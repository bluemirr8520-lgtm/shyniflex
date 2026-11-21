package io.mirr.shynigsy.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

internal fun plexCast(row: JSONObject, client: PlexClient): List<CastMember> =
    row.optJSONArray("Role")?.objects().orEmpty().mapNotNull { role ->
        val name = role.stringOrNull("tag") ?: return@mapNotNull null
        val thumb = role.stringOrNull("thumb")
        val hosted = thumb?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host == "metadata-static.plex.tv" && it.username.isEmpty() && it.password.isEmpty() &&
                it.query == null && it.fragment == null
        }
        // The server resizes Plex-hosted portraits and the client uses its normal authenticated artwork path.
        val portrait = client.resource(thumb) ?: hosted?.let {
            client.resource("/photo/:/transcode")?.newBuilder()?.addQueryParameter("url", it.toString())
                ?.addQueryParameter("width", "160")?.addQueryParameter("height", "220")?.build()
        }
        CastMember(name, role.stringOrNull("role").orEmpty(), portrait?.toString(), ppkey = role.intOrNull("id")?.toString())
    }.distinctBy { it.key }

internal fun plexFiles(row: JSONObject): List<MediaFileInfo> = row.optJSONArray("Media")?.objects().orEmpty().flatMap { media ->
    media.optJSONArray("Part")?.objects().orEmpty().mapNotNull { part ->
        val partId = part.intOrNull("id") ?: return@mapNotNull null
        val streams = part.optJSONArray("Stream")?.objects()
        val video = streams?.firstOrNull { it.optInt("streamType") == 1 }
        val width = media.intOrNull("width")
        val height = media.intOrNull("height")
        MediaFileInfo(
            partId = partId, fileName = safeFileName(part.opt("file")),
            container = part.stringOrNull("container") ?: media.stringOrNull("container"),
            videoCodec = media.stringOrNull("videoCodec") ?: video?.stringOrNull("codec"),
            videoProfile = video?.stringOrNull("profile"), videoBitDepth = video?.intOrNull("bitDepth"),
            resolution = if (width != null && height != null) "${width}×$height" else media.stringOrNull("videoResolution"),
            frameRate = video?.optDouble("frameRate")?.takeIf { it.isFinite() && it > 0 },
            fileSizeBytes = part.optLong("size").takeIf { it > 0 },
            bitrateBps = media.optLong("bitrate").takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000),
            audioCodec = media.stringOrNull("audioCodec"), audioChannels = media.intOrNull("audioChannels"),
            audioTracks = streams?.filter { it.optInt("streamType") == 2 }?.map {
                MediaFileAudio(it.stringOrNull("codec"), it.stringOrNull("language") ?: it.stringOrNull("languageCode"),
                    it.intOrNull("channels"), it.stringOrNull("profile"), it.stringOrNull("title"),
                    it.plexFlag("default"))
            },
            subtitles = streams?.filter { it.optInt("streamType") == 3 }?.map {
                MediaFileSubtitle(it.stringOrNull("language") ?: it.stringOrNull("languageCode"), it.stringOrNull("codec"),
                    if (it.stringOrNull("key") == null) "embedded" else "external", it.stringOrNull("title"),
                    it.plexFlag("forced"), it.plexFlag("hearingImpaired"))
            },
        )
    }
}

private fun JSONObject.plexFlag(key: String): Boolean? = if (isNull(key)) null else optBoolean(key) || optInt(key) == 1

internal suspend fun PlexClient.actorLibraryWorks(actor: CastMember, libraries: List<Library>): ActorWorks {
    val id = actor.ppkey?.toIntOrNull()?.takeIf { it > 0 } ?: return ActorWorks(emptyList())
    val sections = libraries.filter { it.type == "movie" || it.type == "tv" }
    val items = linkedMapOf<Int, MediaItem>()
    var failures = 0
    for (section in sections) {
        val type = if (section.type == "movie") "movie" else "show"
        val pageSize = 100
        var offset = 0
        try {
            while (true) {
                val container = request("/library/sections/${section.id}/all", mapOf(
                    "actor" to id.toString(), "type" to if (type == "movie") "1" else "2",
                    "X-Plex-Container-Size" to pageSize.toString(), "X-Plex-Container-Start" to offset.toString(),
                ))
                val rows = container.optJSONArray("Metadata")?.objects().orEmpty()
                if (rows.isEmpty() || container.optInt("offset", offset) != offset) break
                val previousCount = items.size
                for (row in rows) {
                    val item = mediaItem(row, section.id) ?: continue
                    if (item.type == type && item.libraryId == section.id) items[item.id] = item
                }
                offset += rows.size
                if (items.size == previousCount || offset >= container.optInt("totalSize", Int.MAX_VALUE) ||
                    (!container.has("totalSize") && rows.size < pageSize)) break
            }
        } catch (e: IOException) {
            failures++
            if (failures == sections.size && items.isEmpty()) throw e
        }
    }
    return ActorWorks(items.values.toList(), incomplete = failures > 0)
}
