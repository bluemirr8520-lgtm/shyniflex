package io.mirr.shynigsy

import io.mirr.shynigsy.data.HomeSnapshot
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem

/** Cached artwork is only presentation data; it cannot authorize a private View. */
internal fun restoreHomeSnapshot(state: AppState, snapshot: HomeSnapshot): AppState =
    reconcileHomeLibraryAccess(state.copy(recentItems = snapshot.recentItems,
        continueItems = snapshot.continueItems, continueDetails = null,
        recentLibraryStatus = snapshot.recentLibraryStatus, busy = false, homeLoading = true),
        snapshot.libraries.filterNot { it.isSpoiler })

/** Apply a fresh catalog before slower history or recent-item requests can finish. */
internal fun reconcileHomeLibraryAccess(state: AppState, libraries: List<Library>): AppState {
    fun visible(items: List<MediaItem>): List<MediaItem> = visibleContinueWatching(
        items.filterNot { it.isSpoiler }, items.filter { it.isSpoiler }, libraries, state.spoilerUnlocked).filter { item ->
        libraries.any { library -> library.id == item.libraryId &&
            (if (library.type == "movie") item.type == "movie" else library.type == "tv" &&
                item.type in setOf("show", "tv", "season", "episode")) }
    }
    val continuing = visible(state.continueItems)
    val detail = state.continueDetails?.takeIf { selected -> continuing.any {
        it.id == selected.id && it.libraryId == selected.libraryId && it.type == selected.type &&
            it.viewItemId == selected.viewItemId && it.isSpoiler == selected.isSpoiler
    } }
    return state.copy(libraries = libraries, recentItems = visible(state.recentItems),
        continueItems = continuing, continueDetails = detail,
        recentLibraryStatus = state.recentLibraryStatus.filterKeys { id -> libraries.any { it.id == id } })
}

/** Private names and items are excluded even if a stale public alias has the same View ID. */
internal fun homeSnapshotForPersistence(state: AppState, knownPrivateIds: Set<Int>, nowMs: Long): HomeSnapshot {
    val denied = knownPrivateIds + state.libraries.filter { it.isSpoiler }.map { it.id }
    val libraries = state.libraries.filter { !it.isSpoiler && it.id !in denied }
    val safe = reconcileHomeLibraryAccess(state.copy(spoilerUnlocked = false), libraries)
    return HomeSnapshot(nowMs, libraries, safe.recentItems, safe.continueItems, safe.recentLibraryStatus)
}

/** A denied View cannot retain a cached continue poster or hero while its siblings finish. */
internal fun withoutDeniedHomeRows(state: AppState, ids: Set<Int>): AppState = state.copy(
    recentItems = state.recentItems.filterNot { it.libraryId in ids },
    continueItems = state.continueItems.filterNot { it.libraryId in ids },
    continueDetails = state.continueDetails?.takeUnless { it.libraryId in ids },
)
