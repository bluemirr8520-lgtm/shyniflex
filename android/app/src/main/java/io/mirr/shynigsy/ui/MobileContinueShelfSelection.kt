package io.mirr.shynigsy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ContinueShelfCard(val key: String, val offset: Int, val size: Int)

/** Only the backdrop reads the frame-by-frame offset; the surrounding Home stays still. */
internal class MobileContinueBackdropState {
    private var endpoints by mutableStateOf<Pair<String, String?>?>(null)
    val fromKey: String? get() = endpoints?.first
    val toKey: String? get() = endpoints?.second
    val fraction = Animatable(0f)

    suspend fun follow(motion: ContinueBackdropMotion?) {
        if (motion == null) { clear(); return }
        fraction.snapTo(motion.fraction)
        endpoints = motion.fromKey to motion.toKey
    }

    suspend fun settle(selectedKey: String?) {
        val target = when (selectedKey) {
            fromKey -> 0f
            toKey -> 1f
            else -> null
        }
        if (target != null && endpoints != null)
            fraction.animateTo(target, tween(180, easing = LinearOutSlowInEasing))
        clear()
    }

    fun clear() { endpoints = null }
}

private data class ContinueShelfSample(val dragging: Boolean, val scrolling: Boolean,
    val anchor: String?, val motion: ContinueBackdropMotion?)

/** Follow the leading readable poster, rather than a thin remnant at the viewport edge. */
internal fun continueShelfAnchorKey(cards: List<ContinueShelfCard>, start: Int, end: Int): String? {
    if (end <= start) return null
    val visible = cards.filter { it.size > 0 }.map { card ->
        card to (minOf(end, card.offset + card.size) - maxOf(start, card.offset)).coerceAtLeast(0)
    }.filter { it.second > 0 }
    return visible.firstOrNull { (card, pixels) -> pixels >= (card.size + 1) / 2 }?.first?.key
        ?: visible.maxByOrNull { it.second }?.first?.key
}

/**
 * Touch scrolling owns the hero through the fling. Programmatic reveals and idle backdrop rotation
 * never select a poster. Artwork changes locally; only the settled poster asks for richer metadata.
 */
@Composable
internal fun ObserveMobileContinueShelf(
    row: LazyListState,
    keys: List<String>,
    enabled: Boolean,
    onBrowse: (String) -> Unit,
    onSettled: (String) -> Unit,
    backdrop: MobileContinueBackdropState? = null,
) {
    val dragging by row.interactionSource.collectIsDraggedAsState()
    val browse by rememberUpdatedState(onBrowse)
    val settle by rememberUpdatedState(onSettled)
    LaunchedEffect(row, keys, enabled, backdrop) {
        if (!enabled) { backdrop?.clear(); return@LaunchedEffect }
        val allowed = keys.toHashSet()
        var browsing = false
        var lastKey: String? = null
        try { snapshotFlow {
            val layout = row.layoutInfo
            val cards = layout.visibleItemsInfo.mapNotNull { item ->
                val key = item.key as? String
                key?.takeIf { it in allowed }?.let { ContinueShelfCard(it, item.offset, item.size) }
            }
            val first = layout.visibleItemsInfo.firstOrNull { it.index == row.firstVisibleItemIndex }
            ContinueShelfSample(dragging, row.isScrollInProgress,
                continueShelfAnchorKey(cards, layout.viewportStartOffset, layout.viewportEndOffset),
                first?.let { continueBackdropMotion(keys, row.firstVisibleItemIndex,
                    row.firstVisibleItemScrollOffset, it.size, layout.mainAxisItemSpacing) })
        }.distinctUntilChanged().collectLatest { (draggingNow, scrolling, key, motion) ->
            if (draggingNow) browsing = true
            if (browsing) backdrop?.follow(motion)
            if (browsing && key != null && key != lastKey) {
                lastKey = key
                browse(key)
            }
            if (browsing && !draggingNow && !scrolling) {
                // Drag.Stop may be observed between the drag's scroll mutation ending and the
                // fling acquiring it. Keep ownership through that gap. Any new motion/layout
                // snapshot cancels this wait, including revocation through the effect's keys.
                delay(120)
                if (dragging || row.isScrollInProgress) return@collectLatest
                key?.let(settle)
                backdrop?.settle(key)
                browsing = false
                lastKey = null
            }
        } } finally { backdrop?.clear() }
    }
}
