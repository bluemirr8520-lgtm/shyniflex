package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** `safeForExo` covers returned rows only; embedded identity requires a separate native track probe. */
internal data class PlaybackSubtitleInspection(val tracks: List<SubtitleTrack>, val safeForExo: Boolean)

/** Fail closed if a row was discarded or is not a supported and identifiable text track. */
internal fun areRawSubtitlesSafeForExo(value: Any, tracks: List<SubtitleTrack>): Boolean {
    val array = when (value) {
        is JSONArray -> value
        is JSONObject -> {
            val keys = listOf("subtitles", "items", "results").filter { value.has(it) }
            if (keys.size != 1) return false
            value.opt(keys.single()) as? JSONArray ?: return false
        }
        else -> return false
    }
    if (array.length() != tracks.size) return false
    val identifiers = mutableSetOf<Int>()
    var embeddedCount = 0
    for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: return false
        val source = (row.opt("source") as? String)?.trim()?.lowercase(Locale.ROOT)
        val format = (row.opt("format") as? String)?.trim()?.lowercase(Locale.ROOT)
        when (source) {
            "external" -> if (format !in setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "smi", "sami", "ttml", "dfxp")) return false
            "embedded", "internal" -> {
                if (format !in setOf("mov_text", "tx3g") || ++embeddedCount > 1) return false
                val stream = try { java.math.BigDecimal(row.opt("stream_index").toString()).intValueExact() }
                    catch (_: Exception) { return false }
                if (stream < 0 || tracks[index].streamIndex != stream) return false
            }
            else -> return false
        }
        if (row.has("playable") && !row.isNull("playable") && row.opt("playable") != true) return false
        val rawId = row.opt("id")
        val id = try { java.math.BigDecimal(rawId.toString()).intValueExact() } catch (_: Exception) { return false }
        if (id <= 0 || !identifiers.add(id) || tracks[index].id != id || tracks[index].source != source) return false
    }
    return true
}
