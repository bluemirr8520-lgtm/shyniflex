package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** TV navigation overlays a retained full-width screen and appears only at its left focus boundary. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun TvSidebarDrawer(
    modifier: Modifier = Modifier,
    screenKey: Any?,
    sidebar: @Composable (close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val open = remember { mutableStateOf(false) }
    val bodyReady = remember { mutableStateOf(false) }
    val sidebarReady = remember { mutableStateOf(false) }
    val fallbackEnabled = remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val sidebarFocus = remember { FocusRequester() }
    val fallbackFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val currentScreenKey = rememberUpdatedState(screenKey)
    val memory = remember { TvDrawerFocusMemory() }
    val reveal = remember {
        {
            if (!open.value) {
                bodyFocus.saveFocusedChild()
                memory.savedScreenKey = currentScreenKey.value
                memory.leftInFlight?.let { memory.ownedPresses[it.keyCode] = it.downTime }
                open.value = true
            }
        }
    }
    val close = remember {
        {
            if (open.value) {
                sidebarReady.value = false
                memory.restorePending = true
                open.value = false
            }
        }
    }
    val bodyModifier = remember(focus) {
        Modifier.fillMaxSize().testTag("tv-drawer-body")
            .onGloballyPositioned { bodyReady.value = true }
            .onFocusChanged { memory.bodyHasFocus = it.hasFocus }
            .focusRequester(bodyFocus)
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left && memory.leftInFlight != null && !open.value) {
                        cancelFocusChange()
                        reveal()
                    }
                }
            }.focusGroup()
            .onKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (open.value || key.keyCode != KeyEvent.KEYCODE_DPAD_LEFT) false
                else if (key.action == KeyEvent.ACTION_DOWN) {
                    memory.leftInFlight = TvDrawerPress(key.keyCode, key.downTime)
                    try {
                        // Children handle caret/slider keys first; an ordinary row moves exactly once.
                        if (!focus.moveFocus(FocusDirection.Left) && !open.value) reveal()
                    } finally { memory.leftInFlight = null }
                    true
                } else key.action == KeyEvent.ACTION_UP
            }
    }
    val fallbackModifier = remember {
        Modifier.size(1.dp).testTag("tv-drawer-focus-fallback").focusRequester(fallbackFocus)
            .focusProperties { canFocus = fallbackEnabled.value }
            .onFocusChanged {
                if (it.isFocused) memory.fallbackHadFocus = true
                else if (memory.fallbackHadFocus) {
                    memory.fallbackHadFocus = false
                    fallbackEnabled.value = false
                }
            }.focusable()
    }
    LaunchedEffect(open.value, bodyReady.value, sidebarReady.value, screenKey) {
        if (open.value && sidebarReady.value) {
            withFrameNanos { }
            sidebarFocus.requestFocus(FocusDirection.Enter)
        } else if (!open.value && bodyReady.value && (memory.restorePending || !memory.initialFocusChecked)) {
            withFrameNanos { }
            val restoring = memory.restorePending
            memory.initialFocusChecked = true
            memory.restorePending = false
            if (memory.bodyHasFocus && (!restoring || memory.savedScreenKey != screenKey)) return@LaunchedEffect
            val restored = restoring && memory.savedScreenKey == screenKey && bodyFocus.restoreFocusedChild()
            if (!restored && !bodyFocus.requestFocus(FocusDirection.Enter)) {
                // A new empty/loading screen still needs a remote target from which Left can reopen.
                fallbackEnabled.value = true
                fallbackFocus.requestFocus(FocusDirection.Enter)
            }
        }
    }
    Box(modifier.fillMaxSize().testTag("tv-sidebar-shell").onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (memory.ownedPresses[key.keyCode] == key.downTime) {
            if (key.action == KeyEvent.ACTION_UP || key.isCanceled) memory.ownedPresses.remove(key.keyCode)
            true
        } else false
    }) {
        RetainedTvDrawerBody(bodyModifier, fallbackModifier, content)
        if (open.value) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f))
                .testTag("tv-sidebar-drawer-scrim")
                .pointerInput(close) { detectTapGestures { close() } })
            Box(Modifier.align(Alignment.CenterStart).testTag("tv-sidebar-drawer")
                .onGloballyPositioned { if (open.value) sidebarReady.value = true }
                .focusRequester(sidebarFocus)
                .focusProperties { onExit = { if (open.value) cancelFocusChange() } }
                .focusGroup().onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    if (key.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) false
                    else {
                        if (key.action == KeyEvent.ACTION_DOWN) {
                            memory.ownedPresses[key.keyCode] = key.downTime
                            close()
                        }
                        true
                    }
                }) { sidebar(close) }
        }
    }
    // Register after children so the open drawer owns Back before an underlying screen can navigate.
    BackHandler(enabled = open.value, onBack = close)
}

/** Keeping this slot stable lets opening the drawer leave the screen's composition and layout alone. */
@Composable
private fun RetainedTvDrawerBody(modifier: Modifier, fallbackModifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        Box(fallbackModifier)
    }
}

private data class TvDrawerPress(val keyCode: Int, val downTime: Long)
private class TvDrawerFocusMemory {
    val ownedPresses = mutableMapOf<Int, Long>()
    var leftInFlight: TvDrawerPress? = null
    var savedScreenKey: Any? = null
    var restorePending = false
    var initialFocusChecked = false
    var bodyHasFocus = false
    var fallbackHadFocus = false
}
