package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject

/** Display-only metadata. Paths, tickets, URLs and request headers never enter UI state. */
data class MediaFileInfo(
    val partId: Int,
    val fileName: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val videoBitDepth: Int? = null,
    val resolution: String? = null,
    val frameRate: Double? = null,
    val hdrFormat: String? = null,
    val fileSizeBytes: Long? = null,
    val bitrateBps: Long? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    /** null is unreported or incomplete; an empty list is explicitly reported as empty. */
    val audioTracks: List<MediaFileAudio>? = null,
    val subtitles: List<MediaFileSubtitle>? = null,
)

data class MediaFileAudio(
    val codec: String? = null,
    val language: String? = null,
    val channels: Int? = null,
    val profile: String? = null,
    val title: String? = null,
    val isDefault: Boolean? = null,
)

data class MediaFileSubtitle(
    val language: String? = null,
    val format: String? = null,
    val source: String? = null,
    val title: String? = null,
    val forced: Boolean? = null,
    val sdh: Boolean? = null,
)

/** A file is identified by its part ID and authorized View, never its name or list position. */
internal fun parseMediaFileInfo(info: JSONObject, item: MediaItem, selectedPartId: Int? = null): List<MediaFileInfo> {
    fun invalid(): Nothing = throw ApiException("파일 정보의 작품 식별자를 확인할 수 없습니다.")
    fun checkIdentity(row: JSONObject, key: String, expected: Int) {
        if (row.has(key) && !row.isNull(key) && exactPositiveLong(row.opt(key)) != expected.toLong()) invalid()
    }
    checkIdentity(info, "library_view_id", item.libraryId)
    item.viewItemId?.let { checkIdentity(info, "view_item_id", it) }
    if (selectedPartId != null) checkIdentity(info, "selected_part_id", selectedPartId)
    val itemRow = info.optJSONObject("item")
    val responseItem = itemRow?.let { exactPositiveLong(it.opt("id")) }
    if (itemRow?.has("id") == true && !itemRow.isNull("id") && responseItem == null) invalid()
    if (selectedPartId == null && responseItem != null && responseItem != item.id.toLong()) invalid()
    val parts = info.opt("parts") as? JSONArray ?: throw ApiException("서버가 파일 정보를 제공하지 않았습니다.")
    if (parts.length() > 256) throw ApiException("파일 정보가 너무 많습니다. 파일을 선택해 다시 확인해 주세요.")
    val seen = mutableSetOf<Int>()
    val result = mutableListOf<MediaFileInfo>()
    for (index in 0 until parts.length()) {
        val part = parts.opt(index) as? JSONObject ?: invalid()
        val primary = exactPositiveLong(part.opt("id"))?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        val alternate = exactPositiveLong(part.opt("part_id"))?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        if ((part.has("id") && !part.isNull("id") && primary == null) ||
            (part.has("part_id") && !part.isNull("part_id") && alternate == null) ||
            (primary != null && alternate != null && primary != alternate)) invalid()
        val partId = primary ?: alternate ?: invalid()
        if (!seen.add(partId)) invalid()
        if (selectedPartId != null && partId != selectedPartId) continue
        val ownItem = exactPositiveLong(part.opt("metadata_item_id"))
        if (part.has("metadata_item_id") && !part.isNull("metadata_item_id") && ownItem == null) invalid()
        if (ownItem != null && responseItem != null && ownItem != responseItem) invalid()
        if (selectedPartId == null && ownItem != null && ownItem != item.id.toLong()) invalid()
        if (part.optBoolean("remote") || part.has("federated_id") && !part.isNull("federated_id")) invalid()
        result += MediaFileInfo(
            partId = partId,
            fileName = safeFileName(part.opt("file_name")) ?: safeFileName(part.opt("file_path")),
            container = part.displayLabel("container"), videoCodec = part.displayLabel("video_codec"),
            videoProfile = part.displayLabel("video_profile"), videoBitDepth = part.positiveInt("video_bit_depth", 32),
            resolution = part.displayLabel("resolution") ?: dimensionLabel(part), frameRate = frameRate(part.opt("fps")),
            hdrFormat = part.displayLabel("hdr_format"), fileSizeBytes = exactPositiveLong(part.opt("file_size")),
            bitrateBps = exactPositiveLong(part.opt("bitrate_bps")), audioCodec = part.displayLabel("audio_codec"),
            audioChannels = part.positiveInt("audio_channels", 64),
            audioTracks = trackRows(part, "audio_tracks")?.map { track -> MediaFileAudio(
                codec = track.displayLabel("codec"), language = track.displayLabel("lang_code"),
                channels = track.positiveInt("channels", 64), profile = track.displayLabel("profile"),
                title = track.displayLabel("title", 200), isDefault = track.reportedBoolean("is_default")) },
            subtitles = trackRows(part, "subtitles")?.map { track -> MediaFileSubtitle(
                language = track.displayLabel("lang_code"), format = track.displayLabel("format"),
                source = track.displayLabel("source"), title = track.displayLabel("title", 200),
                forced = track.reportedBoolean("forced"), sdh = track.reportedBoolean("sdh")) },
        )
    }
    if (selectedPartId != null && result.size != 1) invalid()
    return result
}

private fun JSONObject.displayLabel(key: String, limit: Int = 100): String? = (opt(key) as? String)?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= limit && !it.contains("://") &&
        it.none { character -> character.isISOControl() } }

private fun safeFileName(value: Any?): String? {
    val raw = (value as? String)?.trim()?.takeIf { it.length <= 4096 && !it.contains("://") &&
        it.none { character -> character.isISOControl() } } ?: return null
    return raw.replace('\\', '/').substringAfterLast('/').takeIf {
        it.isNotBlank() && it.length <= 512 && it != "." && it != ".."
    }
}

private fun exactPositiveLong(value: Any?): Long? = try {
    when (value) {
        is Number, is String -> java.math.BigDecimal(value.toString()).longValueExact().takeIf { it > 0 }
        else -> null
    }
} catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }

private fun JSONObject.positiveInt(key: String, limit: Int): Int? = exactPositiveLong(opt(key))
    ?.takeIf { it <= limit }?.toInt()

private fun JSONObject.reportedBoolean(key: String): Boolean? = opt(key) as? Boolean

private fun dimensionLabel(part: JSONObject): String? {
    val width = part.positiveInt("width", 16384) ?: return null
    val height = part.positiveInt("height", 16384) ?: return null
    return "${width}×${height}"
}

private fun frameRate(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}?.takeIf { it.isFinite() && it > 0.0 && it <= 480.0 }

private fun trackRows(part: JSONObject, key: String): List<JSONObject>? {
    val rows = part.opt(key) as? JSONArray ?: return null
    if (rows.length() > 128) return null
    return (0 until rows.length()).map { rows.opt(it) as? JSONObject ?: return null }
}
