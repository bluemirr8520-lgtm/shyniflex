package io.mirr.shynigsy.player.subtitles

import java.util.Locale

/** Describe only supplied subtitle metadata; matching labels never establish track identity. */
internal fun subtitleMenuLabel(
    label: String?,
    language: String?,
    format: String?,
    source: String,
    forced: Boolean = false,
    sdh: Boolean = false,
): String {
    val title = label?.trim()?.takeIf { it.isNotEmpty() }
    val languageName = subtitleMenuLanguage(language)
    val parts = mutableListOf<String>()
    if (title == null) parts += languageName ?: "언어 미상"
    else if (languageName != null && subtitleMenuLanguage(title).equals(languageName, ignoreCase = true)) {
        parts += languageName
    } else {
        parts += title
        if (languageName != null) parts += languageName
    }
    parts += subtitleMenuFormat(format)
    source.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    if (forced) parts += "강제"
    if (sdh) parts += "청각 보조"
    return parts.distinctBy { it.lowercase(Locale.ROOT) }.joinToString(" · ")
}

private fun subtitleMenuLanguage(value: String?): String? {
    val normalized = value?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalized.substringBefore('-')) {
        "und", "unknown", "zxx" -> null
        "ko", "kor", "korean", "한국어" -> "한국어"
        "en", "eng", "english", "영어" -> "영어"
        "ja", "jpn", "japanese", "일본어" -> "일본어"
        "zh", "zho", "chi", "chinese", "중국어" -> "중국어"
        else -> normalized
    }
}

private fun subtitleMenuFormat(value: String?): String {
    val actual = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() } ?: return "형식 미상"
    return when (actual) {
        "srt", "subrip", "application/x-subrip" -> "SRT"
        "ass", "application/x-ass", "text/x-ass" -> "ASS"
        "ssa", "application/x-ssa", "text/x-ssa" -> "SSA"
        "vtt", "webvtt", "text/vtt", "application/x-mp4-vtt", "application/mp4vtt" -> "VTT"
        "pgs", "pgssub", "hdmv_pgs_subtitle", "application/pgs" -> "PGS"
        "vobsub", "dvdsub", "dvd_subtitle", "application/vobsub" -> "VobSub"
        "dvb", "dvbsub", "dvb_sub", "dvb_subtitle", "application/dvbsubs" -> "DVB"
        "xsub", "application/x-xsub" -> "XSUB"
        "smi", "sami", "application/x-sami", "text/sami" -> "SMI"
        "ttml", "dfxp", "application/ttml+xml" -> "TTML"
        "mov_text", "tx3g", "application/x-quicktime-tx3g" -> "MOV_TEXT"
        "text", "plain", "text/plain" -> "TEXT"
        "eia_608", "cea608", "cea-608", "application/cea-608" -> "CEA-608"
        "cea708", "cea-708", "application/cea-708" -> "CEA-708"
        "dvb_teletext", "teletext" -> "TELETEXT"
        "unknown", "und" -> "형식 미상"
        else -> actual.uppercase(Locale.ROOT)
    }
}
