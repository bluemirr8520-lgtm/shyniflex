package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.normalizedProductionCountry
import java.util.Locale

/** Unknown origins keep the existing behavior; translated names and dubbed audio are not evidence. */
internal fun defaultSubtitlesEnabled(spec: PlaybackSpec): Boolean {
    val item = spec.sourceItem ?: return true
    if (item.type !in setOf("movie", "episode")) return true
    val countries = item.productionCountries.mapNotNull(::normalizedProductionCountry)
    if (countries.isNotEmpty()) return "KR" !in countries
    val language = item.originalLanguage?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
    return language?.substringBefore('-') !in setOf("ko", "kor", "korean", "한국어")
}
