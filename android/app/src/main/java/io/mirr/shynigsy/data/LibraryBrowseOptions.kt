package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

enum class LibrarySort(val label: String) {
    TITLE_ASC("제목 오름차순"), TITLE_DESC("제목 내림차순"), RECENT("추가된 날짜"),
    YEAR_DESC("제작 연도 최신순"), YEAR_ASC("제작 연도 오래된순"),
    RELEASED_DESC("출시일 최신순"), RELEASED_ASC("출시일 오래된순"),
    RATING("평점"), LATEST_EPISODE("최근 에피소드");

    val field: LibrarySortField get() = when (this) {
        TITLE_ASC, TITLE_DESC -> LibrarySortField.TITLE
        RECENT -> LibrarySortField.ADDED
        YEAR_ASC, YEAR_DESC -> LibrarySortField.YEAR
        RELEASED_ASC, RELEASED_DESC -> LibrarySortField.RELEASED_AT
        RATING -> LibrarySortField.RATING
        LATEST_EPISODE -> LibrarySortField.LATEST_EPISODE
    }
    val isAscending: Boolean get() = this.field.isReversible && this == this.field.ascending
    fun reversedDirection(): LibrarySort = if (!field.isReversible) this else if (isAscending) field.descending else field.ascending

    fun forContentType(contentType: LibraryContentType): LibrarySort =
        takeIf { field in LibrarySortField.availableFor(contentType) } ?: defaultFor(contentType)

    /** Match the server web player: nested catalogs have no sort_dir parameter. */
    internal fun query(library: Library, contentType: LibraryContentType = LibraryContentType.defaultFor(library)): String {
        val selected = forContentType(contentType)
        val query = "sort=${selected.field.serverValue}"
        return if (contentType in setOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)) query
            else "$query&sort_dir=${if (selected.isAscending) "asc" else "desc"}"
    }

    companion object {
        fun defaultFor(contentType: LibraryContentType): LibrarySort =
            if (contentType == LibraryContentType.EPISODES) LATEST_EPISODE else TITLE_ASC
    }
}

enum class LibrarySortField(val label: String, val ascending: LibrarySort, val descending: LibrarySort,
    val defaultSort: LibrarySort, internal val serverValue: String) {
    TITLE("제목", LibrarySort.TITLE_ASC, LibrarySort.TITLE_DESC, LibrarySort.TITLE_ASC, "title"),
    ADDED("추가된 날짜", LibrarySort.RECENT, LibrarySort.RECENT, LibrarySort.RECENT, "added"),
    YEAR("연도", LibrarySort.YEAR_ASC, LibrarySort.YEAR_DESC, LibrarySort.YEAR_DESC, "year"),
    RELEASED_AT("출시일", LibrarySort.RELEASED_ASC, LibrarySort.RELEASED_DESC, LibrarySort.RELEASED_DESC, "released_at"),
    RATING("평점", LibrarySort.RATING, LibrarySort.RATING, LibrarySort.RATING, "rating"),
    LATEST_EPISODE("최근 에피소드", LibrarySort.LATEST_EPISODE, LibrarySort.LATEST_EPISODE, LibrarySort.LATEST_EPISODE, "latest_episode");

    val isReversible: Boolean get() = ascending != descending

    companion object {
        fun availableFor(contentType: LibraryContentType): List<LibrarySortField> = when (contentType) {
            LibraryContentType.MOVIES -> listOf(TITLE, ADDED, YEAR, RELEASED_AT, RATING)
            LibraryContentType.SHOWS -> listOf(TITLE, ADDED, LATEST_EPISODE)
            LibraryContentType.SEASONS -> listOf(TITLE, ADDED)
            LibraryContentType.EPISODES -> listOf(LATEST_EPISODE, TITLE, ADDED)
        }
    }
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

/** Keep the server's complete catalog order, including ties and missing metadata. */
internal fun sortedCatalog(items: List<MediaItem>, sort: LibrarySort, contentType: LibraryContentType,
    search: Boolean = false): List<MediaItem> =
    if (search && contentType !in setOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)) items
    else if (sort.forContentType(contentType) == LibrarySort.TITLE_DESC &&
        contentType in setOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)) items.asReversed()
    else items // The separate /search API defines its own relevance order, without media sort parameters.

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
