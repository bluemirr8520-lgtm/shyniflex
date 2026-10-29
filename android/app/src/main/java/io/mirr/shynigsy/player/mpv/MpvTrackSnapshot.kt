package io.mirr.shynigsy.player.mpv

import org.json.JSONArray
import org.json.JSONObject

/** Only fields needed by the controls; signed external filenames are never retained. */
internal data class MpvNativeTrack(
    val id: Int,
    val type: String,
    val codec: String,
    val language: String?,
    val title: String?,
    val selected: Boolean,
    val default: Boolean,
    val forced: Boolean,
    val hearingImpaired: Boolean,
    val streamIndex: Int?,
    val audioChannels: Int?,
    val sampleRate: Int?,
    val width: Int?,
    val height: Int?,
)

internal data class MpvExoTrackEvidence(
    val videoProfile: String?, val audioProfile: String?, val width: Int?, val height: Int?,
    val frameRate: Double?, val audioChannels: Int?, val subtitleStreamIndex: Int? = null,
)

internal data class MpvExoProbe(
    val tracks: MpvExoTrackEvidence, val pixelFormat: String?, val hardwarePixelFormat: String?,
    val primaries: String?, val gamma: String?, val decodedWidth: Int?, val decodedHeight: Int?,
    val frameRate: Double?,
)

internal object MpvTrackSnapshot {
    /** Exo may ignore unsupported MP4 subtitle tracks; independently inspect the full mpv list. */
    fun exoEvidence(value: String?): MpvExoTrackEvidence? {
        if (value == null || value.length > 1_048_576) return null
        return try {
            val rows = JSONArray(value)
            if (rows.length() !in 2..3) return null
            var video: JSONObject? = null
            var audio: JSONObject? = null
            var subtitle: JSONObject? = null
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: return null
                if (row.integer("id")?.let { it > 0 } != true || row.opt("external") == true) return null
                val type = row.opt("type") as? String ?: return null
                val codec = (row.opt("codec") as? String)?.lowercase(java.util.Locale.ROOT) ?: return null
                when {
                    type == "video" && codec in setOf("h264", "avc") && video == null && row.opt("selected") == true -> video = row
                    type == "audio" && codec == "aac" && audio == null && row.opt("selected") == true -> audio = row
                    type == "sub" && codec in setOf("mov_text", "tx3g") && subtitle == null &&
                        row.integer("ff-index")?.let { it >= 0 } == true -> subtitle = row
                    else -> return null
                }
            }
            val v = video ?: return null
            val a = audio ?: return null
            val fps = (v.opt("demux-fps") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }
            MpvExoTrackEvidence(v.opt("codec-profile") as? String, a.opt("codec-profile") as? String,
                v.integer("demux-w"), v.integer("demux-h"), fps,
                a.integer("audio-channels") ?: a.integer("demux-channel-count"), subtitle?.integer("ff-index"))
        } catch (_: Exception) { null }
    }

    /** MPV exposes this node array as a JSON STRING in the pinned JNI wrapper. */
    fun parse(value: String): List<MpvNativeTrack>? {
        if (value.length > 1_048_576) return null
        return try {
            val rows = JSONArray(value)
            (0 until minOf(rows.length(), 256)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.integer("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val type = row.opt("type") as? String ?: return@mapNotNull null
                if (type != "video" && type != "audio" && type != "sub") return@mapNotNull null
                MpvNativeTrack(id, type, row.opt("codec") as? String ?: "",
                    row.opt("lang") as? String, row.opt("title") as? String,
                    row.opt("selected") == true, row.opt("default") == true,
                    row.opt("forced") == true, row.opt("hearing-impaired") == true,
                    row.integer("ff-index")?.takeIf { it >= 0 },
                    row.integer("audio-channels")?.takeIf { it > 0 },
                    row.integer("demux-samplerate")?.takeIf { it > 0 },
                    row.integer("demux-w")?.takeIf { it > 0 },
                    row.integer("demux-h")?.takeIf { it > 0 })
            }
        } catch (_: Exception) { null }
    }

    private fun JSONObject.integer(key: String): Int? {
        val raw = opt(key) as? Number ?: return null
        val value = raw.toDouble()
        return value.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
    }
}
