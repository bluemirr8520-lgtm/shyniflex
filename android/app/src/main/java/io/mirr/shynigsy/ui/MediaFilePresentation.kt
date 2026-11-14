package io.mirr.shynigsy.ui

import io.mirr.shynigsy.data.MediaFileAudio
import io.mirr.shynigsy.data.MediaFileInfo
import io.mirr.shynigsy.data.MediaFileSubtitle
import java.util.Locale

internal const val UnknownFileInformation = "확인되지 않음"

private fun readable(value: String?, limit: Int = 120): String? = value?.filterNot(Char::isISOControl)
    ?.trim()?.takeIf(String::isNotEmpty)?.take(limit)

/** The information surface never needs a server directory, URL or temporary ticket. */
internal fun mediaFileHeading(file: MediaFileInfo, index: Int): String {
    val name = readable(file.fileName, 400)?.substringBefore('?')?.substringBefore('#')
        ?.replace('\\', '/')?.substringAfterLast('/')?.trim()?.takeIf(String::isNotEmpty)?.take(160)
    return name ?: "파일 ${index + 1}"
}

internal fun mediaCodecLabel(value: String?): String? = readable(value, 60)?.let {
    when (it.lowercase(Locale.ROOT)) {
        "h264", "h.264", "avc", "avc1" -> "H.264"
        "h265", "h.265", "hevc", "hvc1", "hev1" -> "HEVC (H.265)"
        "eac3", "e-ac-3", "ec-3" -> "E-AC-3"
        "ac3", "ac-3" -> "AC-3"
        "truehd" -> "TrueHD"
        "dts-hd", "dts_hd", "dts-hd ma" -> "DTS-HD"
        "subrip", "srt" -> "SRT"
        "webvtt", "vtt" -> "WebVTT"
        "hdmv_pgs_subtitle", "pgs" -> "PGS"
        "dvd_subtitle", "vobsub" -> "VobSub"
        else -> it.uppercase(Locale.ROOT)
    }
}

private fun labels(vararg values: String?): String = values.mapNotNull { readable(it, 180) }
    .distinct().joinToString(" · ").ifEmpty { UnknownFileInformation }

internal fun mediaFileVideoLabel(file: MediaFileInfo): String = labels(
    mediaCodecLabel(file.videoCodec), readable(file.videoProfile),
    file.videoBitDepth?.takeIf { it > 0 }?.let { "${it}비트" }, readable(file.hdrFormat),
)

internal fun mediaFileResolutionLabel(file: MediaFileInfo): String {
    val resolution = readable(file.resolution)?.replace(Regex("(?<=\\d)\\s*[xX×]\\s*(?=\\d)"), " × ")
    val rate = file.frameRate?.takeIf { it.isFinite() && it > 0 }?.let {
        String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') + " fps"
    }
    return labels(resolution, rate)
}

internal fun mediaFileFormatLabel(file: MediaFileInfo): String {
    val size = file.fileSizeBytes?.takeIf { it > 0 }?.let {
        when {
            it >= 1_073_741_824L -> String.format(Locale.US, "%.2f GiB", it / 1_073_741_824.0)
            it >= 1_048_576L -> String.format(Locale.US, "%.1f MiB", it / 1_048_576.0)
            it >= 1024L -> String.format(Locale.US, "%.1f KiB", it / 1024.0)
            else -> "$it B"
        }
    }
    val bitrate = file.bitrateBps?.takeIf { it > 0 }?.let {
        when {
            it >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", it / 1_000_000.0)
            it >= 1000 -> String.format(Locale.US, "%.0f kbps", it / 1000.0)
            else -> "$it bps"
        }
    }
    return labels(readable(file.container)?.uppercase(Locale.ROOT), size, bitrate)
}

private fun languageLabel(language: String?): String? = readable(language, 50)?.let {
    when (it.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
        "ko", "kor", "korean", "한국어" -> "한국어"
        "en", "eng", "english", "영어" -> "영어"
        "ja", "jpn", "japanese", "일본어" -> "일본어"
        "zh", "chi", "zho", "chinese", "중국어" -> "중국어"
        "fr", "fra", "fre" -> "프랑스어"
        "de", "deu", "ger" -> "독일어"
        "es", "spa" -> "스페인어"
        "it", "ita" -> "이탈리아어"
        "pt", "por" -> "포르투갈어"
        "ru", "rus" -> "러시아어"
        "vi", "vie" -> "베트남어"
        "th", "tha" -> "태국어"
        "und", "unknown" -> null
        else -> it
    }
}

internal fun mediaFileAudioLabel(track: MediaFileAudio): String = labels(
    languageLabel(track.language), mediaCodecLabel(track.codec), readable(track.profile),
    track.channels?.takeIf { it > 0 }?.let { "${it}채널" }, readable(track.title),
    "기본".takeIf { track.isDefault == true },
)

internal fun mediaFileAudioFallback(file: MediaFileInfo): String = labels(
    mediaCodecLabel(file.audioCodec), file.audioChannels?.takeIf { it > 0 }?.let { "${it}채널" },
).let { if (it == UnknownFileInformation) it else "$it · 트랙 정보 미확인" }

internal fun mediaFileSubtitleLabel(track: MediaFileSubtitle): String = labels(
    languageLabel(track.language), mediaCodecLabel(track.format),
    when (track.source?.lowercase(Locale.ROOT)) {
        "external", "sidecar" -> "외부"
        "embedded", "internal" -> "내장"
        else -> null
    }, readable(track.title), "강제".takeIf { track.forced == true }, "청각장애인용".takeIf { track.sdh == true },
)
