package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

/** Wraps only mobile browse screens; child scrollables receive the gesture first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobilePullToRefresh(
    screenKey: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    var requested by remember(screenKey) { mutableStateOf(false) }

    // refresh() coalesces events for 250 ms before setting busy. Keep the
    // gesture latched across that interval and release it when work finishes,
    // or when navigation/cancellation means no request was started.
    LaunchedEffect(screenKey, requested, busy) {
        if (requested && !busy) {
            delay(600)
            requested = false
        }
    }

    PullToRefreshBox(
        isRefreshing = requested && busy,
        onRefresh = {
            if (!busy && !requested) {
                requested = true
                onRefresh()
            }
        },
        modifier = Modifier.fillMaxSize().testTag("mobile-pull-refresh"),
        content = { content() },
    )
}
