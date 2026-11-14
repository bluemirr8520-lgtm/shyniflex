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

/** A later full refresh must not replace shelves whose per-library retry has already completed. */
internal fun mergeRetriedHomeRecent(
    incoming: List<MediaItem>, current: List<MediaItem>, libraries: List<Library>,
    retriedViewIds: Set<Int>,
): List<MediaItem> = libraries.filter { it.id > 0 && it.type in setOf("movie", "tv") }
    .distinctBy { it.id }.flatMap { library ->
        // Selecting the current list is intentional even when it is empty: a
        // successful empty retry must not resurrect stale rows from the full load.
        val source = if (library.id in retriedViewIds) current else incoming
        source.filter { it.libraryId == library.id && it.isSpoiler == library.isSpoiler }
    }
