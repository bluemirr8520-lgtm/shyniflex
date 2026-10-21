package io.mirr.shynigsy.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Refresh the visible catalog when a delayed server watch-state write completes. */
object PlaybackCompletionSignals {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun notifyChanged() { revision.update { it + 1 } }
}
