/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.music.innertube.models.WatchEndpoint
import com.music.vivi.constants.AccountEmailKey
import com.music.vivi.constants.AccountNameKey
import com.music.vivi.constants.ViviConnectDeviceNameKey
import com.music.vivi.constants.ViviConnectEnabledKey
import com.music.vivi.constants.ViviConnectSameAccountOnlyKey
import com.music.vivi.models.MediaMetadata
import com.music.vivi.playback.PlayerConnection
import com.music.vivi.playback.queues.YouTubeQueue
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinator for Vivi Connect.
 * Manages discovery, on-device server, client, remote control, and playback transfer.
 */
@Singleton
class ViviConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ViviConnectManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

    private val defaultDeviceName: String by lazy {
        val model = Build.MODEL ?: "Android Device"
        if (model.startsWith(Build.MANUFACTURER, ignoreCase = true)) model
        else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} $model"
    }

    private val _deviceName = MutableStateFlow(defaultDeviceName)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _sameAccountOnly = MutableStateFlow(true)
    val sameAccountOnly: StateFlow<Boolean> = _sameAccountOnly.asStateFlow()

    private val _accountHash = MutableStateFlow("")
    val accountHash: StateFlow<String> = _accountHash.asStateFlow()

    private val discoveryService = ConnectDiscoveryService(context, scope)
    val discoveredDevices: StateFlow<List<ConnectDevice>> = discoveryService.discoveredDevices

    private val client = ConnectClient(scope)
    val connectionState: StateFlow<ClientConnectionState> = client.connectionState
    val connectedDevice: StateFlow<ConnectDevice?> = client.connectedDevice
    val remotePlaybackState: StateFlow<ConnectPlaybackState?> = client.remotePlaybackState

    val isControllingRemote: StateFlow<Boolean> = combine(connectionState, connectedDevice) { state, dev ->
        state == ClientConnectionState.CONNECTED && dev != null
    }.stateIn(scope, SharingStarted.Lazily, false)

    private var playerConnection: PlayerConnection? = null
    private var localPlayerObserverJob: Job? = null

    private val server: ConnectServer = ConnectServer(
        scope = scope,
        onCommandReceived = { type, payload -> handleServerCommand(type, payload) },
        onRequestCurrentState = { getCurrentLocalPlaybackState() }
    )

    init {
        observePreferences()
    }

    fun setPlayerConnection(connection: PlayerConnection?) {
        playerConnection = connection
        if (connection != null && _isEnabled.value) {
            startLocalPlayerObserver()
        } else {
            localPlayerObserverJob?.cancel()
            localPlayerObserverJob = null
        }
    }

    private fun observePreferences() {
        scope.launch {
            context.dataStore.data.collectLatest { prefs ->
                val enabled = prefs[ViviConnectEnabledKey] ?: true
                val customName = prefs[ViviConnectDeviceNameKey]?.takeIf { it.isNotBlank() } ?: defaultDeviceName
                val sameAccount = prefs[ViviConnectSameAccountOnlyKey] ?: true

                val accountEmail = prefs[AccountEmailKey] ?: ""
                val accountName = prefs[AccountNameKey] ?: ""
                val accountIdentifier = accountEmail.ifEmpty { accountName }
                val hash = computeAccountHash(accountIdentifier)

                _isEnabled.value = enabled
                _deviceName.value = customName
                _sameAccountOnly.value = sameAccount
                _accountHash.value = hash

                discoveryService.updateConfig(
                    deviceId = deviceId,
                    deviceName = customName,
                    accountHash = hash,
                    sameAccountOnly = sameAccount
                )
                server.setSecurity(hash, sameAccount)

                if (enabled) {
                    startServices()
                } else {
                    stopServices()
                }
            }
        }
    }

    private fun startServices() {
        if (!_isEnabled.value) return

        val port = server.start()
        if (port > 0) {
            discoveryService.startAdvertising(port)
            discoveryService.startDiscovery()
        }
        startLocalPlayerObserver()
    }

    private fun stopServices() {
        discoveryService.stopAdvertising()
        discoveryService.stopDiscovery()
        server.stop()
        client.disconnect()
        localPlayerObserverJob?.cancel()
        localPlayerObserverJob = null
    }

    private fun startLocalPlayerObserver() {
        localPlayerObserverJob?.cancel()
        val connection = playerConnection ?: return

        localPlayerObserverJob = scope.launch {
            combine(
                connection.mediaMetadata,
                connection.isPlaying,
                connection.playbackState
            ) { metadata, isPlaying, state ->
                Triple(metadata, isPlaying, state)
            }.distinctUntilChanged().collectLatest {
                broadcastLocalState()
            }
        }
    }

    fun broadcastLocalState() {
        if (!server.isRunning) return
        val state = getCurrentLocalPlaybackState()
        server.broadcastState(state)
    }

    private fun getCurrentLocalPlaybackState(): ConnectPlaybackState {
        val connection = playerConnection
        val player = runCatching { connection?.player }.getOrNull()
        val meta = connection?.mediaMetadata?.value

        val track = meta?.let {
            ConnectTrack(
                id = it.id,
                title = it.title,
                artist = it.artists.joinToString(", ") { a -> a.name },
                album = it.album?.title,
                durationMs = (it.duration.toLong() * 1000L).coerceAtLeast(0L),
                thumbnailUrl = it.thumbnailUrl
            )
        }

        return ConnectPlaybackState(
            currentTrack = track,
            isPlaying = player?.playWhenReady == true && player.playbackState != androidx.media3.common.Player.STATE_ENDED,
            positionMs = player?.currentPosition ?: 0L,
            positionTimestamp = System.currentTimeMillis(),
            volume = player?.volume ?: 1.0f,
            isMuted = connection?.isMuted?.value ?: false,
            repeatMode = player?.repeatMode ?: 0,
            shuffleModeEnabled = player?.shuffleModeEnabled ?: false,
            deviceName = _deviceName.value,
            deviceId = deviceId
        )
    }

    private fun handleServerCommand(type: String, payload: String?) {
        val connection = playerConnection ?: return
        Timber.tag(TAG).d("Executing remote command on local player: $type")

        when (type) {
            ConnectCommandTypes.PLAY -> connection.play()
            ConnectCommandTypes.PAUSE -> connection.pause()
            ConnectCommandTypes.TOGGLE_PLAY_PAUSE -> connection.togglePlayPause()
            ConnectCommandTypes.SKIP_NEXT -> connection.seekToNext()
            ConnectCommandTypes.SKIP_PREV -> connection.seekToPrevious()

            ConnectCommandTypes.SEEK -> {
                val seekPayload = payload?.let {
                    runCatching { connectJson.decodeFromString(SeekPayload.serializer(), it) }.getOrNull()
                }
                if (seekPayload != null) {
                    connection.seekTo(seekPayload.positionMs)
                }
            }

            ConnectCommandTypes.SET_VOLUME -> {
                val volPayload = payload?.let {
                    runCatching { connectJson.decodeFromString(VolumePayload.serializer(), it) }.getOrNull()
                }
                if (volPayload != null) {
                    runCatching { connection.player.volume = volPayload.volume }
                }
            }

            ConnectCommandTypes.PLAY_TRACK -> {
                val trackPayload = payload?.let {
                    runCatching { connectJson.decodeFromString(PlayTrackPayload.serializer(), it) }.getOrNull()
                }
                if (trackPayload != null) {
                    val endpoint = WatchEndpoint(videoId = trackPayload.trackId)
                    val preload = MediaMetadata(
                        id = trackPayload.trackId,
                        title = trackPayload.title ?: "",
                        artists = listOf(MediaMetadata.Artist(id = null, name = trackPayload.artist ?: "")),
                        duration = (trackPayload.durationMs / 1000).toInt(),
                        thumbnailUrl = trackPayload.thumbnailUrl,
                        album = trackPayload.album?.let { MediaMetadata.Album(id = "", title = it) }
                    )
                    connection.playQueue(YouTubeQueue(endpoint, preload))
                    connection.play()
                }
            }

            ConnectCommandTypes.TRANSFER_PLAYBACK -> {
                val transfer = payload?.let {
                    runCatching { connectJson.decodeFromString(TransferPlaybackPayload.serializer(), it) }.getOrNull()
                }
                if (transfer?.track != null) {
                    val track = transfer.track
                    val endpoint = WatchEndpoint(videoId = track.id)
                    val preload = MediaMetadata(
                        id = track.id,
                        title = track.title,
                        artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
                        duration = (track.durationMs / 1000).toInt(),
                        thumbnailUrl = track.thumbnailUrl,
                        album = track.album?.let { MediaMetadata.Album(id = "", title = it) }
                    )
                    connection.playQueue(YouTubeQueue(endpoint, preload))
                    if (transfer.positionMs > 0) {
                        connection.seekTo(transfer.positionMs)
                    }
                    if (transfer.isPlaying) {
                        connection.play()
                    }
                }
            }
        }
        broadcastLocalState()
    }

    fun connectToDevice(device: ConnectDevice) {
        client.connect(
            device = device,
            localDeviceId = deviceId,
            localDeviceName = _deviceName.value,
            accountHash = _accountHash.value
        )
    }

    fun disconnectFromDevice() {
        client.disconnect()
    }

    fun remotePlay() = client.sendPlay()
    fun remotePause() = client.sendPause()
    fun remoteTogglePlayPause() = client.sendTogglePlayPause()
    fun remoteSeekTo(positionMs: Long) = client.sendSeek(positionMs)
    fun remoteSeekToNext() = client.sendSkipNext()
    fun remoteSeekToPrevious() = client.sendSkipPrev()
    fun remoteSetVolume(volume: Float) = client.sendSetVolume(volume)

    /**
     * Transfer playback to THIS device (Spotify Connect "Play on this phone"):
     * Takes remote track & position, starts local playback, and pauses the remote device.
     */
    fun transferPlaybackToThisDevice() {
        val remoteState = remotePlaybackState.value ?: return
        val track = remoteState.currentTrack ?: return
        val connection = playerConnection ?: return

        // 1. Pause remote
        client.sendPause()

        // 2. Play locally
        val endpoint = WatchEndpoint(videoId = track.id)
        val preload = MediaMetadata(
            id = track.id,
            title = track.title,
            artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
            duration = (track.durationMs / 1000).toInt(),
            thumbnailUrl = track.thumbnailUrl,
            album = track.album?.let { MediaMetadata.Album(id = "", title = it) }
        )

        connection.playQueue(YouTubeQueue(endpoint, preload))
        if (remoteState.positionMs > 0) {
            connection.seekTo(remoteState.positionMs)
        }
        connection.play()

        // 3. Disconnect client as we are now the local player
        client.disconnect()
    }

    /**
     * Transfer local playback to a remote device:
     * Pauses local playback and commands the remote device to start playing the current track & position.
     */
    fun transferPlaybackToRemoteDevice(device: ConnectDevice) {
        val connection = playerConnection ?: return
        val meta = connection.mediaMetadata.value ?: return
        val player = runCatching { connection.player }.getOrNull()
        val currentPos = player?.currentPosition ?: 0L
        val wasPlaying = player?.playWhenReady == true

        // Pause local player
        connection.pause()

        // Connect and transfer
        connectToDevice(device)
        scope.launch {
            // Wait briefly for connection handshake
            delay(400)
            val track = ConnectTrack(
                id = meta.id,
                title = meta.title,
                artist = meta.artists.joinToString(", ") { it.name },
                album = meta.album?.title,
                durationMs = (meta.duration.toLong() * 1000L).coerceAtLeast(0L),
                thumbnailUrl = meta.thumbnailUrl
            )
            client.sendTransferPlayback(track, currentPos, wasPlaying)
        }
    }

    private fun computeAccountHash(account: String): String {
        if (account.isBlank()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(account.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            ""
        }
    }
}
