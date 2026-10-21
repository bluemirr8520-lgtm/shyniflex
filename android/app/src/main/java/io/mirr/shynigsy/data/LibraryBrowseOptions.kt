package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject

enum class LibrarySort(val label: String) {
    TITLE_ASC("제목 오름차순"), TITLE_DESC("제목 내림차순"), RECENT("최근 추가순");

    internal fun query(library: Library): String = when (this) {
        TITLE_ASC -> "sort=title"
        TITLE_DESC -> "sort=title&sort_dir=desc"
        RECENT -> if (library.type == "tv") "sort=latest_episode&sort_dir=desc" else "sort=added&sort_dir=desc"
    }
}

/** Only actual metadata values become filter choices; missing years stay unknown. */
internal fun mediaYear(row: JSONObject): Int? = sequenceOf("year", "production_year").mapNotNull { key ->
    row.opt(key)?.toString()?.toIntOrNull()
}.firstOrNull { it in 1..9999 } ?: sequenceOf("originally_available_at", "release_date", "first_air_date")
    .mapNotNull { row.optString(it).take(4).toIntOrNull() }.firstOrNull { it in 1..9999 }

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

internal fun filteredCatalog(items: List<MediaItem>, genre: String?, year: Int?): MediaPage {
    val filtered = items.filter { (genre == null || genre in it.genres) && (year == null || year == it.year) }
    return MediaPage(filtered, filtered.size, items.flatMap { it.genres }.distinct().sorted(),
        items.mapNotNull { it.year }.distinct().sortedDescending())
}
