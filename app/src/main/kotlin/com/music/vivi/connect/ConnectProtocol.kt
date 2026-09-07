/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ConnectDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val accountHash: String,
    val model: String = "",
    val isSelf: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

@Serializable
data class ConnectTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null
)

@Serializable
data class ConnectPlaybackState(
    val currentTrack: ConnectTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val positionTimestamp: Long = 0L,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val repeatMode: Int = 0,
    val shuffleModeEnabled: Boolean = false,
    val deviceName: String = "",
    val deviceId: String = ""
)

object ConnectCommandTypes {
    const val HANDSHAKE = "HANDSHAKE"
    const val HANDSHAKE_ACK = "HANDSHAKE_ACK"
    const val STATE_UPDATE = "STATE_UPDATE"
    const val PLAY = "PLAY"
    const val PAUSE = "PAUSE"
    const val TOGGLE_PLAY_PAUSE = "TOGGLE_PLAY_PAUSE"
    const val SEEK = "SEEK"
    const val SKIP_NEXT = "SKIP_NEXT"
    const val SKIP_PREV = "SKIP_PREV"
    const val SET_VOLUME = "SET_VOLUME"
    const val PLAY_TRACK = "PLAY_TRACK"
    const val TRANSFER_PLAYBACK = "TRANSFER_PLAYBACK"
    const val PING = "PING"
    const val PONG = "PONG"
    const val DISCONNECT = "DISCONNECT"
}

@Serializable
data class ConnectMessage(
    val type: String,
    val payload: String? = null
)

@Serializable
data class HandshakePayload(
    val deviceId: String,
    val deviceName: String,
    val accountHash: String,
    val protocolVersion: Int = 1
)

@Serializable
data class SeekPayload(
    val positionMs: Long
)

@Serializable
data class VolumePayload(
    val volume: Float
)

@Serializable
data class PlayTrackPayload(
    val trackId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null
)

@Serializable
data class TransferPlaybackPayload(
    val track: ConnectTrack?,
    val positionMs: Long,
    val isPlaying: Boolean
)

val connectJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}
