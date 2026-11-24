package io.mirr.shynigsy.data

import io.mirr.shynigsy.player.verifiedCreditsNextEpisode
import io.mirr.shynigsy.player.continuousPlaybackTarget
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PlexClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PlexClient
    private val token = "fixture-token"

    @Before fun start() {
        server = MockWebServer().apply { start() }
        client = PlexClient(server.url("/plex").toString(), token, "fixture-device")
    }

    @After fun stop() = server.shutdown()

    private fun container(key: String, vararg rows: JSONObject, total: Int? = null) {
        val data = JSONObject().put(key, JSONArray(rows.toList())).put("size", rows.size)
        total?.let { data.put("totalSize", it) }
        server.enqueue(MockResponse().setBody(JSONObject().put("MediaContainer", data).toString()))
    }

    private fun episode(id: Int, season: Int, index: Int): JSONObject {
        val parent = 500 + season
        val part = JSONObject().put("id", 1000 + id).put("key", "/library/parts/${1000 + id}/video.mkv")
        val media = JSONObject().put("container", "mkv").put("Part", JSONArray().put(part)).put("videoCodec", "hevc")
        return JSONObject().put("ratingKey", id.toString()).put("type", "episode").put("title", "Episode $index")
            .put("librarySectionID", 9).put("index", index).put("parentIndex", season)
            .put("parentRatingKey", parent).put("grandparentRatingKey", 500).put("grandparentTitle", "Show")
            .put("duration", 600000).put("viewOffset", 62000).put("Media", JSONArray().put(media))
    }

    @Test fun sectionsAuthorizeWithHeadersAndPaginationUsesServerTotal() = runBlocking {
        container("Directory",
            JSONObject("""{"type":"show","title":"Series","key":"9"}"""),
            JSONObject("""{"type":"artist","title":"Music","key":"5"}"""))
        val library = client.libraries().single()
        assertEquals(Library(9, "Series", "tv"), library)
        server.takeRequest().let {
            assertEquals("/plex/library/sections", it.path)
            assertEquals(token, it.getHeader("X-Plex-Token"))
            assertEquals("fixture-device", it.getHeader("X-Plex-Client-Identifier"))
            assertEquals("PLEXMINI", it.getHeader("X-Plex-Product"))
        }
        container("Metadata", JSONObject("""{"ratingKey":"42","type":"show","title":"Series","thumb":"/library/metadata/42/thumb"}"""),
            JSONObject("""{"ratingKey":"43","type":"collection","title":"Collection"}"""), total = 347)
        val page = client.page(library, 60)
        assertEquals(347, page.total)
        assertEquals(62, page.nextOffset)
        assertEquals(server.url("/plex/library/metadata/42/thumb").toString(), page.items.single().poster)
        server.takeRequest().requestUrl!!.let {
            assertEquals("60", it.queryParameter("X-Plex-Container-Start"))
            assertEquals("60", it.queryParameter("X-Plex-Container-Size"))
            assertEquals("2", it.queryParameter("type"))
        }
    }

    @Test fun seasonListKeepsEpisodeAirDateStillAndPlayableVersionResolution() = runBlocking {
        val row = episode(80, 1, 1).put("thumb", "/library/metadata/80/thumb")
            .put("grandparentThumb", "/library/metadata/500/thumb").put("originallyAvailableAt", "2026-11-19")
        row.getJSONArray("Media").getJSONObject(0).put("videoResolution", "4k")
        container("Metadata", row)
        val item = client.children(MediaItem(501, 9, "Season 1", "season")).single()
        assertEquals(1, item.episodeNumber)
        assertEquals("2026-11-19", item.releaseDate)
        assertEquals("4K", item.resolutionLabel)
        assertEquals(server.url("/plex/library/metadata/80/thumb").toString(), item.thumbnail)
        assertEquals(server.url("/plex/library/metadata/500/thumb").toString(), item.poster)
    }

    @Test fun seasonCountIncludesSubsequentChildrenWhenServerOmitsPagingMetadata() = runBlocking {
        val first = (1..100).map { episode(1000 + it, 1, it) }.toTypedArray()
        container("Metadata", *first)
        container("Metadata", first.last(), episode(1101, 1, 101))
        val children = client.children(MediaItem(501, 9, "Season 1", "season"))
        assertEquals((1..101).toList(), children.map { it.episodeNumber })
        server.takeRequest()
        assertEquals("100", server.takeRequest().requestUrl?.queryParameter("X-Plex-Container-Start"))
    }

    @Test fun continueWatchingReadsTheMatchingHubAndSupportsOlderServers() = runBlocking {
        container("Hub",
            JSONObject().put("hubIdentifier", "home.ondeck").put("Metadata", JSONArray().put(episode(70, 1, 7))),
            JSONObject().put("hubIdentifier", "home.continue").put("Metadata", JSONArray().put(episode(80, 1, 8))))
        assertEquals(80, client.continueWatching().single().id)
        assertEquals("/plex/hubs/continueWatching", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(MockResponse().setResponseCode(404))
        container("Metadata", episode(55, 1, 5))
        assertEquals(55, client.continueWatching().single().id)
        server.takeRequest()
        assertEquals("/plex/library/onDeck", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun libraryOptionsApplyToTheServerBeforePagination() = runBlocking {
        val library = Library(9, "Series", "tv")
        val term = "서울 & 부산"
        container("Metadata", episode(70, 1, 7), total = 100)
        val result = client.page(library, 60, 12, LibraryContentType.EPISODES, LibrarySort.TITLE_DESC,
            LibraryWatchFilter.IN_PROGRESS, term)
        assertEquals(61, result.nextOffset)
        assertEquals(100, result.total)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/plex/library/sections/9/all", url.encodedPath)
        assertEquals("60", url.queryParameter("X-Plex-Container-Start"))
        assertEquals("12", url.queryParameter("X-Plex-Container-Size"))
        assertEquals("4", url.queryParameter("type"))
        assertEquals("titleSort:desc", url.queryParameter("sort"))
        assertEquals("1", url.queryParameter("inProgress"))
        assertEquals(term, url.queryParameter("title"))

        container("Metadata")
        client.page(library, limit = 12, sort = LibrarySort.LATEST_EPISODE)
        assertEquals("episode.addedAt:desc", server.takeRequest().requestUrl!!.queryParameter("sort"))
    }

    @Test fun selectedOriginalVersionSurvivesPlaybackPreparation() = runBlocking {
        val row = episode(50, 1, 2)
        val media = row.getJSONArray("Media")
        media.getJSONObject(0).put("videoResolution", "720")
        media.put(JSONObject().put("videoResolution", "4k").put("container", "mp4")
            .put("Part", JSONArray().put(JSONObject().put("id", 99).put("key", "/library/parts/99/film.mp4"))))
        row.put("Country", JSONArray().put(JSONObject().put("tag", "South Korea")))
        container("Metadata", row)
        val info = client.itemInfo(MediaItem(50, 9, "Example", "episode"))
        assertEquals(listOf("720p", "2160p"), info.versions.map { it.resolution })
        assertEquals(listOf("South Korea"), info.item.productionCountries)
        container("Metadata", row)
        val spec = client.playback(info.item, selectedPartId = 99)
        assertEquals(99, spec.partId)
        assertEquals(99, spec.sourceItem?.playbackPartId)
        assertEquals(server.url("/plex/library/parts/99/film.mp4").toString(), spec.url)
        container("Metadata", row)
        assertTrue(runCatching { client.playback(info.item, selectedPartId = 501) }.exceptionOrNull() is IOException)
    }

    @Test fun searchEncodesTheTermAndKeepsLocalVideoResultsInServerOrder() = runBlocking {
        assertTrue(client.search("  ").isEmpty())
        assertEquals(0, server.requestCount)
        val show = JSONObject("""{"ratingKey":"11","type":"show","title":"Show","key":"/library/metadata/11/children"}""")
        val movie = JSONObject("""{"ratingKey":"12","type":"movie","title":"Movie","key":"/library/metadata/12"}""")
        val remote = JSONObject("""{"ratingKey":"13","type":"movie","title":"Remote","key":"https://other.invalid/library/metadata/13"}""")
        val artist = JSONObject("""{"ratingKey":"14","type":"artist","title":"Artist"}""")
        container("Hub", JSONObject().put("Directory", JSONArray().put(show).put(artist)),
            JSONObject().put("Metadata", JSONArray().put(movie).put(remote)),
            JSONObject().put("Video", JSONArray().put(episode(55, 1, 2)).put(movie)))
        val term = "서울 & LA + 100%"
        assertEquals(listOf(11, 12, 55), client.search(" $term ").map { it.id })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/plex/hubs/search", url.encodedPath)
        assertEquals(term, url.queryParameter("query"))
        assertNull(url.queryParameter("X-Plex-Token"))
    }

    @Test fun authenticatedMediaHasNoSavedTokenAndTimelineUsesEpisodeIdentity() = runBlocking {
        val row = episode(50, 1, 2).put("Marker", JSONArray("""[{"type":"credits","startTimeOffset":500000,"endTimeOffset":550000}]"""))
        val streams = JSONArray("""[
            {"id":20,"streamType":2,"selected":true,"index":1,"codec":"aac","languageCode":"kor"},
            {"id":30,"streamType":3,"selected":true,"codec":"srt","key":"/library/streams/30?X-Plex-Token=secret","languageCode":"kor"},
            {"id":31,"streamType":3,"codec":"srt","key":"https://elsewhere.invalid/subtitle"}
        ]""")
        row.getJSONArray("Media").getJSONObject(0).getJSONArray("Part").getJSONObject(0).put("Stream", streams)
        container("Metadata", row)
        val spec = client.playback(MediaItem(50, 9, "Episode", "episode"))
        assertEquals(500, spec.viewItemId)
        assertEquals(1050, spec.partId)
        assertEquals(62000L, spec.resumeMs)
        assertEquals(600000L, spec.durationMs)
        assertEquals(1, spec.markers.size)
        assertEquals("mkv", spec.sourceContainer)
        assertEquals("hevc", spec.sourceVideoCodec)
        assertEquals("aac", spec.selectedAudioCodec)
        assertEquals(30, spec.selectedSubtitleId)
        assertEquals(server.url("/plex/library/streams/30").toString(), spec.subtitles.single().url)
        assertEquals(token, client.headersFor(spec.url)["X-Plex-Token"])
        assertTrue(client.headersFor(server.url("/outside").toString()).isEmpty())
        assertTrue(client.headersFor("https://elsewhere.invalid/path").isEmpty())
        assertFalse(spec.toJson().contains(token))
        assertFalse(spec.toJson().contains("secret"))
        assertFalse(spec.toJson().contains("X-Plex-Token"))
        server.takeRequest()
        server.enqueue(MockResponse())
        client.timeline(spec, 80000, 600000, "stopped")
        server.takeRequest().requestUrl!!.let {
            assertEquals("/plex/:/timeline", it.encodedPath)
            assertEquals("50", it.queryParameter("ratingKey"))
            assertEquals("/library/metadata/50", it.queryParameter("key"))
            assertEquals("80000", it.queryParameter("time"))
            assertEquals("stopped", it.queryParameter("state"))
            assertEquals(spec.sessionId, it.queryParameter("X-Plex-Session-Identifier"))
        }
        server.enqueue(MockResponse())
        client.setWatched(spec.itemId, true)
        assertEquals("/plex/:/scrobble", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun creditsAndContinuousPlaybackAdvanceAcrossSeasons() = runBlocking {
        val last = episode(80, 1, 10)
        container("Metadata", last)
        val spec = client.playback(MediaItem(80, 9, "Finale", "episode"))
        container("Metadata", JSONObject("""{"ratingKey":501,"type":"season","title":"Season 1","index":1}"""),
            JSONObject("""{"ratingKey":502,"type":"season","title":"Season 2","index":2}"""))
        container("Metadata", episode(79, 1, 9), last)
        container("Metadata", episode(85, 2, 1))
        val neighbors = client.neighbors(spec)
        assertEquals(79, neighbors.previous?.id)
        assertEquals(85, verifiedCreditsNextEpisode(spec, neighbors)?.id)
    }

    @Test fun autoNextUsesKnownSeasonAndKeepsIdentityWhenPlexOmitsRepeatedParentFields() = runBlocking {
        val first = episode(84, 2, 1).apply { remove("parentIndex") }
        container("Metadata", first)
        val original = client.playback(MediaItem(84, 9, "First", "episode"))
        val seasonOne = JSONObject("""{"ratingKey":501,"type":"season","title":"S1","index":1}""")
        val seasonTwo = JSONObject("""{"ratingKey":502,"type":"season","title":"S2","index":2}""")
        container("Metadata", seasonOne, seasonTwo)
        val next = episode(85, 2, 2).apply {
            remove("parentRatingKey"); remove("grandparentRatingKey"); remove("parentIndex")
        }
        container("Metadata", first, next)
        val neighbors = client.neighbors(original, includePrevious = false)
        val target = requireNotNull(continuousPlaybackTarget(true, original, neighbors))
        assertEquals(85, target.id)
        assertNull(neighbors.previous)
        assertEquals(3, server.requestCount)
        assertEquals("/plex/library/metadata/84", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/plex/library/metadata/500/children", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/plex/library/metadata/502/children", server.takeRequest().requestUrl!!.encodedPath)
        container("Metadata", next)
        val nextPlayback = client.playback(target)
        assertEquals(500, nextPlayback.viewItemId)
        assertEquals(2, nextPlayback.sourceItem?.seasonNumber)
        assertEquals(server.url("/plex/library/parts/1085/video.mkv").toString(), nextPlayback.url)
    }

    @Test fun autoNextCrossesAnEmptySeasonWithoutServingAnotherShowsEpisode() = runBlocking {
        val first = episode(80, 1, 9)
        container("Metadata", first)
        val spec = client.playback(MediaItem(80, 9, "Finale", "episode"))
        container("Metadata", *(1..3).map {
            JSONObject().put("ratingKey", 500 + it).put("type", "season").put("title", "S$it").put("index", it)
        }.toTypedArray())
        container("Metadata", first)
        container("Metadata", episode(98, 2, 1).put("grandparentRatingKey", 999))
        container("Metadata", episode(90, 3, 1))
        assertEquals(90, continuousPlaybackTarget(true, spec, client.neighbors(spec, includePrevious = false))?.id)
    }

    @Test fun rejectsRedirectsAndInvalidMultipartWithoutPlayingOnlyFirstFile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertTrue(runCatching { client.libraries() }.exceptionOrNull() is IOException)
        assertEquals(1, server.requestCount)
        val row = episode(80, 1, 10)
        val parts = row.getJSONArray("Media").getJSONObject(0).getJSONArray("Part")
        parts.put(JSONObject("""{"id":4000,"key":"/library/parts/4000/second.mkv"}"""))
        container("Metadata", row)
        assertTrue(runCatching { client.playback(MediaItem(80, 9, "Finale", "episode")) }
            .exceptionOrNull()?.message.orEmpty().contains("여러 파일"))
    }
}
