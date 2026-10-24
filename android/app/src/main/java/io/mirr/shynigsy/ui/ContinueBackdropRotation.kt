package io.mirr.shynigsy.ui

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.delay

internal const val CONTINUE_BACKDROP_INTERVAL_MS = 6_000L

/** The finite shelf supplies every destination; rotation never manufactures a media identity. */
internal fun nextContinueBackdropKey(keys: List<String>, selectedKey: String): String? {
    if (keys.size < 2) return null
    val current = keys.indexOf(selectedKey)
    return keys[if (current < 0) 0 else (current + 1) % keys.size]
}

/** A clipped or scrolled-away hero does not keep requesting artwork in the background. */
internal fun isContinueBackdropVisible(coordinates: LayoutCoordinates): Boolean {
    if (!coordinates.isAttached || coordinates.size.height <= 0) return false
    val visible = coordinates.boundsInWindow()
    return visible.width > 0 && visible.height >= coordinates.size.height * .5f
}

/**
 * One timer per visible hero, with no frame polling, focus movement, scrolling or metadata request.
 * A new selection or interaction starts a fresh interval. Backgrounding and dialogs cancel it.
 */
@Composable
internal fun rememberContinueBackdropRotation(
    keys: List<String>,
    selectedKey: String,
    enabled: Boolean,
    interactionRevision: Int,
    onAdvance: (String) -> Unit,
    intervalMs: Long = CONTINUE_BACKDROP_INTERVAL_MS,
) {
    val view = LocalView.current
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
    }
    var windowFocused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    var availabilityRevision by remember(view, lifecycle) { mutableIntStateOf(0) }
    val advance by rememberUpdatedState(onAdvance)
    val currentKeys by rememberUpdatedState(keys)
    val currentSelection by rememberUpdatedState(selectedKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentInteraction by rememberUpdatedState(interactionRevision)
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
            availabilityRevision++
        }
        val windowObserver = ViewTreeObserver.OnWindowFocusChangeListener {
            windowFocused = it
            availabilityRevision++
        }
        lifecycle?.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowObserver)
        onDispose {
            lifecycle?.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowObserver)
        }
    }
    LaunchedEffect(keys, selectedKey, enabled, resumed, windowFocused, interactionRevision, availabilityRevision, intervalMs) {
        if (!enabled || !resumed || !windowFocused || intervalMs <= 0) return@LaunchedEffect
        val next = nextContinueBackdropKey(keys, selectedKey) ?: return@LaunchedEffect
        val availabilityAtStart = availabilityRevision
        delay(intervalMs)
        // A stopped Recomposer may not have cancelled this effect yet: verify the live window too.
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true || !view.hasWindowFocus() ||
            !currentEnabled || currentKeys != keys || currentSelection != selectedKey ||
            currentInteraction != interactionRevision || availabilityRevision != availabilityAtStart) return@LaunchedEffect
        advance(next)
    }
}
