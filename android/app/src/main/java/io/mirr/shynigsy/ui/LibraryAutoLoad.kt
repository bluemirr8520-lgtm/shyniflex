package io.mirr.shynigsy.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Inputs and measured layout belong to one snapshot, including hidden private rows. */
internal data class LibraryLoadSnapshot(
    val loadedCount: Int,
    val displayedCount: Int,
    val total: Int,
    val busy: Boolean,
    val hasError: Boolean,
    val layoutTotalItemsCount: Int,
    val lastVisibleIndex: Int,
)

/** One automatic attempt per offset; only a new dataset creates another gate. */
internal class LibraryAutoLoadGate {
    private val requestedOffsets = mutableSetOf<Int>()

    fun claim(snapshot: LibraryLoadSnapshot): Boolean {
        if (snapshot.loadedCount <= 0 || snapshot.loadedCount >= snapshot.total ||
            snapshot.displayedCount < 0 || snapshot.busy || snapshot.hasError || snapshot.lastVisibleIndex < 0) return false
        // Both library grids have two leading headers. A previous page's layout
        // must not immediately request another page before new cards are measured.
        val measuredItemCount = snapshot.displayedCount.toLong() + 2
        if (snapshot.layoutTotalItemsCount.toLong() < measuredItemCount ||
            snapshot.lastVisibleIndex.toLong() < measuredItemCount - 1 - 6) return false
        // Mark before invoking the callback, even if it synchronously changes state.
        return requestedOffsets.add(snapshot.loadedCount)
    }
}

@Composable
internal fun LibraryAutoLoad(
    gridState: LazyGridState,
    datasetKey: Any,
    loadedCount: Int,
    displayedCount: Int,
    total: Int,
    busy: Boolean,
    hasError: Boolean,
    onLoadMore: () -> Unit,
) {
    val gate = remember(datasetKey) { LibraryAutoLoadGate() }
    val currentInputs by rememberUpdatedState(LibraryLoadSnapshot(loadedCount, displayedCount, total,
        busy, hasError, layoutTotalItemsCount = 0, lastVisibleIndex = -1))
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, gate) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            currentInputs.copy(layoutTotalItemsCount = layout.totalItemsCount,
                lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.distinctUntilChanged().collect { snapshot ->
            if (gate.claim(snapshot)) currentLoadMore()
        }
    }
}
