package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem

/** Continue rows must belong to an explicitly known public or unlocked private View. */
internal fun visibleContinueWatching(
    publicItems: List<MediaItem>,
    privateItems: List<MediaItem>,
    libraries: List<Library>,
    spoilerUnlocked: Boolean,
): List<MediaItem> {
    val privateViews = libraries.filter { it.isSpoiler }.map { it.id }.toSet()
    val publicViews = libraries.filterNot { it.isSpoiler }.map { it.id }.toSet() - privateViews
    val ordinary = publicItems.filter { !it.isSpoiler && it.libraryId in publicViews }
    val privateRows = if (spoilerUnlocked) privateItems.filter { it.libraryId in privateViews }
        .map { it.copy(isSpoiler = true) } else emptyList()
    return (ordinary + privateRows).distinctBy { listOf(it.libraryId, it.type, it.id, it.viewItemId) }
}
