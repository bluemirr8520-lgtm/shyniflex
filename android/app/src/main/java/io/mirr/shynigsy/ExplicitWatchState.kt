package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState

/** Reconcile a confirmed explicit change with rows already present in the current screen. */
internal fun applyExplicitWatchState(
    state: AppState,
    target: MediaItem,
    changed: List<MediaItem>,
    watched: Boolean,
    invalidatedAggregate: (MediaItem) -> Boolean = { false },
): AppState {
    fun matches(candidate: MediaItem, item: MediaItem): Boolean =
        candidate.id == item.id && candidate.libraryId == item.libraryId &&
            candidate.type == item.type && candidate.isSpoiler == item.isSpoiler &&
            (candidate.viewItemId == null || item.viewItemId == null || candidate.viewItemId == item.viewItemId)

    fun affected(candidate: MediaItem): Boolean =
        matches(candidate, target) || changed.any { matches(candidate, it) }

    fun updated(candidate: MediaItem): MediaItem = when {
        affected(candidate) -> candidate.copy(watched = watched, resumeMs = 0,
            watchState = if (watched) MediaWatchState.WATCHED else MediaWatchState.UNWATCHED)
        invalidatedAggregate(candidate) -> candidate.copy(watched = false, watchState = null)
        else -> candidate
    }

    return state.copy(
        items = state.items.map(::updated),
        searchItems = state.searchItems.map(::updated),
        recentItems = state.recentItems.map(::updated),
        continueItems = state.continueItems.filterNot(::affected),
        detailStack = state.detailStack.map { detail ->
            detail.copy(item = updated(detail.item), children = detail.children.map(::updated),
                seasons = detail.seasons.map(::updated))
        },
        detailDiscovery = state.detailDiscovery.mapValues { (_, detail) -> detail.copy(
            parent = updated(detail.parent), relatedItems = detail.relatedItems.map(::updated),
            actorItems = detail.actorItems.map(::updated)) },
    )
}
