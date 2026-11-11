package io.mirr.shynigsy.data

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Prefer verifiable title/code matches; keep server alias/typo results if none match directly. */
internal fun rankSearchRows(rows: List<JSONObject>, query: String): List<JSONObject> {
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    fun initials(value: String): String = buildString {
        value.forEach { character ->
            if (character in '\uAC00'..'\uD7A3') append((0x1100 + (character.code - 0xAC00) / 588).toChar())
            else append(character)
        }
    }
    val needle = normalized(query)
    if (needle.isEmpty()) return rows
    val terms = Normalizer.normalize(query, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+")) .map(::normalized).filter { it.isNotEmpty() }
    val isInitials = needle.all { it in '\u1100'..'\u1112' }
    fun rank(row: JSONObject): Int {
        val fields = listOf("title", "original_title", "sort_title", "metadata_code")
            .mapNotNull { key -> if (row.isNull(key)) null else row.optString(key).takeIf { it.isNotBlank() } }
            .map(::normalized).filter { it.isNotEmpty() }
        return when {
            fields.any { it == needle } -> 0
            fields.any { it.startsWith(needle) } -> 1
            fields.any { it.contains(needle) } -> 2
            isInitials && fields.any { initials(it).contains(needle) } -> 3
            terms.size > 1 && fields.any { field -> terms.all { field.contains(it) } } -> 3
            else -> 4
        }
    }
    val ranked = rows.withIndex().map { Triple(it.index, it.value, rank(it.value)) }
    val direct = ranked.filter { it.third < 4 }
    return (if (direct.isEmpty()) ranked else direct)
        .sortedWith(compareBy<Triple<Int, JSONObject, Int>> { it.third }.thenBy { it.first }).map { it.second }
}
