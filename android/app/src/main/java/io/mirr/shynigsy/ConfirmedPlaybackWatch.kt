package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState
import io.mirr.shynigsy.player.PlaybackCompletionSignals.ConfirmedWatch

/** A short-lived acknowledgement is not valid across accounts, grants or manual choices. */
internal fun isCurrentPlaybackConfirmation(
    event: ConfirmedWatch, scope: String?, choiceCurrent: Boolean, grantRevision: Long,
    spoilerUnlocked: Boolean, nowNanos: Long = System.nanoTime(),
): Boolean {
    val age = nowNanos - event.confirmedNanos
    return scope != null && event.scope == scope && choiceCurrent &&
        age in 0..60_000_000_000L &&
        (!event.item.isSpoiler || (spoilerUnlocked && event.grantRevision == grantRevision))
}

/** Reconcile all still-valid acknowledgements, not only the last completed episode. */
internal fun applyConfirmedPlaybackWatches(
    state: AppState, events: Collection<ConfirmedWatch>, current: (ConfirmedWatch) -> Boolean,
): AppState = events.fold(state) { previous, event ->
    if (current(event)) applyConfirmedPlaybackWatch(previous, event.item) else previous
}

/** Do not infer a match when either View identity is absent or belongs to another View. */
internal fun applyConfirmedPlaybackWatch(state: AppState, target: MediaItem): AppState {
    fun matches(candidate: MediaItem): Boolean = target.viewItemId != null &&
        candidate.libraryId == target.libraryId &&
        candidate.viewItemId == target.viewItemId && candidate.type == target.type &&
        candidate.isSpoiler == target.isSpoiler && (candidate.id == target.id ||
            (target.type == "episode" && target.showId?.let { it > 0 } == true && candidate.showId == target.showId &&
                target.seasonNumber?.let { it >= 0 } == true && candidate.seasonNumber == target.seasonNumber &&
                target.episodeNumber?.let { it >= 0 } == true && candidate.episodeNumber == target.episodeNumber))
    fun updated(candidate: MediaItem): MediaItem = if (matches(candidate)) candidate.copy(
        watched = true, resumeMs = 0, watchState = MediaWatchState.WATCHED) else candidate
    return state.copy(items = state.items.map(::updated), searchItems = state.searchItems.map(::updated),
        recentItems = state.recentItems.map(::updated), continueItems = state.continueItems.filterNot(::matches),
        detailStack = state.detailStack.map { detail -> detail.copy(item = updated(detail.item),
            children = detail.children.map(::updated), seasons = detail.seasons.map(::updated)) },
        detailDiscovery = state.detailDiscovery.mapValues { (_, detail) -> detail.copy(
            parent = updated(detail.parent), relatedItems = detail.relatedItems.map(::updated),
            actorItems = detail.actorItems.map(::updated)) })
}
