package io.mirr.shynigsy.data

/** Search is bounded by the existing 200-result response of each authorized Library View. */
data class LibrarySearchResult(val items: List<MediaItem>, val failedLibraryCount: Int = 0)
