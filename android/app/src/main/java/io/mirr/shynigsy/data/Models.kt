package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class Library(val id: Int, val title: String, val type: String, val isSpoiler: Boolean = false)

data class SpoilerStatus(
    val available: Boolean = false,
    val pinEnabled: Boolean? = null,
    val unlocked: Boolean = false,
    val expiresInSeconds: Long = 0,
)

data class PlaybackNeighbors(val previous: MediaItem? = null, val next: MediaItem? = null)

data class PlaybackMarker(val type: String, val startMs: Long, val endMs: Long) {
    internal fun asJson(): JSONObject = JSONObject().put("type", type).put("start_ms", startMs).put("end_ms", endMs)

    companion object {
        /** Browse uses type; public media-info uses segment_type. All timestamps are milliseconds. */
        fun parse(value: Any?, durationMs: Long = 0): List<PlaybackMarker> {
            val decoded = if (value is String) runCatching { org.json.JSONTokener(value).nextValue() }.getOrNull() else value
            val rows = when (decoded) {
                is JSONArray -> decoded
                is JSONObject -> decoded.opt("markers")?.let { return parse(it, durationMs) } ?: return emptyList()
                else -> return emptyList()
            }
            fun milliseconds(raw: Any?): Long? = runCatching {
                if (raw !is Number && raw !is String) return@runCatching null
                java.math.BigDecimal(raw.toString()).longValueExact()
            }.getOrNull()
            return (0 until minOf(rows.length(), 128)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val type = (row.stringOrNull("type") ?: row.stringOrNull("segment_type"))
                    ?.trim()?.lowercase(java.util.Locale.ROOT)
                    ?.takeIf { it in setOf("intro", "credits", "recap", "preview") } ?: return@mapNotNull null
                val start = milliseconds(row.opt("start_ms") ?: row.opt("startMs")) ?: return@mapNotNull null
                val rawEnd = milliseconds(row.opt("end_ms") ?: row.opt("endMs")) ?: return@mapNotNull null
                val end = if (durationMs > 0) rawEnd.coerceAtMost(durationMs) else rawEnd
                if (start < 0 || end <= start) null else PlaybackMarker(type, start, end)
            }.distinct().sortedWith(compareBy<PlaybackMarker> { it.startMs }.thenBy { it.endMs }.thenBy { it.type })
        }
    }
}

data class MediaItem(
    val id: Int,
    val libraryId: Int,
    val title: String,
    val type: String,
    val summary: String = "",
    val poster: String? = null,
    val durationMs: Long = 0,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val viewItemId: Int? = null,
    val showId: Int? = null,
    val resumeMs: Long = 0,
    val watched: Boolean = false,
    val showTitle: String? = null,
    val displaySubtitle: String? = null,
    val backdrop: String? = null,
    val isSpoiler: Boolean = false,
    val markers: List<PlaybackMarker> = emptyList(),
    val folderKey: String? = null,
    val navigationPartId: Int? = null,
    val thumbnail: String? = null,
    val playbackPartId: Int? = null,
) {
    val playable: Boolean get() = type == "movie" || type == "episode"

    fun toJson(): String = asJson().toString()
    internal fun asJson(): JSONObject = JSONObject().apply {
        put("id", id); put("libraryId", libraryId); put("title", title); put("type", type)
        put("summary", summary); put("poster", poster); put("durationMs", durationMs)
        put("seasonNumber", seasonNumber); put("episodeNumber", episodeNumber)
        put("viewItemId", viewItemId); put("showId", showId); put("resumeMs", resumeMs)
        put("watched", watched)
        put("showTitle", showTitle); put("displaySubtitle", displaySubtitle); put("backdrop", backdrop)
        put("isSpoiler", isSpoiler)
        put("markers", JSONArray(markers.map { it.asJson() }))
        put("folderKey", folderKey); put("navigationPartId", navigationPartId)
        put("thumbnail", thumbnail)
        put("playbackPartId", playbackPartId)
    }

    companion object {
        fun fromJson(value: String): MediaItem = fromJson(JSONObject(value))
        internal fun fromJson(value: JSONObject): MediaItem = MediaItem(
            value.getInt("id"), value.getInt("libraryId"), value.getString("title"),
            value.getString("type"), value.optString("summary"), value.stringOrNull("poster"),
            value.optLong("durationMs"), value.indexOrNull("seasonNumber"),
            value.indexOrNull("episodeNumber"), value.intOrNull("viewItemId"), value.intOrNull("showId"), value.optLong("resumeMs"),
            value.optBoolean("watched"),
            value.stringOrNull("showTitle"), value.stringOrNull("displaySubtitle"),
            value.stringOrNull("backdrop"),
            value.optBoolean("isSpoiler"),
            PlaybackMarker.parse(value.opt("markers"), value.optLong("durationMs")),
            value.stringOrNull("folderKey"), value.intOrNull("navigationPartId"),
            value.stringOrNull("thumbnail"),
            value.intOrNull("playbackPartId"),
        )
    }
}

