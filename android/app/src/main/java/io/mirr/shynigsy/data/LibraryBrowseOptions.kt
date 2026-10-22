package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

enum class LibrarySort(val label: String) {
    TITLE_ASC("제목 오름차순"), TITLE_DESC("제목 내림차순"), RECENT("최근 추가순"), RECENT_ASC("오래된 추가순"),
    YEAR_DESC("제작 연도 최신순"), YEAR_ASC("제작 연도 오래된순"),
    DURATION_DESC("재생시간 긴순"), DURATION_ASC("재생시간 짧은순");

    val field: LibrarySortField get() = when (this) {
        TITLE_ASC, TITLE_DESC -> LibrarySortField.TITLE
        RECENT, RECENT_ASC -> LibrarySortField.ADDED
        YEAR_ASC, YEAR_DESC -> LibrarySortField.YEAR
        DURATION_ASC, DURATION_DESC -> LibrarySortField.DURATION
    }
    val isAscending: Boolean get() = this == field.ascending
    fun reversedDirection(): LibrarySort = if (isAscending) field.descending else field.ascending

    internal fun query(library: Library, contentType: LibraryContentType = LibraryContentType.defaultFor(library)): String = when (this) {
        TITLE_ASC -> "sort=title"
        TITLE_DESC -> if (contentType in setOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)) "sort=title" else "sort=title&sort_dir=desc"
        RECENT, RECENT_ASC -> if (contentType in setOf(LibraryContentType.SHOWS, LibraryContentType.EPISODES)) "sort=latest_episode&sort_dir=desc" else "sort=added&sort_dir=desc"
        else -> "sort=title"
    }
}

enum class LibrarySortField(val label: String, val ascending: LibrarySort, val descending: LibrarySort,
    val defaultSort: LibrarySort) {
    TITLE("제목", LibrarySort.TITLE_ASC, LibrarySort.TITLE_DESC, LibrarySort.TITLE_ASC),
    ADDED("추가일", LibrarySort.RECENT_ASC, LibrarySort.RECENT, LibrarySort.RECENT),
    YEAR("연도", LibrarySort.YEAR_ASC, LibrarySort.YEAR_DESC, LibrarySort.YEAR_DESC),
    DURATION("재생시간", LibrarySort.DURATION_ASC, LibrarySort.DURATION_DESC, LibrarySort.DURATION_ASC),
}

enum class LibraryContentType(val label: String, internal val route: String, internal val itemType: String) {
    MOVIES("영화", "movies", "movie"), SHOWS("TV 쇼", "shows", "show"),
    SEASONS("시즌", "seasons", "season"), EPISODES("에피소드", "episodes", "episode");

    companion object {
        fun defaultFor(library: Library): LibraryContentType = if (library.type == "tv") SHOWS else MOVIES
        fun availableFor(library: Library): List<LibraryContentType> =
            if (library.type == "tv") listOf(SHOWS, SEASONS, EPISODES) else listOf(MOVIES)
    }
}

enum class LibraryWatchFilter(val label: String) {
    ALL("전체"), UNWATCHED("보지 않음"), WATCHED("보았음"), IN_PROGRESS("시청 중")
}

/** Null means history was unavailable, so it cannot satisfy a watched-state filter. */
enum class MediaWatchState { UNWATCHED, IN_PROGRESS, WATCHED }

/** Only actual metadata values become filter choices; missing years stay unknown. */
internal fun mediaYear(row: JSONObject): Int? = sequenceOf("year", "production_year").mapNotNull { key ->
    row.opt(key)?.toString()?.toIntOrNull()
}.firstOrNull { it in 1..9999 } ?: sequenceOf("originally_available_at", "release_date", "first_air_date")
    .mapNotNull { row.optString(it).take(4).toIntOrNull() }.firstOrNull { it in 1..9999 }

