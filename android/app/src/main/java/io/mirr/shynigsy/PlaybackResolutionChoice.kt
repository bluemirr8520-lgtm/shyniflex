package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion

/** Only server-returned file parts are offered; selecting a resolution starts playback. */
data class PlaybackResolutionChoice(
    val item: MediaItem,
    val versions: List<MediaVersion>,
    val startOver: Boolean,
    val navigationQuery: String,
)

internal data class PlaybackSelectionSource(
    val libraryId: Int?,
    val browseRevision: Long,
    val detailKeys: List<String>,
    val query: String,
    val searchOpen: Boolean,
    val searchQuery: String,
    val selectedPartId: Int?,
)

/** A pending choice belongs to the exact browse/detail screen that requested playback. */
internal fun playbackSelectionSource(state: AppState) = PlaybackSelectionSource(
    state.library?.id, state.libraryBrowseRevision, state.detailStack.map { detailDiscoveryKey(it.item) },
    state.query, state.searchOpen, state.searchQuery, state.selectedPartId,
)
