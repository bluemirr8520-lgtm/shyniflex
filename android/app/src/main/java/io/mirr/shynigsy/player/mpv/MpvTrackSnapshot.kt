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

internal object MpvTrackSnapshot {
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