/** A real offset-bearing ISO timestamp only; a year or missing zone is not an added date. */
internal fun mediaAddedAt(row: JSONObject): Long? = (row.opt("added_at") as? String)?.trim()?.let { raw ->
    runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
}

internal fun mediaGenres(row: JSONObject): List<String> {
    val value = row.opt("genres")?.takeUnless { it == JSONObject.NULL } ?: row.opt("genre")
    val values = when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        is String -> value.split(',', '|', ';')
        else -> emptyList()
    }
    return values.mapNotNull { value ->
        when (value) {
            is String -> value.trim()
            is JSONObject -> value.optString("name").ifBlank { value.optString("tag") }.trim()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }.distinct()
}

internal fun filteredCatalog(items: List<MediaItem>, genre: String?, year: Int?,
    watchFilter: LibraryWatchFilter = LibraryWatchFilter.ALL, decade: Int? = null): MediaPage {
    val filtered = items.filter { (genre == null || genre in it.genres) && (year == null || year == it.year) &&
        (decade == null || it.year?.let { value -> (value / 10) * 10 == decade } == true) &&
        when (watchFilter) {
            LibraryWatchFilter.ALL -> true
            LibraryWatchFilter.UNWATCHED -> it.watchState in setOf(MediaWatchState.UNWATCHED, MediaWatchState.IN_PROGRESS)
            LibraryWatchFilter.WATCHED -> it.watchState == MediaWatchState.WATCHED
            LibraryWatchFilter.IN_PROGRESS -> it.watchState == MediaWatchState.IN_PROGRESS
        }
    }
    return MediaPage(filtered, filtered.size, items.flatMap { it.genres }.distinct().sorted(),
        items.mapNotNull { it.year }.distinct().sortedDescending())
}

/** Keep unknown values at the end in either direction; do not synthesize years or runtimes. */
internal fun sortedCatalog(items: List<MediaItem>, sort: LibrarySort, contentType: LibraryContentType,
    search: Boolean = false): List<MediaItem> = when (sort) {
    LibrarySort.YEAR_DESC -> items.sortedWith(compareByDescending<MediaItem> { it.year != null }.thenByDescending { it.year })
    LibrarySort.YEAR_ASC -> items.sortedWith(compareByDescending<MediaItem> { it.year != null }.thenBy { it.year })
    LibrarySort.DURATION_DESC -> items.sortedWith(compareByDescending<MediaItem> { it.durationMs > 0 }.thenByDescending { it.durationMs })
    LibrarySort.DURATION_ASC -> items.sortedWith(compareByDescending<MediaItem> { it.durationMs > 0 }.thenBy { it.durationMs })
    LibrarySort.TITLE_DESC -> if (contentType in setOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)) items.asReversed()
        else if (search) items.sortedByDescending { it.title.lowercase(java.util.Locale.ROOT) } else items
    LibrarySort.TITLE_ASC -> if (search) items.sortedBy { it.title.lowercase(java.util.Locale.ROOT) } else items
    LibrarySort.RECENT -> if (search) items.sortedWith(compareByDescending<MediaItem> { it.addedAtMs != null }
        .thenByDescending { it.addedAtMs }) else items
    LibrarySort.RECENT_ASC -> if (search) items.sortedWith(compareByDescending<MediaItem> { it.addedAtMs != null }
        .thenBy { it.addedAtMs }) else items.asReversed()
}

internal fun aggregateWatchState(item: MediaItem, episodes: List<MediaItem>): MediaItem {
    if (episodes.isEmpty() || episodes.any { it.watchState == null }) return item.copy(watchState = null)
    val allWatched = episodes.all { it.watchState == MediaWatchState.WATCHED }
    val started = episodes.any { it.watchState != MediaWatchState.UNWATCHED }
    return item.copy(watched = allWatched, watchState = when {
        allWatched -> MediaWatchState.WATCHED
        started -> MediaWatchState.IN_PROGRESS
        else -> MediaWatchState.UNWATCHED
    })
}
