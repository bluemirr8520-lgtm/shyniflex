package io.mirr.shynigsy

import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem

/** Supplemental state belongs to an in-memory detail frame, never to a saved account record. */
data class DetailDiscoveryState(
    val parent: MediaItem,
    val relatedItems: List<MediaItem> = emptyList(),
    val relatedLoading: Boolean = false,
    val relatedLoaded: Boolean = false,
    val relatedError: String? = null,
    val selectedActor: CastMember? = null,
    val actorItems: List<MediaItem> = emptyList(),
    val actorLoading: Boolean = false,
    val actorLoaded: Boolean = false,
    val actorError: String? = null,
    val currentLibraryOnly: Boolean = false,
)

internal fun detailDiscoveryKey(item: MediaItem): String =
    "${item.libraryId}:${item.type}:${item.id}:${item.viewItemId}:${item.isSpoiler}"

internal fun canDiscoverActorWorks(actor: CastMember, parent: MediaItem, libraries: List<Library>): Boolean =
    (actor.tmdbId ?: 0) > 0 || (actor.canLoadWorks && libraries.any {
        it.id == parent.libraryId && it.type == "tv" && it.isSpoiler == parent.isSpoiler
    })

/** A library returned by the server is a View; a private duplicate always wins. */
internal fun visibleDetailWorks(items: List<MediaItem>, libraries: List<Library>, unlocked: Boolean): List<MediaItem> =
    visibleContinueWatching(items.filterNot { it.isSpoiler }, items.filter { it.isSpoiler }, libraries, unlocked)

/** Unknown credit metadata must not erase a known watch state for this exact current identity. */
internal fun retainKnownDetailWatchState(items: List<MediaItem>, state: AppState): List<MediaItem> {
    val known = (state.detailDiscovery.values.flatMap { it.relatedItems + it.actorItems } +
        state.items + state.searchItems + state.recentItems + state.continueItems +
        state.detailStack.flatMap { listOf(it.item) + it.children + it.seasons })
        .filter { it.watchState != null || it.watched || it.resumeMs > 0 }.associateBy(::detailDiscoveryKey)
    return items.map { item ->
        val previous = known[detailDiscoveryKey(item)]
        if (item.watchState != null || item.watched || item.resumeMs > 0 || previous == null) item else item.copy(
            watched = previous.watched, resumeMs = previous.resumeMs, watchState = previous.watchState,
            durationMs = item.durationMs.takeIf { it > 0 } ?: previous.durationMs)
    }
}

internal fun DetailDiscoveryState.withoutPrivateWorks() = copy(
    relatedItems = relatedItems.filterNot { it.isSpoiler },
    actorItems = actorItems.filterNot { it.isSpoiler },
    relatedLoading = false, actorLoading = false, relatedError = null, actorError = null,
    relatedLoaded = relatedLoaded && !parent.isSpoiler && relatedItems.none { it.isSpoiler },
    actorLoaded = actorLoaded && !parent.isSpoiler && actorItems.none { it.isSpoiler },
)
