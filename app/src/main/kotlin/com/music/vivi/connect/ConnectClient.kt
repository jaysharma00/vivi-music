/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

enum class ClientConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * Lightweight, non-blocking on-device TCP Client for Vivi Connect.
 * Connects to a remote device over local Wi-Fi, receives remote playback state,
 * and sends remote playback controls.
 */
class ConnectClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectClient"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HEARTBEAT_INTERVAL_MS = 8000L
    }

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _connectionState = MutableStateFlow(ClientConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ClientConnectionState> = _connectionState.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<ConnectPlaybackState?>(null)
    val remotePlaybackState: StateFlow<ConnectPlaybackState?> = _remotePlaybackState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<ConnectDevice?>(null)
    val connectedDevice: StateFlow<ConnectDevice?> = _connectedDevice.asStateFlow()

    fun connect(
        device: ConnectDevice,
        localDeviceId: String,
        localDeviceName: String,
        accountHash: String
    ) {
        disconnect()

        _connectionState.value = ClientConnectionState.CONNECTING
        _connectedDevice.value = device

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)

                reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))

                // Send Handshake
                val handshakePayload = HandshakePayload(
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    accountHash = accountHash
                )
                val handshakeJson = connectJson.encodeToString(HandshakePayload.serializer(), handshakePayload)
                val handshakeMsg = ConnectMessage(ConnectCommandTypes.HANDSHAKE, handshakeJson)
                sendRaw(connectJson.encodeToString(ConnectMessage.serializer(), handshakeMsg) + "\n")

                startHeartbeat()

                // Read incoming loop
                while (isActive && !s.isClosed) {
                    val line = reader?.readLine() ?: break
                    handleIncomingLine(line)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Connection failed to ${device.name}")
                _connectionState.value = ClientConnectionState.FAILED
            } finally {
                disconnect()
            }
        }
    }

    private fun handleIncomingLine(line: String) {
        val message = try {
            connectJson.decodeFromString(ConnectMessage.serializer(), line)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to decode message from host: $line")
            return
        }

        when (message.type) {
            ConnectCommandTypes.HANDSHAKE_ACK -> {
                _connectionState.value = ClientConnectionState.CONNECTED
                Timber.tag(TAG).d("Connected successfully to host!")
                val state = message.payload?.let {
                    runCatching { connectJson.decodeFromString(ConnectPlaybackState.serializer(), it) }.getOrNull()
                }
                if (state != null) {
                    _remotePlaybackState.value = state
                }
            }

            ConnectCommandTypes.STATE_UPDATE -> {
                val state = message.payload?.let {
                    runCatching { connectJson.decodeFromString(ConnectPlaybackState.serializer(), it) }.getOrNull()
                }
                if (state != null) {
                    _remotePlaybackState.value = state
                }
            }

            ConnectCommandTypes.PONG -> {
                // Heartbeat alive
            }

            ConnectCommandTypes.DISCONNECT -> {
                disconnect()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendPing()
            }
        }
    }

    private fun sendPing() {
        val msg = ConnectMessage(ConnectCommandTypes.PING)
        sendRaw(connectJson.encodeToString(ConnectMessage.serializer(), msg) + "\n")
    }

    fun sendPlay() = sendCommand(ConnectCommandTypes.PLAY)
    fun sendPause() = sendCommand(ConnectCommandTypes.PAUSE)
    fun sendTogglePlayPause() = sendCommand(ConnectCommandTypes.TOGGLE_PLAY_PAUSE)
    fun sendSkipNext() = sendCommand(ConnectCommandTypes.SKIP_NEXT)
    fun sendSkipPrev() = sendCommand(ConnectCommandTypes.SKIP_PREV)

    fun sendSeek(positionMs: Long) {
        val payload = connectJson.encodeToString(SeekPayload.serializer(), SeekPayload(positionMs))
        sendCommand(ConnectCommandTypes.SEEK, payload)
    }

    fun sendSetVolume(volume: Float) {
        val payload = connectJson.encodeToString(VolumePayload.serializer(), VolumePayload(volume))
        sendCommand(ConnectCommandTypes.SET_VOLUME, payload)
    }

    fun sendPlayTrack(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        durationMs: Long = 0L,
        thumbnailUrl: String? = null
    ) {
        val payload = connectJson.encodeToString(
            PlayTrackPayload.serializer(),
            PlayTrackPayload(trackId, title, artist, album, durationMs, thumbnailUrl)
        )
        sendCommand(ConnectCommandTypes.PLAY_TRACK, payload)
    }

    fun sendTransferPlayback(track: ConnectTrack?, positionMs: Long, isPlaying: Boolean) {
        val payload = connectJson.encodeToString(
            TransferPlaybackPayload.serializer(),
            TransferPlaybackPayload(track, positionMs, isPlaying)
        )
        sendCommand(ConnectCommandTypes.TRANSFER_PLAYBACK, payload)
    }

    private fun sendCommand(type: String, payload: String? = null) {
        val msg = ConnectMessage(type, payload)
        val line = connectJson.encodeToString(ConnectMessage.serializer(), msg) + "\n"
        sendRaw(line)
    }

    private fun sendRaw(rawJsonLine: String) {
        scope.launch(Dispatchers.IO) {
            try {
                writer?.write(rawJsonLine)
                writer?.flush()
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error writing command: ${e.message}")
                disconnect()
            }
        }
    }

    fun disconnect() {
        try {
            heartbeatJob?.cancel()
            heartbeatJob = null
            connectionJob?.cancel()
            connectionJob = null
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            socket = null
            reader = null
            writer = null
            _connectionState.value = ClientConnectionState.DISCONNECTED
            _connectedDevice.value = null
            _remotePlaybackState.value = null
        }
    }
}
