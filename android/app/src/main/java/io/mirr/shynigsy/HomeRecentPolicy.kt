package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem

/** Retain only explicitly eligible Views; callers must validate account and private grant. */
internal fun mergeHomeRecent(
    items: List<MediaItem>, previous: List<MediaItem>, libraries: List<Library>,
    retainViewIds: Set<Int>, previousOwnerMatches: Boolean,
): List<MediaItem> = libraries.distinctBy { it.id }.flatMap { library ->
    fun matching(rows: List<MediaItem>) = rows.filter {
        it.libraryId == library.id && it.isSpoiler == library.isSpoiler
    }
    val current = matching(items)
    if (current.isNotEmpty() || !previousOwnerMatches || library.id !in retainViewIds) current
    else matching(previous)
}
