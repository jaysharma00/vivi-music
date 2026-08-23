/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Tells the currently composed SearchScreen to expand and focus the
 * keyboard, independent of Navigation-Compose's back stack.
 *
 * The nav-bar Search icon needs to trigger this both when it first
 * navigates to Search AND on every later tap while Search is already
 * open. Routing this through a NavBackStackEntry.savedStateHandle flag
 * turned out to be unreliable: with the popUpTo/restoreState pattern
 * used for bottom-nav navigation, the entry that SearchScreen ends up
 * observing doesn't always reflect the flag set right after navigate()
 * fires, so the flag was silently missed and the bar only expanded on
 * the previously-required extra manual tap of the bar. A plain
 * in-memory counter observed directly by SearchScreen sidesteps that
 * back stack timing entirely.
 */
object SearchFocusRequest {
    var requestId by mutableIntStateOf(0)
        private set

    fun trigger() {
        requestId++
    }
}
