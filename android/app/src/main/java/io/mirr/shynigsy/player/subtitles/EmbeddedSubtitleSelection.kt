package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import java.util.Locale

internal data class EmbeddedSubtitleOption(
    val supported: Boolean,
    val language: String?,
    val label: String?,
    val originalMimeType: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isSdh: Boolean = false,
)

internal fun subtitleLanguage(language: String?): String? = when (val value = language?.lowercase(Locale.ROOT)?.substringBefore('-')) {
    "kor", "ko" -> "ko"; "eng", "en" -> "en"; "jpn", "ja" -> "ja"
    null, "", "und" -> null; else -> value
}

internal val SubtitleTrack.isEmbedded: Boolean get() = source in setOf("embedded", "internal")

internal fun preferredSubtitle(spec: PlaybackSpec): SubtitleTrack? {
    spec.selectedSubtitleId?.let { id -> return spec.subtitles.firstOrNull { it.id == id } }
    return spec.subtitles.firstOrNull { subtitleLanguage(it.language) == "ko" }
        ?: spec.subtitles.firstOrNull { it.source == "external" } ?: spec.subtitles.firstOrNull()
}

/**
 * FFprobe stream_index is not a Media3 track ID. When same-language tracks are
 * otherwise indistinguishable, use the container's native preferences instead
 * of waiting for a full-file subtitle conversion.
 */
internal fun embeddedSubtitleIndex(track: SubtitleTrack, options: List<EmbeddedSubtitleOption>): Int? {
    if (!track.isEmbedded) return null
    val supported = options.indices.filter { options[it].supported }
    val language = subtitleLanguage(track.language)
    var candidates = if (language != null) {
        val matches = supported.filter { subtitleLanguage(options[it].language) == language }
        // Some containers omit their language tag. Only one total text track
        // can be selected safely without guessing across unsupported tracks.
        if (matches.isEmpty()) return supported.singleOrNull()
            ?.takeIf { options.size == 1 && subtitleLanguage(options[it].language) == null }
        matches
    } else supported
    if (candidates.isEmpty()) return null

    val title = track.title?.takeIf { it.isNotBlank() } ?: track.label
    val labels = candidates.filter {
        !options[it].label.isNullOrBlank() && options[it].label?.trim().equals(title.trim(), ignoreCase = true)
    }
    if (labels.isNotEmpty()) candidates = labels
    if (candidates.size == 1) return candidates.single()

    originalSubtitleMimeType(track.format)?.let { mime ->
        candidates.filter { options[it].originalMimeType.equals(mime, ignoreCase = true) }
            .takeIf { it.isNotEmpty() }?.let { candidates = it }
    }
    track.forced?.let { forced ->
        candidates.filter { options[it].isForced == forced }
            .takeIf { it.isNotEmpty() }?.let { candidates = it }
    }
    track.sdh?.let { sdh ->
        candidates.filter { options[it].isSdh == sdh }
            .takeIf { it.isNotEmpty() }?.let { candidates = it }
    }
    return candidates.firstOrNull { options[it].isDefault }
        ?: candidates.firstOrNull { !options[it].isForced && !options[it].isSdh }
        ?: candidates.firstOrNull { !options[it].isForced }
        ?: candidates.first()
}

private fun originalSubtitleMimeType(format: String?): String? = when (format?.lowercase(Locale.ROOT)) {
    "srt", "subrip" -> "application/x-subrip"
    "ass", "ssa" -> "text/x-ssa"
    "vtt", "webvtt" -> "text/vtt"
    "smi", "sami" -> "application/x-sami"
    "ttml", "dfxp" -> "application/ttml+xml"
    "pgs", "hdmv_pgs_subtitle" -> "application/pgs"
    "dvd_subtitle", "vobsub" -> "application/vobsub"
    "dvb_subtitle" -> "application/dvbsubs"
    "mov_text", "tx3g" -> "application/x-quicktime-tx3g"
    else -> null
}
