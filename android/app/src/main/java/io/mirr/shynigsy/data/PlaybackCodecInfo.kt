package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject

/** Observations for the exact authorized source part. Null means unknown, not unsupported. */
internal data class PlaybackCodecInfo(
    val partId: Int,
    val container: String?,
    val videoCodec: String?,
    val videoProfile: String?,
    val videoBitDepth: Int?,
    val hdrFormat: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val audioCodec: String?,
    val audioProfile: String?,
    val audioChannels: Int?,
    val selectedAudioCodec: String?,
    /** A server-reported list, not proof that all embedded and external tracks were scanned. */
    val subtitleFormats: List<String>?,
)

/** Never identify a file by its title, file path, or position within an options list. */
internal fun parsePlaybackCodecInfo(info: JSONObject, spec: PlaybackSpec): PlaybackCodecInfo? {
    if (spec.partId <= 0) return null
    fun positiveInt(value: Any?): Int? = try {
        when (value) {
            is Number, is String -> java.math.BigDecimal(value.toString()).intValueExact().takeIf { it > 0 }
            else -> null
        }
    } catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }
    if (info.has("library_view_id") && !info.isNull("library_view_id") &&
        positiveInt(info.opt("library_view_id")) != spec.libraryId) return null
    if (info.has("view_item_id") && !info.isNull("view_item_id") &&
        positiveInt(info.opt("view_item_id")) != spec.viewItemId) return null
    if (info.has("selected_part_id") && !info.isNull("selected_part_id") &&
        positiveInt(info.opt("selected_part_id")) != spec.partId) return null
    val parts = info.opt("parts") as? JSONArray ?: return null
    val matches = mutableListOf<JSONObject>()
    for (index in 0 until parts.length()) {
        val row = parts.opt(index) as? JSONObject ?: return null
        val id = positiveInt(row.opt("id"))
        val alternate = positiveInt(row.opt("part_id"))
        if (id != spec.partId && alternate != spec.partId) continue
        if ((row.has("id") && !row.isNull("id") && id == null) ||
            (row.has("part_id") && !row.isNull("part_id") && alternate == null) ||
            (id != null && alternate != null && id != alternate)) return null
        matches += row
    }
    if (matches.size != 1) return null
    val part = matches.single()
    val itemRow = info.optJSONObject("item")
    val infoItem = itemRow?.let { positiveInt(it.opt("id")) }
    val partItem = positiveInt(part.opt("metadata_item_id"))
    if ((itemRow?.has("id") == true && !itemRow.isNull("id") && infoItem == null) ||
        (part.has("metadata_item_id") && !part.isNull("metadata_item_id") && partItem == null)) return null
    if (infoItem != null && partItem != null && infoItem != partItem) return null

    fun label(row: JSONObject, key: String): String? = (row.opt(key) as? String)?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 100 && it.none { ch -> ch.code < 32 || ch.code == 127 } }
    val resolution = label(part, "resolution")?.let {
        Regex("^(\\d{1,5})\\s*[xX×]\\s*(\\d{1,5})$").matchEntire(it)
    }
    val resolutionWidth = resolution?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..16384 }
    val resolutionHeight = resolution?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..16384 }
    fun dimension(key: String, parsed: Int?): Int? {
        val explicit = positiveInt(part.opt(key))?.takeIf { it <= 16384 }
        if (explicit != null && parsed != null && explicit != parsed) return null
        return explicit ?: parsed
    }
    val fps = when (val raw = part.opt("fps")) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() && it > 0.0 && it <= 480.0 }
    val subtitles = (part.opt("subtitles") as? JSONArray)?.let { array ->
        val reported = (0 until array.length()).mapNotNull { array.opt(it) as? JSONObject }
        if (reported.size != array.length()) null else {
            val formats = reported.map { label(it, "format") }
            if (formats.any { it == null }) null else formats.filterNotNull()
        }
    }
    return PlaybackCodecInfo(
        partId = spec.partId, container = label(part, "container"),
        videoCodec = label(part, "video_codec"), videoProfile = label(part, "video_profile"),
        videoBitDepth = positiveInt(part.opt("video_bit_depth"))?.takeIf { it <= 32 },
        hdrFormat = label(part, "hdr_format"), width = dimension("width", resolutionWidth),
        height = dimension("height", resolutionHeight), frameRate = fps,
        audioCodec = label(part, "audio_codec"), audioProfile = label(part, "audio_profile"),
        audioChannels = positiveInt(part.opt("audio_channels"))?.takeIf { it <= 64 },
        selectedAudioCodec = spec.selectedAudioCodec?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 },
        subtitleFormats = subtitles,
    )
}
