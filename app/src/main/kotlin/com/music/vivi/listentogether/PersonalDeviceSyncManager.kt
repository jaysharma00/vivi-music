/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.listentogether

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import com.music.vivi.constants.PersonalSyncDeviceNameKey
import com.music.vivi.constants.PersonalSyncEnabledKey
import com.music.vivi.constants.PersonalSyncRoomCodeKey
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Personal Device Sync" - a thin, opt-in layer on top of [ListenTogetherClient]
 * for syncing playback across YOUR OWN devices under one account, rather than
 * hosting a room for other people. It deliberately reuses Listen Together's
 * existing, already-working protocol and relay server as-is - no new wire
 * protocol, no server changes - because that infrastructure already does
 * everything needed (room creation/join, join approval, and live playback
 * sync of track/position/volume between whoever's host and everyone else in
 * the room).
 *
 * What this adds on top:
 * - Persists the room code INDEFINITELY once you opt in (Listen Together's
 *   own [com.music.vivi.constants.ListenTogetherRoomCodeKey] is intentionally
 *   short-lived - a 10-minute grace period for crash/network recovery, not
 *   for "come back next week and still be connected").
 * - Automatically rejoins that saved room whenever the client connects, so
 *   after a ONE-TIME code entry on each device, opening the app anywhere just
 *   silently syncs back in - no server-side account linking exists to make
 *   this fully zero-config across devices that have never shared a code.
 * - Automatically saves the room's own code when THIS device is the one that
 *   creates it, so the creating device also benefits from the same indefinite
 *   auto-rejoin, not just devices that joined with a typed-in code.
 *
 * Turning on Listen Together's own "Auto-approval" setting is what makes
 * joins from your other devices silent too - that toggle already exists and
 * already works; this class doesn't duplicate it.
 */
@Singleton
class PersonalDeviceSyncManager @Inject constructor(
    private val client: ListenTogetherClient,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isPersonalSyncActive = MutableStateFlow(false)
    /** True once this device has successfully (re)joined its saved personal-sync room. */
    val isPersonalSyncActive: StateFlow<Boolean> = _isPersonalSyncActive.asStateFlow()

    init {
        // The underlying client only connects when something asks it to -
        // normally that's the Listen Together screen's own UI. Personal sync
        // needs to work without that screen ever being opened, so kick off
        // the connection here if the user has already opted in.
        scope.launch {
            if (context.dataStore.get(PersonalSyncEnabledKey, false)) {
                client.connect()
            }
        }

        // Auto-rejoin on every (re)connect - covers cold app starts, not just
        // the client's own short-lived crash-recovery reconnects.
        scope.launch {
            client.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    attemptAutoRejoin()
                }
            }
        }

        // If THIS device is the one that creates the room, remember its own
        // code too - otherwise only devices that manually typed in a code
        // would get the indefinite auto-rejoin benefit.
        scope.launch {
            client.events.collect { event ->
                if (event is ListenTogetherEvent.RoomCreated) {
                    val enabled = context.dataStore.get(PersonalSyncEnabledKey, false)
                    if (enabled) {
                        persistRoomCode(event.roomCode)
                    }
                }
                if (event is ListenTogetherEvent.JoinApproved) {
                    val enabled = context.dataStore.get(PersonalSyncEnabledKey, false)
                    if (enabled) {
                        _isPersonalSyncActive.value = true
                    }
                }
                if (event is ListenTogetherEvent.Disconnected || event is ListenTogetherEvent.Kicked) {
                    _isPersonalSyncActive.value = false
                }
            }
        }
    }

    private suspend fun attemptAutoRejoin() {
        val enabled = context.dataStore.get(PersonalSyncEnabledKey, false)
        if (!enabled) return
        if (client.roomState.first() != null) return // already in a room

        val savedCode = context.dataStore.get(PersonalSyncRoomCodeKey, "")
        if (savedCode.isBlank()) return

        val deviceName = deviceName()
        // Small delay so this doesn't race the client's own just-connected
        // bookkeeping (e.g. its own short-lived session reconnect attempt,
        // which takes priority if it succeeds).
        delay(500)
        if (client.roomState.first() == null) {
            client.joinRoom(savedCode, deviceName)
        }
    }

    /** Creates a new personal-sync room. Call this from exactly one device to start. */
    fun startPersonalSync(deviceName: String = deviceName()) {
        scope.launch {
            context.dataStore.edit { it[PersonalSyncEnabledKey] = true }
            saveDeviceName(deviceName)
        }
        client.createRoom(deviceName)
    }

    /** Joins an existing personal-sync room with a code shown on another of your devices. */
    fun joinPersonalSync(roomCode: String, deviceName: String = deviceName()) {
        scope.launch {
            context.dataStore.edit { it[PersonalSyncEnabledKey] = true }
            saveDeviceName(deviceName)
            persistRoomCode(roomCode)
        }
        client.joinRoom(roomCode, deviceName)
    }

    /** Turns personal sync off on this device and forgets the saved room code. */
    fun stopPersonalSync() {
        scope.launch {
            context.dataStore.edit {
                it[PersonalSyncEnabledKey] = false
                it.remove(PersonalSyncRoomCodeKey)
            }
        }
        _isPersonalSyncActive.value = false
        client.leaveRoom()
    }

    suspend fun currentRoomCode(): String? =
        context.dataStore.get(PersonalSyncRoomCodeKey, "").ifBlank { null }

    suspend fun isEnabled(): Boolean = context.dataStore.get(PersonalSyncEnabledKey, false)

    private suspend fun persistRoomCode(code: String) {
        context.dataStore.edit { it[PersonalSyncRoomCodeKey] = code }
    }

    private suspend fun saveDeviceName(name: String) {
        context.dataStore.edit { it[PersonalSyncDeviceNameKey] = name }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
