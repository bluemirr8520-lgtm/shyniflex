package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.hasKnownMediaOrigin
import io.mirr.shynigsy.data.normalizedProductionCountry
import java.util.Locale

/** Current audio is only a caption fallback for missing origin metadata; it never changes the work's origin. */
internal fun defaultSubtitlesEnabled(spec: PlaybackSpec, selectedAudioLanguage: String? = null): Boolean {
    val item = spec.sourceItem ?: return true
    if (item.type !in setOf("movie", "episode")) return true
    val countries = item.productionCountries.mapNotNull(::normalizedProductionCountry)
    if (countries.isNotEmpty()) return "KR" !in countries
    val language = item.originalLanguage?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
    val originalLanguage = language?.substringBefore('-')
    if (originalLanguage in setOf("ko", "kor", "korean", "한국어")) return false
    if (originalLanguage !in setOf("und", "unk", "zxx", "mul") && item.hasKnownMediaOrigin()) return true
    // The caller must supply the current selected audio language explicitly. A remembered
    // server selection, a Korean title, or the subtitle language cannot activate this fallback.
    val selectedAudio = selectedAudioLanguage?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
    return selectedAudio == null || !Regex("(?:ko|kor)(?:-[a-z]{2,4})?").matches(selectedAudio)
}