data class MediaPage(val items: List<MediaItem>, val total: Int)
data class MediaDetails(val item: MediaItem, val children: List<MediaItem> = emptyList())
data class MediaVersion(
    val partId: Int,
    val label: String,
    val resolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val fileSizeBytes: Long? = null,
    val bitrateBps: Long? = null,
    val metadataItemId: Int? = null,
)

data class SubtitleTrack(
    val id: Int,
    val label: String,
    val url: String,
    val mimeType: String = "text/vtt",
    val headers: Map<String, String> = emptyMap(),
    val fallbackUrl: String? = null,
    val fallbackMimeType: String? = null,
    val source: String? = null,
    val language: String? = null,
    val streamIndex: Int? = null,
    val format: String? = null,
    val forced: Boolean? = null,
    val sdh: Boolean? = null,
    val title: String? = null,
) {
    internal fun asJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("url", url); put("mimeType", mimeType)
        put("headers", JSONObject(headers))
        put("fallbackUrl", fallbackUrl); put("fallbackMimeType", fallbackMimeType)
        put("source", source); put("language", language); put("streamIndex", streamIndex)
        put("format", format); put("forced", forced); put("sdh", sdh); put("title", title)
    }
}

data class PlaybackSpec(
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val itemId: Int,
    val libraryId: Int,
    val partId: Int,
    val resumeMs: Long = 0,
    val durationMs: Long = 0,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val sessionId: String? = null,
    val progressToken: String? = null,
    val viewItemId: Int? = null,
    val decision: String? = null,
    val preferSoftwareDecode: Boolean = false,
    val selectedAudioStreamIndex: Int? = null,
    val selectedAudioStreamOrdinal: Int? = null,
    val selectedAudioLanguage: String? = null,
    val selectedSubtitleId: Int? = null,
    val requestedPartId: Int? = null,
    val conversionHeight: Int? = null,
    val streamStartPositionMs: Long = 0,
    val sourceItem: MediaItem? = null,
    val navigationQuery: String = "",
    val markers: List<PlaybackMarker> = emptyList(),
) {
    /** Automatic audio/remux conversion uses the same offset/reopen transport as video conversion. */
    val isServerConversion: Boolean
        get() {
            if (conversionHeight != null) return true
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if ((!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) ||
                uri.host.isNullOrBlank()) return false
            return uri.path?.trimEnd('/')?.endsWith("/transcode/$partId") == true
        }

    fun toJson(): String = JSONObject().apply {
        put("title", title); put("url", url); put("headers", JSONObject(headers))
        put("itemId", itemId); put("libraryId", libraryId); put("partId", partId)
        put("resumeMs", resumeMs); put("durationMs", durationMs)
        put("subtitles", JSONArray(subtitles.map { it.asJson() }))
        put("sessionId", sessionId); put("progressToken", progressToken)
        put("viewItemId", viewItemId); put("decision", decision)
        put("preferSoftwareDecode", preferSoftwareDecode)
        put("selectedAudioStreamIndex", selectedAudioStreamIndex)
        put("selectedAudioStreamOrdinal", selectedAudioStreamOrdinal)
        put("selectedAudioLanguage", selectedAudioLanguage)
        put("selectedSubtitleId", selectedSubtitleId)
        put("requestedPartId", requestedPartId)
        put("conversionHeight", conversionHeight); put("streamStartPositionMs", streamStartPositionMs)
        put("sourceItem", sourceItem?.asJson()); put("navigationQuery", navigationQuery)
        put("markers", JSONArray(markers.map { it.asJson() }))
    }.toString()

    companion object {
        fun fromJson(value: String): PlaybackSpec {
            val json = JSONObject(value)
            val tracks = json.optJSONArray("subtitles") ?: JSONArray()
            return PlaybackSpec(
                title = json.getString("title"), url = json.getString("url"),
                headers = json.optJSONObject("headers").stringMap(),
                itemId = json.getInt("itemId"), libraryId = json.getInt("libraryId"),
                partId = json.getInt("partId"), resumeMs = json.optLong("resumeMs"),
                durationMs = json.optLong("durationMs"),
                subtitles = (0 until tracks.length()).map { index ->
                    val track = tracks.getJSONObject(index)
                    SubtitleTrack(track.getInt("id"), track.getString("label"),
                        track.getString("url"), track.optString("mimeType", "text/vtt"),
                        track.optJSONObject("headers").stringMap(), track.stringOrNull("fallbackUrl"),
                        track.stringOrNull("fallbackMimeType"), track.stringOrNull("source"),
                        track.stringOrNull("language"), track.indexOrNull("streamIndex"),
                        track.stringOrNull("format"), track.booleanOrNull("forced"),
                        track.booleanOrNull("sdh"), track.stringOrNull("title"))
                },
                sessionId = json.stringOrNull("sessionId"), progressToken = json.stringOrNull("progressToken"),
                viewItemId = json.intOrNull("viewItemId"), decision = json.stringOrNull("decision"),
                preferSoftwareDecode = json.optBoolean("preferSoftwareDecode"),
                selectedAudioStreamIndex = json.indexOrNull("selectedAudioStreamIndex"),
                selectedAudioStreamOrdinal = json.indexOrNull("selectedAudioStreamOrdinal"),
                selectedAudioLanguage = json.stringOrNull("selectedAudioLanguage"),
                selectedSubtitleId = json.intOrNull("selectedSubtitleId"),
                requestedPartId = json.intOrNull("requestedPartId"),
                conversionHeight = json.intOrNull("conversionHeight"),
                streamStartPositionMs = json.optLong("streamStartPositionMs").coerceAtLeast(0),
                sourceItem = json.optJSONObject("sourceItem")?.let(MediaItem::fromJson),
                navigationQuery = json.optString("navigationQuery"),
                markers = PlaybackMarker.parse(json.opt("markers"), json.optLong("durationMs")),
            )
        }
    }
}

sealed interface LoginOutcome {
    data class Success(val token: String, val userName: String) : LoginOutcome
    data class NeedsTotp(val challenge: String) : LoginOutcome
}

class ApiException(message: String, val status: Int = 0) : Exception(message)

internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key).takeIf { it > 0 }

internal fun JSONObject.indexOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key).takeIf { it >= 0 }

internal fun JSONObject.booleanOrNull(key: String): Boolean? = when (val value = opt(key)) {
    is Boolean -> value
    is String -> when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }
    else -> null
}

internal fun JSONObject?.stringMap(): Map<String, String> =
    if (this == null) emptyMap() else keys().asSequence().associateWith { getString(it) }
