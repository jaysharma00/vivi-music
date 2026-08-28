/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.MotionEvent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.utils.YouTubeUrlParser
import com.music.vivi.LocalDatabase
import com.music.vivi.LocalIsPlayerExpanded
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.R
import com.music.vivi.constants.PauseSearchHistoryKey
import com.music.vivi.constants.SearchSource
import com.music.vivi.constants.SearchSourceKey
import com.music.vivi.db.entities.SearchHistory
import com.music.vivi.playback.queues.YouTubeQueue
import com.music.vivi.ui.component.NavigationTitle
import com.music.vivi.utils.rememberEnumPreference
import com.music.vivi.utils.rememberPreference
import com.music.vivi.utils.SearchFocusRequest
import com.music.vivi.ui.screens.search.suggestions.SuggestionsTabContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    navController: NavController,
    pureBlack: Boolean
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isPlayerExpanded = LocalIsPlayerExpanded.current
    val playerConnection = LocalPlayerConnection.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    var isFirstLaunch by rememberSaveable { mutableStateOf(true) }
    
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showSearchContent by remember { mutableStateOf(false) }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            // Small delay to let the initial expansion animation run smoothly
            // before composing the potentially heavy search results/history
            delay(100)
            showSearchContent = true
        } else {
            showSearchContent = false
        }
    }

    // Tapping the Search nav bar icon triggers this (see MainActivity.kt's
    // onNavItemClick / SearchFocusRequest) so it expands straight into the
    // full search input with the keyboard open - the same state you'd reach
    // by tapping the search bar itself - instead of landing on the
    // collapsed state. Uses a plain in-memory counter (SearchFocusRequest)
    // rather than a value stashed in the nav back stack entry's
    // savedStateHandle, since that flag wasn't reliably visible to this
    // screen under the popUpTo/restoreState navigation pattern used for the
    // bottom bar. The actual focus/keyboard grab happens in the SearchBar's
    // content lambda below - see the comment there for why it has to run
    // from in there rather than from here.
    LaunchedEffect(SearchFocusRequest.requestId) {
        if (SearchFocusRequest.requestId > 0) {
            searchActive = true
        }
    }

    val searchBarHorizontalPadding by animateDpAsState(
        targetValue = if (searchActive) 0.dp else 16.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "SearchBarHorizontalPadding"
    )
    val searchBarVerticalPadding by animateDpAsState(
        targetValue = if (searchActive) 0.dp else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "SearchBarVerticalPadding"
    )

    val onSearch: (String) -> Unit = remember {
        { searchQuery ->
            if (searchQuery.isNotEmpty()) {
                focusManager.clearFocus()
                println("[LINK_PARSE_DEBUG] onSearch initiated for: $searchQuery")
                
                when (val parsedUrl = YouTubeUrlParser.parse(searchQuery)) {
                    is YouTubeUrlParser.ParsedUrl.Video -> {
                        println("[LINK_PARSE_DEBUG] Performing direct playback for Video ID: ${parsedUrl.id}")
                        playerConnection?.playQueue(
                            YouTubeQueue(
                                WatchEndpoint(videoId = parsedUrl.id),
                            ),
                        )
                    }

                    is YouTubeUrlParser.ParsedUrl.Artist -> {
                        println("[LINK_PARSE_DEBUG] Navigating to Artist: ${parsedUrl.id}")
                        navController.navigate("artist/${parsedUrl.id}")
                    }

                    null -> {
                        println("[LINK_PARSE_DEBUG] No URL detected in search action")
                        navController.navigate("search/${URLEncoder.encode(searchQuery, "UTF-8")}")
                    }
                }

                if (!pauseSearchHistory) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.query {
                            insert(SearchHistory(query = searchQuery))
                        }
                    }
                }
            }
        }
    }

    val onSearchFromSuggestion: (String) -> Unit = remember {
        { searchQuery ->
            if (searchQuery.isNotEmpty()) {
                focusManager.clearFocus()
                println("[LINK_PARSE_DEBUG] onSearchFromSuggestion initiated for: $searchQuery")
                
                when (val parsedUrl = YouTubeUrlParser.parse(searchQuery)) {
                    is YouTubeUrlParser.ParsedUrl.Video -> {
                        println("[LINK_PARSE_DEBUG] Performing direct playback from suggestion for Video ID: ${parsedUrl.id}")
                        playerConnection?.playQueue(
                            YouTubeQueue(
                                WatchEndpoint(videoId = parsedUrl.id),
                            ),
                        )
                    }

                    is YouTubeUrlParser.ParsedUrl.Artist -> {
                        println("[LINK_PARSE_DEBUG] Navigating to Artist from suggestion: ${parsedUrl.id}")
                        navController.navigate("artist/${parsedUrl.id}")
                    }

                    null -> {
                        println("[LINK_PARSE_DEBUG] No URL detected in suggestion action")
                        navController.navigate("search/${URLEncoder.encode(searchQuery, "UTF-8")}")
                    }
                }

                if (!pauseSearchHistory) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.query {
                            insert(SearchHistory(query = searchQuery))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            SearchBar(
                    query = query.text,
                    onQueryChange = { query = TextFieldValue(it) },
                    onSearch = { 
                        onSearch(it)
                        searchActive = false
                    },
                    active = searchActive,
                    onActiveChange = { searchActive = it },
                    placeholder = {
                        DynamicSearchPlaceholder(
                            searchSource = searchSource,
                            style = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 16.sp
                            )
                        )
                    },
                    leadingIcon = {
                        IconButton(onClick = {
                            if (searchActive) {
                                searchActive = false
                                query = TextFieldValue("") // Clear text when dismissing search
                            } else {
                                searchActive = true // Focus search instead of navigating back
                            }
                        }) {
                            Icon(
                                painter = painterResource(if (searchActive) R.drawable.arrow_back else R.drawable.search),
                                contentDescription = if (searchActive) stringResource(R.string.dismiss) else null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.text.isNotEmpty()) {
                                IconButton(onClick = { query = TextFieldValue("") }) {
                                    Icon(
                                        painter = painterResource(R.drawable.close),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    searchSource = if (searchSource == SearchSource.ONLINE) 
                                        SearchSource.LOCAL else SearchSource.ONLINE
                                }
                            ) {
                                Icon(
                                    painter = painterResource(
                                        when (searchSource) {
                                            SearchSource.LOCAL -> R.drawable.library_music
                                            SearchSource.ONLINE -> R.drawable.globe_search
                                        }
                                    ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    colors = SearchBarDefaults.colors(
                        containerColor = if (pureBlack) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = searchBarHorizontalPadding)
                        .padding(vertical = searchBarVerticalPadding)
                ) {
                    if (showSearchContent) {
                        // This lambda is the SearchBar's expanded/content slot, which
                        // Material3 composes inside its own internal Popup once active
                        // becomes true - a separate focus root (and separate Android
                        // View/window) from the rest of the screen. That's why grabbing
                        // focus from a LaunchedEffect up in the outer SearchScreen
                        // composable never worked, and why moveFocus/requestFocus alone
                        // - even from here - still don't reliably bring up the keyboard:
                        // Android's IME auto-show can specifically require the focus to
                        // have come from a genuine touch event, not a programmatic one.
                        //
                        // So instead of asking for focus, we synthesize an actual tap -
                        // exactly what a real second manual tap on the bar already does
                        // reliably. The internal query field itself isn't something we
                        // can get a position for directly, but it always sits immediately
                        // above this content slot, in the same popup window, so we tap
                        // just above this Box's own captured top edge instead of trying
                        // to guess coordinates across windows.
                        val popupView = LocalView.current
                        val popupDensity = LocalDensity.current
                        var contentTopYPx by remember { mutableStateOf<Float?>(null) }
                        var contentCenterXPx by remember { mutableStateOf<Float?>(null) }
                        var contentRightXPx by remember { mutableStateOf<Float?>(null) }
                        // Bumped whenever the query is replaced by picking a
                        // suggestion/history entry (see onQueryChange below) rather
                        // than by the user actually typing, so the cursor can be
                        // moved to the end of the newly-filled text - see the
                        // LaunchedEffect(cursorToEndTrigger) below for why.
                        var cursorToEndTrigger by remember { mutableIntStateOf(0) }

                        LaunchedEffect(Unit) {
                            fun isImeVisible() = ViewCompat.getRootWindowInsets(popupView)
                                ?.isVisible(WindowInsetsCompat.Type.ime()) == true

                            // Let the real tap that opened this screen finish being
                            // processed, and the expand animation + content layout
                            // fully settle, before touching anything ourselves.
                            // Firing a synthetic tap too close to the real one that
                            // triggered it - or onto a field that's already gained
                            // focus - reads to Android as a rapid double-tap, which
                            // opens the text-selection/copy-paste toolbar instead of
                            // just showing the keyboard.
                            delay(300)

                            keyboardController?.show()
                            if (isImeVisible()) return@LaunchedEffect

                            // Give that a moment to actually take effect before
                            // deciding it didn't work - IME visibility updates
                            // aren't instant.
                            delay(200)
                            if (isImeVisible()) return@LaunchedEffect

                            // Still not up - fall back to exactly one synthetic tap
                            // on the (inferred) query field position. Deliberately
                            // not retried: a second tap on a field that the first
                            // one already focused is exactly what triggers the
                            // selection toolbar instead of just the keyboard.
                            val topY = contentTopYPx
                            val centerX = contentCenterXPx
                            if (topY != null && centerX != null) {
                                val inputFieldHeightPx = with(popupDensity) { 56.dp.toPx() }
                                val tapY = (topY - inputFieldHeightPx / 2).coerceAtLeast(0f)
                                val downTime = SystemClock.uptimeMillis()
                                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, centerX, tapY, 0)
                                val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, centerX, tapY, 0)
                                try {
                                    popupView.dispatchTouchEvent(down)
                                    popupView.dispatchTouchEvent(up)
                                } finally {
                                    down.recycle()
                                    up.recycle()
                                }
                            }
                        }

                        // Material3's SearchBar only takes a plain query String, with
                        // no way to pass a desired cursor/selection - it manages its
                        // own internal cursor entirely privately. So even though
                        // picking a suggestion/history entry correctly builds a
                        // TextFieldValue with the selection at the end of the new
                        // text, that selection is silently ignored by the actual
                        // rendered field, which keeps whatever cursor position it
                        // already had. The same synthetic-touch approach used above
                        // for focus works here too: a tap near the right edge of the
                        // (inferred) input row reliably lands the cursor at the end
                        // of the text, exactly like a real tap there would.
                        LaunchedEffect(cursorToEndTrigger) {
                            if (cursorToEndTrigger == 0) return@LaunchedEffect
                            delay(50)
                            val topY = contentTopYPx
                            val rightX = contentRightXPx
                            if (topY != null && rightX != null) {
                                val inputFieldHeightPx = with(popupDensity) { 56.dp.toPx() }
                                // Wide enough to clear BOTH trailing icons when a
                                // suggestion has just been picked - the Clear (X)
                                // button appears alongside the search-source toggle
                                // once the field is non-empty, doubling the trailing
                                // icon zone from ~48dp to ~96dp. A narrower inset here
                                // landed the tap on the Clear button instead of past
                                // it, wiping the query it was supposed to be fixing
                                // the cursor position in.
                                val trailingIconInsetPx = with(popupDensity) { 120.dp.toPx() }
                                val tapY = (topY - inputFieldHeightPx / 2).coerceAtLeast(0f)
                                val tapX = (rightX - trailingIconInsetPx).coerceAtLeast(0f)
                                val downTime = SystemClock.uptimeMillis()
                                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
                                val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, tapX, tapY, 0)
                                try {
                                    popupView.dispatchTouchEvent(down)
                                    popupView.dispatchTouchEvent(up)
                                } finally {
                                    down.recycle()
                                    up.recycle()
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                contentTopYPx = bounds.top
                                contentCenterXPx = bounds.left + bounds.width / 2f
                                contentRightXPx = bounds.right
                            }
                        ) {
                            when (searchSource) {
                                SearchSource.LOCAL -> LocalSearchScreen(
                                    query = query.text,
                                    navController = navController,
                                    onDismiss = { searchActive = false },
                                    pureBlack = pureBlack
                                )
                                SearchSource.ONLINE -> OnlineSearchScreen(
                                    query = query.text,
                                    onQueryChange = {
                                        query = it
                                        cursorToEndTrigger++
                                    },
                                    navController = navController,
                                    onSearch = {
                                        onSearchFromSuggestion(it)
                                        searchActive = false
                                    },
                                    onDismiss = { searchActive = false },
                                    pureBlack = pureBlack
                                )
                            }
                        }
                    }
                }
        },
        containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background
    ) { paddingValues ->
        val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
        
        Box(
            modifier = Modifier
                .padding(top = paddingValues.calculateTopPadding())
                .fillMaxSize()
        ) {
            val tabPadding = PaddingValues(bottom = bottomPadding)
            SuggestionsTabContent(navController = navController, contentPadding = tabPadding)
        }
    }

    // Handle lifecycle events to manage keyboard visibility
    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Always hide keyboard when resuming if player is expanded
                    if (isPlayerExpanded) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    } else if (isFirstLaunch) {
                        // Only request focus on first launch when player is not expanded
                        try {
                            focusRequester.requestFocus()
                        } catch (e: Exception) {
                            // Ignore focus request failures
                        }
                        isFirstLaunch = false
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Clear focus when pausing to prevent keyboard from showing on resume
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        // Initial check - hide keyboard if player is expanded
        if (isPlayerExpanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
