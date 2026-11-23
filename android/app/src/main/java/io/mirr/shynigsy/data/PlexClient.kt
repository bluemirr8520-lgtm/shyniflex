package io.mirr.shynigsy.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val PLEX_PRODUCT_NAME = "PLEXMINI"

internal fun plexOffsetSeconds(positionMs: Long): String =
    java.math.BigDecimal.valueOf(positionMs, 3).stripTrailingZeros().toPlainString()

data class PlexLibraryPage(val items: List<MediaItem>, val total: Int, val nextOffset: Int)
data class PlexItemInfo(val item: MediaItem, val versions: List<MediaVersion>, val cast: List<CastMember>, val files: List<MediaFileInfo>)

private class PlexRequestException(val status: Int, message: String) : IOException(message)

/** Plex local library IDs (ratingKey, section ID and part ID) are distinct namespaces. */
class PlexClient(server: String, token: String, deviceId: String) {
    private val base = server.trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }?.let { it.newBuilder().encodedPath(it.encodedPath.trimEnd('/') + "/").build() }
        ?: throw IllegalArgumentException("http:// 또는 https://로 시작하는 Plex 서버 주소를 입력해 주세요.")
    val server: String = base.toString().trimEnd('/')

    val headers: Map<String, String> = mapOf(
        "X-Plex-Token" to token.trim().also { value ->
            require(value.isNotEmpty() && value.all { it.code in 33..126 }) { "Plex 토큰을 확인해 주세요." }
        },
        "X-Plex-Client-Identifier" to deviceId.also { value ->
            require(value.isNotEmpty() && value.all { it.code in 33..126 }) { "기기 식별자를 확인해 주세요." }
        },
        "X-Plex-Product" to PLEX_PRODUCT_NAME,
        "X-Plex-Platform" to "Android",
        "X-Plex-Version" to io.mirr.shynigsy.BuildConfig.VERSION_NAME,
    )

    private fun trusted(url: HttpUrl): Boolean = url.scheme == base.scheme &&
        url.host == base.host && url.port == base.port && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath.startsWith(base.encodedPath)

    fun headersFor(url: String): Map<String, String> =
        if (url.toHttpUrlOrNull()?.let(::trusted) == true) headers else emptyMap()

    // Keys from PMS normally start with /library (even behind a reverse proxy).
    internal fun resource(key: String?): HttpUrl? {
        if (key.isNullOrBlank() || key.startsWith("//") || '\\' in key) return null
        val url = when {
            key.startsWith(base.encodedPath) -> base.resolve(key)
            key.startsWith("/") -> base.resolve(key.removePrefix("/"))
            else -> key.toHttpUrlOrNull()
        }?.takeIf(::trusted) ?: return null
        return url.newBuilder().apply {
            url.queryParameterNames.filter { it.equals("X-Plex-Token", true) }
                .forEach(::removeAllQueryParameters)
            fragment(null)
        }.build()
    }

    internal suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        expectContainer: Boolean = true,
    ): JSONObject {
        val url = requireNotNull(resource(path)).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            this@PlexClient.headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(IOException("Plex 서버에 연결하지 못했습니다. 서버 주소와 네트워크를 확인해 주세요."))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val container = response.use {
                            if (!it.isSuccessful) throw PlexRequestException(it.code, when (it.code) {
                                401, 403 -> "Plex 서버가 접근을 거부했습니다. 계정 토큰과 라이브러리 접근 권한을 확인해 주세요."
                                in 300..399 -> "Plex 서버의 최종 접속 주소를 입력해 주세요."
                                else -> "Plex 서버 요청이 실패했습니다 (HTTP ${it.code})."
                            })
                            if (!expectContainer) return@use JSONObject()
                            JSONObject(it.body?.string().orEmpty()).getJSONObject("MediaContainer").apply {
                                if (!has("totalSize")) it.header("X-Plex-Container-Total-Size")?.toIntOrNull()?.let { n -> put("totalSize", n) }
                                if (!has("offset")) it.header("X-Plex-Container-Start")?.toIntOrNull()?.let { n -> put("offset", n) }
                            }
                        }
                        if (cont.isActive) cont.resume(container)
                    } catch (_: JSONException) {
                        if (cont.isActive) cont.resumeWithException(IOException("Plex 라이브러리 응답을 읽지 못했습니다. 서버 주소를 확인해 주세요."))
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    suspend fun libraries(): List<Library> = request("/library/sections").rows("Directory").mapNotNull { row ->
        val type = when (row.optString("type")) { "show" -> "tv"; "movie" -> "movie"; else -> return@mapNotNull null }
        Library(row.intOrNull("key") ?: return@mapNotNull null, row.getString("title"), type)
    }

    suspend fun page(library: Library, offset: Int = 0, limit: Int = 60,
        contentType: LibraryContentType = LibraryContentType.defaultFor(library),
        sort: LibrarySort = LibrarySort.defaultFor(contentType),
        watchFilter: LibraryWatchFilter = LibraryWatchFilter.ALL, title: String = ""): PlexLibraryPage {
        require(offset >= 0 && limit > 0)
        require(contentType in LibraryContentType.availableFor(library))
        val type = when (contentType) {
            LibraryContentType.MOVIES -> "1"
            LibraryContentType.SHOWS -> "2"
            LibraryContentType.SEASONS -> "3"
            LibraryContentType.EPISODES -> "4"
        }
        val order = when (sort.forContentType(contentType)) {
            LibrarySort.TITLE_ASC -> "titleSort:asc"
            LibrarySort.TITLE_DESC -> "titleSort:desc"
            LibrarySort.RECENT -> "addedAt:desc"
            LibrarySort.YEAR_ASC -> "year:asc"
            LibrarySort.YEAR_DESC -> "year:desc"
            LibrarySort.RELEASED_ASC -> "originallyAvailableAt:asc"
            LibrarySort.RELEASED_DESC -> "originallyAvailableAt:desc"
            LibrarySort.RATING -> "rating:desc"
            LibrarySort.LATEST_EPISODE -> if (contentType == LibraryContentType.SHOWS) "episode.addedAt:desc" else "addedAt:desc"
        }
        val query = paging(offset, limit).toMutableMap().apply {
            put("type", type)
            put("sort", order)
            put("includeCollections", "0")
            if (title.isNotBlank()) put("title", title.trim())
            when (watchFilter) {
                LibraryWatchFilter.UNWATCHED -> put("unwatched", "1")
                LibraryWatchFilter.WATCHED -> put("unwatched", "0")
                LibraryWatchFilter.IN_PROGRESS -> put("inProgress", "1")
                LibraryWatchFilter.ALL -> Unit
            }
        }
        val container = request("/library/sections/${library.id}/all", query)
        val rows = container.rows("Metadata")
        val items = rows.mapNotNull { mediaItem(it, library.id) }
        val count = container.optInt("size", rows.size)
        val end = offset + count
        val total = container.optInt("totalSize", if (count == limit && container.has("offset")) end + 1 else end)
        return PlexLibraryPage(items, total, end)
    }

    private suspend fun allChildren(id: Int, libraryId: Int): List<MediaItem> {
        val result = linkedMapOf<Int, MediaItem>()
        val limit = 100
        var offset = 0
        while (true) {
            val container = request("/library/metadata/$id/children", paging(offset, limit) + ("includeMedia" to "1"))
            val rows = container.rows("Metadata")
            val previous = result.size
            rows.mapNotNull { mediaItem(it, libraryId) }.forEach { result[it.id] = it }
            offset += rows.size
            if (rows.isEmpty() || result.size == previous) break
            val total = container.indexOrNull("totalSize")
            if (total != null && offset >= total) break
            if (total == null && rows.size < limit) break
        }
        return result.values.toList()
    }

    suspend fun children(item: MediaItem): List<MediaItem> = allChildren(item.id, item.libraryId)

    suspend fun continueWatching(): List<MediaItem> {
        val container = try {
            request("/hubs/continueWatching", mapOf("count" to "40"))
        } catch (e: PlexRequestException) {
            if (e.status != 404) throw e
            request("/library/onDeck", paging(0, 40))
        }
        val hubs = container.rows("Hub")
        val hub = hubs.firstOrNull {
            it.optString("hubIdentifier") == "home.continue" || it.optString("context") == "hub.home.continue" ||
                it.optString("key").substringBefore('?').trimEnd('/').endsWith("/continueWatching")
        } ?: hubs.singleOrNull()
        return (hub ?: container).rows("Metadata").mapNotNull { mediaItem(it, 0) }
            .filter { it.playable }.distinctBy { it.id }
    }

    suspend fun search(query: String): List<MediaItem> {
        val term = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val container = try {
            request("/hubs/search", mapOf("query" to term, "limit" to "40"))
        } catch (e: PlexRequestException) {
            if (e.status == 404) return emptyList()
            throw e
        }
        val localMetadata = base.encodedPath + "library/metadata/"
        val result = linkedMapOf<Int, MediaItem>()
        for (hub in container.rows("Hub").ifEmpty { listOf(container) }) {
            for (row in hub.rows("Metadata") + hub.rows("Directory") + hub.rows("Video")) {
                val key = row.stringOrNull("key")
                if (key != null && resource(key)?.encodedPath?.startsWith(localMetadata) != true) continue
                val item = mediaItem(row, hub.optInt("librarySectionID")) ?: continue
                if (item.playable || item.type == "show") result.putIfAbsent(item.id, item)
            }
        }
        return result.values.toList()
    }

    private suspend fun metadata(id: Int): JSONObject {
        val container = request("/library/metadata/$id", mapOf("includeMedia" to "1", "includeStreams" to "1",
            "includeExternalMedia" to "1", "includeMarkers" to "1"))
        val row = container.rows("Metadata").firstOrNull { it.intOrNull("ratingKey") == id }
            ?: throw IOException("Plex 서버에서 작품을 찾을 수 없습니다.")
        if (row.intOrNull("librarySectionID") == null) container.intOrNull("librarySectionID")?.let { row.put("librarySectionID", it) }
        return row
    }

    suspend fun detail(item: MediaItem): MediaItem =
        mediaItem(metadata(item.id), item.libraryId) ?: throw IOException("작품 정보를 읽지 못했습니다.")

    suspend fun itemInfo(item: MediaItem): PlexItemInfo {
        val row = metadata(item.id)
        val updated = mediaItem(row, item.libraryId) ?: throw IOException("작품 정보를 읽지 못했습니다.")
        val versions = row.rows("Media").filter(::isPlayableVersion).map { media ->
            val part = media.rows("Part").single()
            val resolution = media.stringOrNull("videoResolution")?.let {
                when { it.equals("4k", true) -> "2160p"; it.all(Char::isDigit) -> "${it}p"; else -> it }
            } ?: media.intOrNull("height")?.let { "${it}p" }
            MediaVersion(part.getInt("id"), resolution ?: "원본", resolution, media.stringOrNull("videoCodec"),
                media.stringOrNull("audioCodec"), part.optLong("size").takeIf { it > 0 },
                media.optLong("bitrate").takeIf { it > 0 }?.times(1000), updated.id)
        }
        return PlexItemInfo(updated, versions, plexCast(row, this), plexFiles(row))
    }

    private fun isPlayableVersion(version: JSONObject): Boolean {
        val part = version.rows("Part").singleOrNull()
        return part != null && part.intOrNull("id") != null && resource(part.stringOrNull("key")) != null &&
            (!part.has("exists") || part.flag("exists")) && (!part.has("accessible") || part.flag("accessible"))
    }

    internal fun mediaItem(row: JSONObject, libraryId: Int): MediaItem? {
        val id = row.intOrNull("ratingKey") ?: return null
        val type = row.optString("type").takeIf { it in videoTypes } ?: return null
        val index = row.indexOrNull("index")
        val duration = row.optLong("duration").coerceAtLeast(0)
        val offset = row.optLong("viewOffset").coerceAtLeast(0)
        val count = row.indexOrNull("leafCount")
        val watched = row.optInt("viewCount") > 0 ||
            (count != null && count > 0 && row.optInt("viewedLeafCount") >= count)
        val showId = when (type) {
            "episode" -> row.intOrNull("grandparentRatingKey")
            "season" -> row.intOrNull("parentRatingKey")
            else -> null
        }
        val media = row.rows("Media")
        val playableVersion = media.firstOrNull(::isPlayableVersion)
        val partId = playableVersion?.rows("Part")?.single()?.intOrNull("id")
        val displayVersion = playableVersion ?: media.firstOrNull { !it.has("Part") }
        val markers = row.rows("Marker").mapNotNull {
            val start = it.optLong("startTimeOffset", -1)
            val end = it.optLong("endTimeOffset", -1)
            val kind = it.optString("type")
            if (start >= 0 && end > start && kind in markerTypes) PlaybackMarker(kind, start, end) else null
        }
        return MediaItem(
            id = id, libraryId = row.intOrNull("librarySectionID") ?: libraryId,
            title = row.stringOrNull("title") ?: return null, type = type, summary = row.optString("summary"),
            poster = resource(if (type == "episode") row.stringOrNull("grandparentThumb")
                ?: row.stringOrNull("parentThumb") ?: row.stringOrNull("thumb") else row.stringOrNull("thumb"))?.toString(),
            thumbnail = resource(row.stringOrNull("thumb"))?.toString(),
            backdrop = resource(row.stringOrNull("art") ?: row.stringOrNull("grandparentArt"))?.toString(),
            durationMs = duration, resumeMs = offset, watched = watched,
            seasonNumber = if (type == "season") index else row.indexOrNull("parentIndex"),
            episodeNumber = index.takeIf { type == "episode" },
            showId = showId,
            showTitle = if (type == "season") row.stringOrNull("parentTitle") else row.stringOrNull("grandparentTitle"),
            viewItemId = showId ?: id, year = row.intOrNull("year"), episodeCount = count, markers = markers,
            folderKey = row.intOrNull("parentRatingKey")?.let { "/library/metadata/$it/children" },
            playbackPartId = partId, navigationPartId = partId,
            watchState = when {
                offset > 0 -> MediaWatchState.IN_PROGRESS
                watched -> MediaWatchState.WATCHED
                row.optInt("viewedLeafCount") > 0 -> MediaWatchState.IN_PROGRESS
                else -> MediaWatchState.UNWATCHED
            },
            genres = row.rows("Genre").mapNotNull { it.stringOrNull("tag") },
            productionCountries = row.rows("Country").mapNotNull { it.stringOrNull("tag") },
            originalLanguage = row.stringOrNull("originalLanguage"),
            addedAtMs = row.optLong("addedAt").takeIf { it > 0 }?.times(1000),
            releaseDate = mediaReleaseDate(row),
            resolutionLabel = displayVersion?.let {
                mediaResolutionLabel(it.stringOrNull("videoResolution")) ?: mediaResolutionLabel(it.stringOrNull("height"))
            },
        )
    }

    suspend fun playback(item: MediaItem, selectedPartId: Int? = null): PlaybackSpec {
        val row = metadata(item.id)
        return originalPlayback(row, item, selectVersion(row.rows("Media"), selectedPartId))
    }

    private fun selectVersion(versions: List<JSONObject>, selectedPartId: Int?): JSONObject =
        versions.firstOrNull { version ->
            isPlayableVersion(version) && (selectedPartId == null || version.rows("Part").single().intOrNull("id") == selectedPartId)
        } ?: throw IOException(when {
            selectedPartId != null -> "선택한 원본 파일을 찾을 수 없습니다."
            versions.any { it.rows("Part").size > 1 } -> "여러 파일로 나뉜 작품은 직접 재생을 지원하지 않습니다."
            else -> "Plex 서버에 직접 재생할 수 있는 원본 파일이 없습니다."
        })

    private fun originalPlayback(row: JSONObject, item: MediaItem, media: JSONObject, audioIndex: Int? = null): PlaybackSpec {
        val selected = mediaItem(row, item.libraryId)?.takeIf { it.playable }
            ?: throw IOException("이 작품은 재생할 수 없습니다.")
        val part = media.rows("Part").single()
        val partId = part.getInt("id")
        val streams = part.rows("Stream")
        val audio = streams.filter { it.optInt("streamType") == 2 }
        val activeAudio = audioIndex?.let { index -> audio.firstOrNull { it.indexOrNull("index") == index } }
            ?: audio.firstOrNull { it.flag("selected") } ?: audio.firstOrNull()
        val external = streams.filter { it.optInt("streamType") == 3 }.mapNotNull { stream ->
            val url = resource(stream.stringOrNull("key")) ?: return@mapNotNull null
            val codec = stream.optString("codec").lowercase()
            val mime = when (codec) {
                "srt", "subrip" -> "application/x-subrip"
                "ass", "ssa" -> "text/x-ssa"
                "vtt", "webvtt" -> "text/vtt"
                "smi", "sami" -> "application/x-sami"
                "ttml", "dfxp" -> "application/ttml+xml"
                else -> return@mapNotNull null
            }
            val id = stream.intOrNull("id") ?: return@mapNotNull null
            SubtitleTrack(id, stream.stringOrNull("displayTitle") ?: stream.stringOrNull("language") ?: "자막 $id",
                url.toString(), mime, source = "external", language = stream.stringOrNull("languageCode"),
                format = codec, forced = stream.flag("forced"), sdh = stream.flag("hearingImpaired"), title = stream.stringOrNull("title"))
        }
        val selectedSubtitle = streams.firstOrNull { it.optInt("streamType") == 3 && it.flag("selected") }?.intOrNull("id")
        return PlaybackSpec(
            title = selected.title, itemId = selected.id, libraryId = selected.libraryId, partId = partId,
            url = requireNotNull(resource(part.getString("key"))).toString(),
            sourceItem = selected.copy(playbackPartId = partId, navigationPartId = partId),
            durationMs = selected.durationMs.takeIf { it > 0 } ?: part.optLong("duration"),
            resumeMs = selected.resumeMs, subtitles = external, markers = selected.markers,
            sessionId = UUID.randomUUID().toString(), viewItemId = selected.viewItemId, decision = "direct_play",
            selectedAudioCodec = activeAudio?.stringOrNull("codec") ?: media.stringOrNull("audioCodec"),
            selectedAudioStreamIndex = activeAudio?.indexOrNull("index"),
            selectedAudioStreamOrdinal = activeAudio?.let(audio::indexOf),
            selectedAudioLanguage = activeAudio?.stringOrNull("languageCode"),
            sourceVideoCodec = media.stringOrNull("videoCodec"), sourceContainer = part.stringOrNull("container") ?: media.stringOrNull("container"),
            selectedSubtitleId = selectedSubtitle?.takeIf { id -> external.any { it.id == id } },
        )
    }

    internal suspend fun convertPlayback(spec: PlaybackSpec, height: Int?, positionMs: Long,
        audioIndex: Int?, subtitleIndex: Int?): PlaybackSpec {
        val source = spec.sourceItem?.takeIf { it.id == spec.itemId }
            ?: throw ApiException("재생 중인 Plex 항목을 확인할 수 없습니다.")
        val profile = height?.let { plexResolution(it) }
        val row = metadata(source.id)
        val versions = row.rows("Media")
        val media = selectVersion(versions, spec.partId)
        val original = originalPlayback(row, source, media, audioIndex)
        val position = positionMs.coerceIn(0, original.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val current = original.copy(resumeMs = position, sessionId = spec.sessionId ?: original.sessionId)
        if (profile == null) return current

        val streams = media.rows("Part").single().rows("Stream")
        val audio = streams.filter { it.optInt("streamType") == 2 }
        val selectedAudio = current.selectedAudioStreamOrdinal?.let(audio::getOrNull)
        val embedded = streams.filter { it.optInt("streamType") == 3 && it.stringOrNull("key") == null }
        // A converted MKV renumbers tracks. On subsequent changes retain the source PMS ID.
        val priorSubtitleId = if (spec.isServerConversion) resource(spec.url)?.queryParameter("subtitleStreamID")?.toIntOrNull() else null
        val selectedSub = if (priorSubtitleId != null) embedded.firstOrNull { it.intOrNull("id") == priorSubtitleId }
            else subtitleIndex?.let { index -> embedded.firstOrNull { it.indexOrNull("index") == index } }
                ?: embedded.firstOrNull { it.flag("selected") }
        val transcodeSession = UUID.randomUUID().toString()
        val params = linkedMapOf(
            "path" to "/library/metadata/${spec.itemId}", "mediaIndex" to versions.indexOf(media).toString(), "partIndex" to "0",
            "protocol" to "http", "directPlay" to "0", "directStream" to "0", "directStreamAudio" to "0",
            "videoResolution" to profile.first, "maxVideoBitrate" to profile.second.toString(), "videoBitrate" to profile.second.toString(),
            "offset" to plexOffsetSeconds(position), "fastSeek" to "1",
            "subtitles" to (if (selectedSub != null) "embedded" else "none"),
            "subtitleStreamID" to (selectedSub?.intOrNull("id") ?: 0).toString(),
            "advancedSubtitles" to "text", "transcodeSessionId" to transcodeSession, "session" to transcodeSession,
            "X-Plex-Session-Identifier" to requireNotNull(current.sessionId),
            "X-Plex-Client-Identifier" to headers.getValue("X-Plex-Client-Identifier"),
            "X-Plex-Client-Profile-Name" to "Generic", "X-Plex-Platform" to "Android",
            "X-Plex-Product" to PLEX_PRODUCT_NAME, "X-Plex-Version" to headers.getValue("X-Plex-Version"),
            "X-Plex-Client-Profile-Extra" to
                "add-transcode-target(type=videoProfile&context=streaming&protocol=http&container=mkv&videoCodec=h264&audioCodec=aac&subtitleCodec=srt,ass,pgs,vobsub&replace=true)",
        )
        selectedAudio?.intOrNull("id")?.let { params["audioStreamID"] = it.toString() }
        val decision = try { request("/video/:/transcode/universal/decision", params) }
            catch (error: PlexRequestException) { throw ApiException(error.message ?: "Plex 서버가 변환을 거부했습니다.", error.status) }
        val general = decision.optInt("generalDecisionCode")
        val transcode = decision.optInt("transcodeDecisionCode")
        val mde = decision.optInt("mdeDecisionCode")
        if (decision.optInt("terminationCode") > 0 || general >= 2000 || transcode >= 2000 || mde >= 2000 ||
            (general != 1001 && transcode != 1001 && mde != 1001))
            throw ApiException("Plex 서버에서 선택한 해상도로 변환할 수 없습니다.")
        // Progressive MKV is one authenticated request. No credentials are passed to a global MPV header list.
        val stream = requireNotNull(resource("/video/:/transcode/universal/start.mkv")).newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return current.copy(url = stream.toString(), conversionHeight = height, streamStartPositionMs = position, decision = "transcode")
    }

    /** The old HTTP stream is already closed. Release its encoder without delaying the new player. */
    internal fun stopTranscode(session: String) {
        val url = requireNotNull(resource("/video/:/transcode/universal/stop")).newBuilder()
            .addQueryParameter("session", session).addQueryParameter("transcodeSessionId", session).build()
        val request = Request.Builder().url(url).apply {
            this@PlexClient.headers.forEach { (name, value) -> header(name, value) }
        }.build()
        http.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = response.close()
            override fun onFailure(call: Call, e: IOException) = Unit
        })
    }

    private fun plexResolution(height: Int): Pair<String, Int> = when (height) {
        1080 -> "1920x1080" to 8000
        720 -> "1280x720" to 4000
        480 -> "854x480" to 1500
        else -> throw ApiException("지원하지 않는 Plex 해상도입니다.")
    }

    suspend fun neighbors(spec: PlaybackSpec): PlaybackNeighbors {
        val item = spec.sourceItem?.takeIf { it.type == "episode" } ?: return PlaybackNeighbors()
        val show = item.showId ?: return PlaybackNeighbors(currentEpisode = item)
        val seasons = allChildren(show, item.libraryId).filter { it.type == "season" }.sortedBy { it.seasonNumber }
        val season = seasons.indexOfFirst { it.seasonNumber == item.seasonNumber }
        if (season < 0) return PlaybackNeighbors(currentEpisode = item)
        suspend fun seasonEpisodes(index: Int): List<MediaItem> = seasons.getOrNull(index)?.let { children(it) }
            .orEmpty().filter { it.type == "episode" }.sortedBy { it.episodeNumber }
        val episodes = seasonEpisodes(season)
        val index = episodes.indexOfFirst { it.id == item.id }
        if (index < 0) return PlaybackNeighbors(currentEpisode = item)
        val previous = episodes.getOrNull(index - 1) ?: seasonEpisodes(season - 1).lastOrNull()
        val next = (episodes.getOrNull(index + 1) ?: seasonEpisodes(season + 1).firstOrNull())?.let {
            if (it.navigationPartId == null) detail(it) else it
        }
        return PlaybackNeighbors(previous, next, next, item)
    }

    suspend fun timeline(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String) {
        require(state in timelineStates)
        val query = mutableMapOf("ratingKey" to spec.itemId.toString(), "key" to "/library/metadata/${spec.itemId}",
            "state" to state, "time" to positionMs.coerceAtLeast(0).toString(), "duration" to durationMs.coerceAtLeast(0).toString(),
            "identifier" to "com.plexapp.plugins.library")
        spec.sessionId?.let { query["X-Plex-Session-Identifier"] = it }
        request("/:/timeline", query, expectContainer = false)
    }

    suspend fun setWatched(itemId: Int, watched: Boolean) {
        request(if (watched) "/:/scrobble" else "/:/unscrobble",
            mapOf("identifier" to "com.plexapp.plugins.library", "key" to itemId.toString()), expectContainer = false)
    }

    private companion object {
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        val videoTypes = setOf("movie", "show", "season", "episode")
        val timelineStates = setOf("playing", "paused", "buffering", "stopped")
        val markerTypes = setOf("intro", "credits", "recap", "preview")
        fun paging(offset: Int, limit: Int) =
            mapOf("X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to limit.toString())
        fun JSONObject.rows(key: String): List<JSONObject> {
            val array = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        fun JSONObject.flag(key: String) = optBoolean(key) || optInt(key) == 1
    }
}
