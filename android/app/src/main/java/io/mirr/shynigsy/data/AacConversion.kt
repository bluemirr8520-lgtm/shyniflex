package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Only an explicitly selected E-AC-3 track needs this MP4 muxer compatibility path. */
internal fun isEac3Codec(value: String?): Boolean = value?.trim()?.lowercase(Locale.ROOT) in setOf(
    "eac3", "e-ac3", "e-ac-3", "ec-3", "ec+3", "ec3", "eac3-joc", "ddp", "dd+", "audio/eac3", "audio/eac3-joc",
)

/** Describes the requested H.264/AAC SDR conversion output, not original MPV decoding. */
internal fun aacConversionProfile(height: Int): JSONObject = JSONObject().apply {
    val width = when (height) { 480 -> 854; 720 -> 1280; 1080 -> 1920; else -> throw ApiException("변환 화질이 올바르지 않습니다.") }
    put("video_codecs", JSONArray().put(JSONObject().put("codec", "h264").put("max_width", width).put("max_height", height)))
    put("audio_codecs", JSONArray().put("aac")); put("max_audio_channels", 6)
    put("containers", JSONArray().put("mp4"))
    put("hdr", JSONObject().put("hdr10", false).put("hdr10_plus", false).put("dolby_vision", false).put("hlg", false))
}

internal fun conversionLanguage(value: String?): String? = when (val code = value?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')) {
    null, "", "und", "unknown" -> null
    "kor", "kr", "korean" -> "ko"
    "eng", "english" -> "en"
    "jpn", "japanese" -> "ja"
    "zho", "chi", "chinese" -> "zh"
    "deu", "ger" -> "de"
    "fra", "fre" -> "fr"
    else -> code
}
