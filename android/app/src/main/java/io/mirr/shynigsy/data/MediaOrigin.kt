package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Read only work-origin metadata, never selected/dubbed audio or subtitle language. */
internal fun mediaProductionCountries(row: JSONObject): List<String> {
    val fields = listOf("productionCountries", "countries_display", "country", "countries", "origin_country",
        "origin_countries", "production_countries")
    for (source in listOfNotNull(row, originExtra(row))) {
        for (field in fields) {
            val countries = countryValues(source.opt(field)).mapNotNull(::normalizedProductionCountry).distinct().take(16)
            if (countries.isNotEmpty()) return countries
        }
    }
    return emptyList()
}

internal fun mediaOriginalLanguage(row: JSONObject): String? {
    for (source in listOfNotNull(row, originExtra(row))) {
        for (field in listOf("originalLanguage", "original_language")) {
            val value = (source.opt(field) as? String)?.trim()?.lowercase(Locale.ROOT) ?: continue
            if (value == "korean" || value == "한국어" || value == "조선말") return "ko"
            if (Regex("[a-z]{2,3}(?:[-_][a-z]{2,4})?").matches(value)) return value.replace('_', '-')
        }
    }
    return null
}

/** A parent's origin is applicable to its seasons/episodes, not unrelated list neighbors. */
internal fun MediaItem.inheritMediaOrigin(parent: MediaItem): MediaItem = copy(
    productionCountries = productionCountries.ifEmpty { parent.productionCountries },
    originalLanguage = originalLanguage ?: parent.originalLanguage,
)

internal fun normalizedProductionCountry(value: String): String? {
    val clean = value.trim().takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Character::isISOControl) } ?: return null
    val lower = clean.lowercase(Locale.ROOT)
    if (lower in setOf("unknown", "none", "null", "n/a", "미상", "국가 없음")) return null
    if (lower in KOREAN_COUNTRY_NAMES) return "KR"
    return if (Regex("[a-zA-Z]{2,3}").matches(clean)) clean.uppercase(Locale.ROOT) else clean
}

private val KOREAN_COUNTRY_NAMES = setOf("kr", "kor", "한국", "대한민국", "남한", "south korea",
    "republic of korea", "korea, republic of", "korea (south)", "korea, south")

private fun countryValues(value: Any?, depth: Int = 0): List<String> {
    if (depth > 2) return emptyList()
    return when (value) {
        is JSONArray -> (0 until minOf(value.length(), 16)).flatMap { countryValues(value.opt(it), depth + 1) }
        is JSONObject -> listOf("iso_3166_1", "code", "name", "tag").firstNotNullOfOrNull { key ->
            (value.opt(key) as? String)?.takeIf { it.isNotBlank() }
        }?.let(::listOf).orEmpty()
        is String -> {
            if (value.length > 2_048) emptyList()
            else if (value.trim().startsWith("[")) runCatching { countryValues(JSONArray(value), depth + 1) }.getOrDefault(emptyList())
            else if (value.trim().lowercase(Locale.ROOT) in KOREAN_COUNTRY_NAMES) listOf(value)
            else value.split(',', '|', ';').take(16)
        }
        else -> emptyList()
    }
}

private fun originExtra(row: JSONObject): JSONObject? = row.optJSONObject("extra")
    ?: (row.opt("extra") as? String)?.takeIf { it.length <= 512_000 }?.let { runCatching { JSONObject(it) }.getOrNull() }
