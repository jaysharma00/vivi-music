/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight, non-blocking on-device TCP Server for Vivi Connect.
 * Runs on local Wi-Fi, accepts controller connections, broadcasts playback state,
 * and executes remote playback commands on the local player.
 */
class ConnectServer(
    private val scope: CoroutineScope,
    private val onCommandReceived: (commandType: String, payload: String?) -> Unit,
    private val onRequestCurrentState: () -> ConnectPlaybackState
) {
    companion object {
        private const val TAG = "ConnectServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    val isRunning: Boolean
        get() = serverSocket != null && !serverSocket!!.isClosed

    private var localAccountHash: String = ""
    private var requireSameAccount: Boolean = true

    private val activeClients = CopyOnWriteArrayList<ClientSession>()

    fun setSecurity(accountHash: String, sameAccountOnly: Boolean) {
        localAccountHash = accountHash
        requireSameAccount = sameAccountOnly
    }

    fun start(): Int {
        if (isRunning) return port

        return try {
            val sSocket = ServerSocket(0)
            serverSocket = sSocket
            val boundPort = sSocket.localPort
            Timber.tag(TAG).d("ConnectServer started on port $boundPort")

            serverJob = scope.launch(Dispatchers.IO) {
                while (isActive && !sSocket.isClosed) {
                    try {
                        val clientSocket = sSocket.accept()
                        Timber.tag(TAG).d("Client connected from ${clientSocket.inetAddress.hostAddress}")
                        val session = ClientSession(clientSocket)
                        activeClients.add(session)
                        session.start()
                    } catch (e: Exception) {
                        if (sSocket.isClosed) break
                        Timber.tag(TAG).e(e, "Error accepting client socket")
                    }
                }
            }
            boundPort
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start ConnectServer")
            0
        }
    }

    fun broadcastState(state: ConnectPlaybackState) {
        if (activeClients.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val jsonPayload = try {
                connectJson.encodeToString(ConnectPlaybackState.serializer(), state)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error serializing ConnectPlaybackState")
                return@launch
            }
            val message = ConnectMessage(ConnectCommandTypes.STATE_UPDATE, jsonPayload)
            val jsonMessage = try {
                connectJson.encodeToString(ConnectMessage.serializer(), message) + "\n"
            } catch (e: Exception) {
                return@launch
            }

            for (client in activeClients) {
                client.sendMessage(jsonMessage)
            }
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverJob = null
            for (client in activeClients) {
                client.close()
            }
            activeClients.clear()
            serverSocket?.close()
            serverSocket = null
            Timber.tag(TAG).d("ConnectServer stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping ConnectServer")
        }
    }

    private inner class ClientSession(private val socket: Socket) {
        private var reader: BufferedReader? = null
        private var writer: BufferedWriter? = null
        private var job: Job? = null
        private var isHandshakeVerified = false

        fun start() {
            job = scope.launch(Dispatchers.IO) {
                try {
                    socket.tcpNoDelay = true
                    reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

                    while (isActive && !socket.isClosed) {
                        val line = reader?.readLine() ?: break
                        handleIncomingLine(line)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).d("Client session disconnected: ${e.message}")
                } finally {
                    close()
                }
            }
        }

        fun sendMessage(rawJsonLine: String) {
            scope.launch(Dispatchers.IO) {
                try {
                    writer?.write(rawJsonLine)
                    writer?.flush()
                } catch (e: Exception) {
                    close()
                }
            }
        }

        private suspend fun handleIncomingLine(line: String) {
            val message = try {
                connectJson.decodeFromString(ConnectMessage.serializer(), line)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Malformed message received: $line")
                return
            }

            when (message.type) {
                ConnectCommandTypes.HANDSHAKE -> {
                    val handshake = message.payload?.let {
                        runCatching { connectJson.decodeFromString(HandshakePayload.serializer(), it) }.getOrNull()
                    }

                    if (requireSameAccount && localAccountHash.isNotEmpty()) {
                        if (handshake?.accountHash != localAccountHash) {
                            Timber.tag(TAG).w("Handshake account mismatch: ${handshake?.accountHash} != $localAccountHash")
                            close()
                            return
                        }
                    }

                    isHandshakeVerified = true
                    Timber.tag(TAG).d("Handshake verified for client: ${handshake?.deviceName}")

                    // Send back ACK with current state
                    val currentState = withContext(Dispatchers.Main) {
                        onRequestCurrentState()
                    }
                    val stateJson = connectJson.encodeToString(ConnectPlaybackState.serializer(), currentState)
                    val ack = ConnectMessage(ConnectCommandTypes.HANDSHAKE_ACK, stateJson)
                    sendMessage(connectJson.encodeToString(ConnectMessage.serializer(), ack) + "\n")
                }

                ConnectCommandTypes.PING -> {
                    val pong = ConnectMessage(ConnectCommandTypes.PONG)
                    sendMessage(connectJson.encodeToString(ConnectMessage.serializer(), pong) + "\n")
                }

                ConnectCommandTypes.DISCONNECT -> {
                    close()
                }

                else -> {
                    if (!isHandshakeVerified && requireSameAccount && localAccountHash.isNotEmpty()) {
                        Timber.tag(TAG).w("Unauthorized command without handshake")
                        close()
                        return
                    }

                    withContext(Dispatchers.Main) {
                        onCommandReceived(message.type, message.payload)
                    }
                }
            }
        }

        fun close() {
            try {
                job?.cancel()
                job = null
                reader?.close()
                writer?.close()
                socket.close()
            } catch (e: Exception) {
                // Ignore closing errors
            } finally {
                activeClients.remove(this)
            }
        }
    }
}
