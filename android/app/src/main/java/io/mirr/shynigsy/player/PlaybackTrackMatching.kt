package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.SubtitleTrack
import java.util.Locale

/** A selection's metadata, independent of a container's track IDs or ordering. */
data class PlaybackTrackPreference(val language: String?, val label: String?)

/** Returns null when there is no evidence for replacing the new source's default. */
fun matchingPlaybackTrack(
    preference: PlaybackTrackPreference,
    candidates: List<PlaybackTrackPreference>,
): Int? {
    val language = normalizedLanguage(preference.language)
    val label = meaningfulLabel(preference.label)
    if (language != null) {
        val sameLanguage = candidates.indices.filter {
            normalizedLanguage(candidates[it].language) == language
        }
        return sameLanguage.firstOrNull { label != null && meaningfulLabel(candidates[it].label) == label }
            ?: sameLanguage.firstOrNull()
    }
    return label?.let { name -> candidates.indices.firstOrNull { meaningfulLabel(candidates[it].label) == name } }
}

/**
 * Server subtitle IDs and stream indices are local to a media part. Reuse the
 * language and descriptive metadata, then use the old ID only to break a tie
 * between equally suitable replacements. URLs never establish track identity.
 */
fun replacementSubtitle(previous: SubtitleTrack?, candidates: List<SubtitleTrack>): SubtitleTrack? {
    if (previous == null) return null
    val language = normalizedLanguage(previous.language)
    val names = subtitleNames(previous)
    val preferredName = meaningfulLabel(previous.title) ?: meaningfulLabel(previous.label)
    if (language == null && names.isEmpty()) return null

    // A known, different language must never match through a repeated ID/title.
    val compatible = candidates.filter {
        val candidateLanguage = normalizedLanguage(it.language)
        language == null || candidateLanguage == null || candidateLanguage == language
    }
    val sameLanguage = if (language == null) emptyList() else compatible.filter {
        normalizedLanguage(it.language) == language
    }
    val eligible = sameLanguage.ifEmpty {
        compatible.filter { names.intersect(subtitleNames(it)).isNotEmpty() }
    }

    var best: SubtitleTrack? = null
    var bestScore: List<Int>? = null
    for (candidate in eligible) {
        val candidateNames = subtitleNames(candidate)
        val labelMatches = names.intersect(candidateNames).isNotEmpty()
        val source = normalizedSource(previous.source)
        val candidateSource = normalizedSource(candidate.source)
        val originalFormat = normalizedSubtitleFormat(previous.format)
        val candidateFormat = normalizedSubtitleFormat(candidate.format)
        val kind = subtitleKind(previous)
        val candidateKind = subtitleKind(candidate)
        val idHasIdentity = (language != null && normalizedLanguage(candidate.language) == language || labelMatches) &&
            !conflicts(source, candidateSource) &&
            !conflicts(previous.forced, candidate.forced) &&
            !conflicts(previous.sdh, candidate.sdh) &&
            !conflicts(kind, candidateKind)
        val score = listOf(
            if (preferredName != null && preferredName in candidateNames) 1 else 0,
            if (labelMatches) 1 else 0,
            similarity(source, candidateSource),
            similarity(previous.forced, candidate.forced),
            similarity(previous.sdh, candidate.sdh),
            similarity(kind, candidateKind),
            similarity(originalFormat, candidateFormat),
            if (idHasIdentity && candidate.id == previous.id) 1 else 0,
        )
        if (bestScore == null || compareScores(score, bestScore) > 0) {
            best = candidate
            bestScore = score
        }
    }
    return best
}

private fun normalizedLanguage(value: String?): String? = when (
    val language = value?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
) {
    null, "", "und", "unk", "unknown", "auto", "mul", "zxx" -> null
    "ko", "kor" -> "ko"
    "en", "eng" -> "en"
    "ja", "jpn" -> "ja"
    else -> language
}

private val spaces = Regex("\\s+")
private val genericTrackLabel = Regex("(?:sub(?:title|titles)?|subtitle track|track|자막)(?:\\s*#?\\d+)?")

private fun meaningfulLabel(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
    ?.replace(spaces, " ")?.takeUnless {
        it.isEmpty() || it in setOf("unknown", "unknown language", "und", "default", "auto", "none") ||
            genericTrackLabel.matches(it)
    }

private fun subtitleNames(track: SubtitleTrack): Set<String> =
    listOfNotNull(meaningfulLabel(track.title), meaningfulLabel(track.label)).toSet()

private fun normalizedSource(value: String?): String? = when (val source = value?.trim()?.lowercase(Locale.ROOT)) {
    null, "" -> null
    "internal", "embedded" -> "embedded"
    "sidecar", "external" -> "external"
    else -> source
}

private fun normalizedSubtitleFormat(value: String?): String? = when (val format = value?.trim()?.lowercase(Locale.ROOT)) {
    null, "" -> null
    "subrip", "srt" -> "srt"
    "webvtt", "vtt" -> "vtt"
    "ass", "ssa" -> "ass"
    "hdmv_pgs_subtitle", "pgs" -> "pgs"
    "dvd_subtitle", "vobsub" -> "vobsub"
    "dvb_subtitle", "dvbsubs" -> "dvb"
    else -> format
}

private fun subtitleKind(track: SubtitleTrack): String? = when (normalizedSubtitleFormat(track.format)) {
    "pgs", "vobsub", "dvb", "xsub" -> "bitmap"
    "srt", "vtt", "ass", "smi", "sami", "ttml", "dfxp", "mov_text", "tx3g" -> "text"
    else -> when (track.mimeType.trim().lowercase(Locale.ROOT)) {
        "application/pgs", "application/vobsub", "application/dvbsubs", "application/x-xsub" -> "bitmap"
        "application/x-subrip", "application/ttml+xml", "application/x-sami", "application/x-quicktime-tx3g" -> "text"
        else -> if (track.mimeType.startsWith("text/", ignoreCase = true)) "text" else null
    }
}

private fun <T> conflicts(expected: T?, actual: T?): Boolean = expected != null && actual != null && expected != actual

private fun <T> similarity(expected: T?, actual: T?): Int = when {
    expected == null -> 0
    actual == expected -> 2
    actual == null -> 1
    else -> 0
}

private fun compareScores(left: List<Int>, right: List<Int>): Int {
    for (index in left.indices) {
        val comparison = left[index].compareTo(right[index])
        if (comparison != 0) return comparison
    }
    return 0
}
