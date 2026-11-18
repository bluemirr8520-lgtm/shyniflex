package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import java.net.URLEncoder

internal const val SUBTITLE_SEARCH_MAX_LENGTH = 200

/** Only visible search text is retained; server URLs, headers and file identifiers are never read. */
internal fun normalizeSubtitleWebSearchQuery(value: String): String {
    val cleaned = value.map { character ->
        if (character.isWhitespace() || Character.isSpaceChar(character) || Character.isISOControl(character) ||
            Character.getType(character) == Character.FORMAT.toInt()) ' ' else character
    }.joinToString("").replace(Regex(" +"), " ").trim().take(SUBTITLE_SEARCH_MAX_LENGTH)
    return cleaned.dropLastWhile { it.isHighSurrogate() }.trimEnd()
}

internal fun defaultSubtitleWebSearchQuery(spec: PlaybackSpec): String {
    val parts = mutableListOf(normalizeSubtitleWebSearchQuery(spec.controllerTitle))
    val item = spec.sourceItem
    fun existing() = parts.joinToString(" ")
    item?.year?.takeIf { it in 1000..9999 }?.let { year ->
        if (!Regex("(?<!\\d)$year(?!\\d)").containsMatchIn(existing())) parts += year.toString()
    }
    if (item?.type == "episode") {
        item.seasonNumber?.takeIf { it >= 0 }?.let { season ->
            val present = Regex("(?i)(?<![A-Za-z])S0*$season(?!\\d)|시즌\\s*0*$season(?!\\d)|(?<!\\d)0*$season\\s*시즌")
            if (!present.containsMatchIn(existing())) parts += "시즌 $season"
        }
        item.episodeNumber?.takeIf { it >= 0 }?.let { episode ->
            val present = Regex("(?i)(?<![A-Za-z])E0*$episode(?!\\d)|(?<!\\d)0*$episode\\s*[화회]")
            if (!present.containsMatchIn(existing())) parts += "${episode}화"
        }
    }
    if (!Regex("한국어\\s*자막").containsMatchIn(existing())) parts += "한국어 자막"
    return normalizeSubtitleWebSearchQuery(parts.joinToString(" "))
}

/** A fixed HTTPS destination and one encoded parameter prevent title text from becoming a URL. */
internal fun subtitleWebSearchUrl(value: String): String? {
    val query = normalizeSubtitleWebSearchQuery(value).takeIf { it.isNotEmpty() } ?: return null
    return "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}
