package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackNeighborsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val responses = mutableMapOf<String, String>()
    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = responses[request.requestUrl!!.encodedPath]
                    ?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404).setBody("{}")
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun spec(item: MediaItem, query: String = "") = PlaybackSpec("작품", "source", itemId = item.id,
        libraryId = item.libraryId, partId = 90, sourceItem = item, navigationQuery = query, viewItemId = item.viewItemId)
    private fun info(id: Int, part: Int, path: String) = """{"item":{"id":$id},"parts":[{"id":$part,"metadata_item_id":$id,"file_path":"$path"}]}"""
    private fun episode(season: Int? = 1, number: Int? = 1, privateItem: Boolean = false) = MediaItem(
        23, 9, "합성 현재 회차", "episode", showId = 5, seasonNumber = season, episodeNumber = number,
        viewItemId = 601, isSpoiler = privateItem)
    private fun splitEpisodeInfo() = """{"item":{"id":23},"parts":[
        {"id":89,"metadata_item_id":23,"part_index":1,"file_path":"/fixture/series/episode-a.mp4"},
        {"id":90,"metadata_item_id":23,"part_index":2,"file_path":"/fixture/series/episode-b.mp4"},
        {"id":91,"metadata_item_id":23,"part_index":3,"file_path":"/fixture/series/episode-c.mp4"}]}"""

    @Test fun onlyVerifiedSameFolderFilesBecomePinnedNeighbors() = runBlocking {
        responses["/libraries/8/media_info/90"] = info(23, 90, "/media/pack/02.mp4")
        responses["/libraries/8/movies"] = """[{"id":21,"view_item_id":499},{"id":22,"view_item_id":500},{"id":23,"view_item_id":501},{"id":24,"view_item_id":502}]"""
        responses["/libraries/8/media_info/item/21"] = info(21, 87, "/media/pack/01.mp4")
        responses["/libraries/8/media_info/item/22"] = info(22, 88, "/media/different/01.mp4")
        responses["/libraries/8/media_info/item/24"] = info(24, 91, "/media/pack/03.mp4")
        val adjacent = client.playbackNeighbors(spec(MediaItem(23, 8, "현재", "movie", viewItemId = 501)))
        assertEquals(21, adjacent.previous!!.id)
        assertEquals(87, adjacent.previous!!.navigationPartId)
        assertEquals(24, adjacent.next!!.id)
        assertEquals(91, adjacent.next!!.navigationPartId)
        assertNotNull(adjacent.previous!!.folderKey)
        assertEquals(adjacent.previous!!.folderKey, adjacent.next!!.folderKey)
        assertFalse(adjacent.next!!.toJson().contains("/media/pack"))
    }

    @Test fun unavailablePhysicalPathDisablesBothWithoutGuessingFromSeasonOrTitle() = runBlocking {
        responses["/libraries/9/media_info/90"] = """{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":23}]}"""
        val current = MediaItem(23, 9, "같은 제목", "episode", showId = 5, seasonNumber = 1, episodeNumber = 2, viewItemId = 601)
        assertEquals(PlaybackNeighbors(), client.playbackNeighbors(spec(current)))
        assertEquals(1, server.requestCount)
    }

    @Test fun matchingSeasonNumbersNeverProveASharedPhysicalFolder() = runBlocking {
        responses["/libraries/9/media_info/90"] = info(23, 90, "/actual-a/current.mp4")
        responses["/libraries/9/shows/5/episodes"] = """[{"id":22,"season_number":1,"episode_number":1},{"id":23,"season_number":1,"episode_number":2},{"id":24,"season_number":1,"episode_number":3}]"""
        responses["/libraries/9/media_info/item/22"] = info(22, 89, "/actual-b/previous.mp4")
        responses["/libraries/9/media_info/item/24"] = """{"item":{"id":24},"parts":[{"id":91,"metadata_item_id":24}]}"""
        val current = MediaItem(23, 9, "현재", "episode", showId = 5, seasonNumber = 1, episodeNumber = 2, viewItemId = 601)
        assertEquals(PlaybackNeighbors(), client.playbackNeighbors(spec(current)))
    }

    @Test fun differentSeasonMetadataIsAllowedOnlyWhenTheActualFolderMatches() = runBlocking {
        responses["/libraries/9/media_info/90"] = info(23, 90, "/all-episodes/current.mp4")
        responses["/libraries/9/shows/5/episodes"] = """[{"id":23,"season_number":1,"episode_number":2},{"id":24,"season_number":2,"episode_number":1}]"""
        responses["/libraries/9/media_info/item/24"] = info(24, 91, "/all-episodes/next.mp4")
        val current = MediaItem(23, 9, "현재", "episode", showId = 5, seasonNumber = 1, episodeNumber = 2, viewItemId = 601)
        val adjacent = client.playbackNeighbors(spec(current))
        assertNull(adjacent.previous)
        assertEquals(91, adjacent.next!!.navigationPartId)
    }

    @Test fun ambiguousSelectedPartMetadataDisablesBothBeforeCatalogRequests() = runBlocking {
        responses["/libraries/8/media_info/90"] = """{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":999,"file_path":"/same/file.mp4"}]}"""
        assertEquals(PlaybackNeighbors(), client.playbackNeighbors(spec(MediaItem(23, 8, "현재", "movie", viewItemId = 501))))
        assertEquals(1, server.requestCount)
    }

    @Test fun splitPartsWithinOneActualFolderNeedNoWholeLibraryScan() = runBlocking {
        responses["/libraries/8/media_info/90"] = """{"item":{"id":23},"parts":[
            {"id":89,"metadata_item_id":23,"part_index":1,"file_path":"/same/01.mp4"},
            {"id":90,"metadata_item_id":23,"part_index":2,"file_path":"/same/02.mp4"},
            {"id":91,"metadata_item_id":23,"part_index":3,"file_path":"/same/03.mp4"}]}"""
        val adjacent = client.playbackNeighbors(spec(MediaItem(23, 8, "현재", "movie", viewItemId = 501)))
        assertEquals(89, adjacent.previous!!.navigationPartId)
        assertEquals(91, adjacent.next!!.navigationPartId)
        assertNull(adjacent.nextEpisode)
        assertEquals(1, server.requestCount)
    }

    @Test fun creditsNextEpisodeExcludesOtherPartsAndVersionsOfTheCurrentEpisode() = runBlocking {
        responses["/libraries/9/media_info/90"] = splitEpisodeInfo()
        responses["/libraries/9/shows/5/episodes"] = """[
            {"id":23,"season_number":1,"episode_number":1},
            {"id":24,"season_number":1,"episode_number":1},
            {"id":25,"season_number":1,"episode_number":2}]"""
        responses["/libraries/9/media_info/item/25"] = info(25, 95, "/fixture/series/episode-next.mp4")
        val adjacent = client.playbackNeighbors(spec(episode().copy(showTitle = "현재 작품명")))
        assertEquals(89, adjacent.previous!!.navigationPartId)
        assertEquals(91, adjacent.next!!.navigationPartId)
        assertEquals(25, adjacent.nextEpisode!!.id)
        assertEquals(95, adjacent.nextEpisode!!.navigationPartId)
        assertEquals(95, adjacent.nextEpisode!!.playbackPartId)
        assertEquals(5, adjacent.nextEpisode!!.showId)
        assertEquals(601, adjacent.nextEpisode!!.viewItemId)
        assertEquals("현재 작품명", adjacent.nextEpisode!!.showTitle)
        val restored = PlaybackSpec.fromJson(spec(adjacent.nextEpisode!!).toJson())
        assertEquals("현재 작품명", restored.controllerTitle)
        assertEquals(25, restored.itemId)
        assertEquals(95, restored.sourceItem!!.navigationPartId)
        assertEquals(adjacent.next!!.folderKey, adjacent.nextEpisode!!.folderKey)
        assertFalse(adjacent.nextEpisode!!.toJson().contains("/fixture/series"))
        assertEquals(3, server.requestCount)
    }

    @Test fun aVerifiedGenericEpisodeNeighborIsReusedWithoutAnotherMetadataRequest() = runBlocking {
        responses["/libraries/9/media_info/90"] = info(23, 90, "/fixture/series/current.mp4")
        responses["/libraries/9/shows/5/episodes"] = """[
            {"id":23,"season_number":1,"episode_number":1},
            {"id":25,"title":"다음 회차 제목","show_title":"명시된 작품명","season_number":1,"episode_number":2}]"""
        responses["/libraries/9/media_info/item/25"] = info(25, 95, "/fixture/series/next.mp4")
        val adjacent = client.playbackNeighbors(spec(episode().copy(showTitle = "현재 작품명")))
        assertEquals(adjacent.next, adjacent.nextEpisode)
        assertEquals(95, adjacent.nextEpisode!!.navigationPartId)
        assertEquals("명시된 작품명", adjacent.nextEpisode!!.showTitle)
        assertEquals("다음 회차 제목", adjacent.nextEpisode!!.title)
        assertEquals("명시된 작품명", spec(adjacent.nextEpisode!!).controllerTitle)
        assertEquals(3, server.requestCount)
    }

    @Test fun nextEpisodeNeedsTheSameShowViewFolderAndExactCandidateOwnership() = runBlocking {
        for (invalid in listOf("folder", "owner", "show", "view", "private", "source-position")) {
            client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
            responses.clear()
            responses["/libraries/9/media_info/90"] = splitEpisodeInfo()
            val sourceSeason = if (invalid == "source-position") 2 else 1
            val extra = when (invalid) {
                "show" -> ",\"show_id\":99"
                "view" -> ",\"view_item_id\":999"
                "private" -> ",\"spoiler_enabled\":true"
                else -> ""
            }
            responses["/libraries/9/shows/5/episodes"] = """[
                {"id":23,"season_number":$sourceSeason,"episode_number":1},
                {"id":25,"season_number":1,"episode_number":2$extra}]"""
            responses["/libraries/9/media_info/item/25"] = when (invalid) {
                "folder" -> info(25, 95, "/fixture/another-series/next.mp4")
                "owner" -> info(99, 95, "/fixture/series/next.mp4")
                else -> info(25, 95, "/fixture/series/next.mp4")
            }
            val adjacent = client.playbackNeighbors(spec(episode()))
            assertEquals(invalid, 89, adjacent.previous!!.navigationPartId)
            assertEquals(invalid, 91, adjacent.next!!.navigationPartId)
            assertNull(invalid, adjacent.nextEpisode)
        }
    }

    @Test fun unknownEpisodeNumbersCannotPromoteAnAlternatePartButZeroIsValid() = runBlocking {
        responses["/libraries/9/media_info/90"] = splitEpisodeInfo()
        for (current in listOf(episode(null, 1), episode(1, null))) {
            client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
            val before = server.requestCount
            val adjacent = client.playbackNeighbors(spec(current))
            assertEquals(91, adjacent.next!!.navigationPartId)
            assertNull(adjacent.nextEpisode)
            // A missing card number now triggers a catalogue lookup. A 404 still
            // cannot turn another part of this same episode into the next episode.
            assertEquals(before + 2, server.requestCount)
        }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
        responses["/libraries/9/shows/5/episodes"] = """[
            {"id":23,"season_number":0,"episode_number":0},
            {"id":25,"season_number":0,"episode_number":1}]"""
        responses["/libraries/9/media_info/item/25"] = info(25, 95, "/fixture/series/next.mp4")
        assertEquals(95, client.playbackNeighbors(spec(episode(0, 0))).nextEpisode!!.navigationPartId)
    }

    @Test fun missingContinueCardNumbersAreRecoveredFromExactSeriesEpisode() = runBlocking {
        responses["/libraries/9/media_info/90"] = info(23, 90, "/fixture/series/current.mp4")
        responses["/libraries/9/shows/5/episodes"] = """[
            {"id":23,"season_number":1,"episode_number":6},
            {"id":25,"season_number":1,"episode_number":7}]"""
        responses["/libraries/9/media_info/item/25"] = info(25, 95, "/fixture/series/next.mp4")
        for (current in listOf(episode(null, null), episode(1, null), episode(null, 6))) {
            client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
            val found = client.playbackNeighbors(spec(current))
            assertEquals(95, found.nextEpisode?.navigationPartId)
            assertEquals(23, found.currentEpisode?.id)
            assertEquals(1, found.currentEpisode?.seasonNumber)
            assertEquals(6, found.currentEpisode?.episodeNumber)
            assertEquals(601, found.currentEpisode?.viewItemId)
        }
    }

    @Test fun missingNumbersNeverUseConflictingOrAmbiguousEpisodeAnchors() = runBlocking {
        responses["/libraries/9/media_info/90"] = info(23, 90, "/fixture/series/current.mp4")
        responses["/libraries/9/media_info/item/25"] = info(25, 95, "/fixture/series/next.mp4")
        val cases = listOf(
            """{"id":23,"season_number":2,"episode_number":6}""" to episode(1, null),
            """{"id":23,"season_number":1,"episode_number":6,"show_id":99}""" to episode(null, null),
            """{"id":23,"season_number":1,"episode_number":6,"view_item_id":999}""" to episode(null, null),
            """{"id":23,"season_number":1,"episode_number":6,"library_view_id":99}""" to episode(null, null),
            """{"id":23,"season_number":1,"episode_number":6}, {"id":23,"season_number":1,"episode_number":6}""" to episode(null, null),
            """{"id":23,"season_number":1,"episode_number":6}, {"id":23,"season_number":1,"episode_number":6,"view_item_id":999}""" to episode(null, null),
        )
        for ((anchor, current) in cases) {
            client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
            responses["/libraries/9/shows/5/episodes"] = """[$anchor,{"id":25,"season_number":1,"episode_number":7}]"""
            val found = client.playbackNeighbors(spec(current))
            assertNull(anchor, found.nextEpisode)
            assertNull(anchor, found.currentEpisode)
        }
    }

    @Test fun anUnavailableOptionalEpisodeCatalogueRetainsBothGenericSplitParts() = runBlocking {
        responses["/libraries/9/media_info/90"] = splitEpisodeInfo()
        // The fixture returns 404 for the optional show catalogue.
        val adjacent = client.playbackNeighbors(spec(episode()))
        assertEquals(89, adjacent.previous!!.navigationPartId)
        assertEquals(91, adjacent.next!!.navigationPartId)
        assertNull(adjacent.nextEpisode)
        assertEquals(2, server.requestCount)
    }

    @Test fun nextEpisodeMetadataChecksAreBoundedEvenWhenEveryLaterFolderDiffers() = runBlocking {
        responses["/libraries/9/media_info/90"] = splitEpisodeInfo()
        responses["/libraries/9/shows/5/episodes"] = (23..43).joinToString(prefix = "[", postfix = "]") {
            """{"id":$it,"season_number":1,"episode_number":${it - 22}}"""
        }
        (24..43).forEach { responses["/libraries/9/media_info/item/$it"] = info(it, it + 100, "/fixture/other/$it.mp4") }
        val adjacent = client.playbackNeighbors(spec(episode()))
        assertEquals(91, adjacent.next!!.navigationPartId)
        assertNull(adjacent.nextEpisode)
        assertEquals(10, server.requestCount)
    }

    @Test fun privateRevocationDuringTheOptionalCatalogueCannotPublishNeighbors() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_fixture-grant","expires_in":1800}""")
                "/libraries/9/media_info/90" -> MockResponse().setBody(splitEpisodeInfo())
                "/libraries/9/shows/5/episodes" -> {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    MockResponse().setBody("""[{"id":23,"season_number":1,"episode_number":1},
                        {"id":25,"season_number":1,"episode_number":2}]""")
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        client.unlockSpoiler("1234")
        val pending = async(Dispatchers.Default) { runCatching { client.playbackNeighbors(spec(episode(privateItem = true))) }.exceptionOrNull() }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            SpoilerAccessRegistry.clearAll()
            release.countDown()
            val failure = pending.await()
            assertTrue(failure is ApiException)
            assertEquals(403, (failure as ApiException).status)
            assertFalse(client.spoilerUnlocked)
            assertEquals(3, server.requestCount)
        } finally { release.countDown() }
    }

    @Test fun currentPartInfoCacheAndSearchMembershipArePreserved() = runBlocking {
        responses["/libraries/8/media_info/90"] = info(23, 90, "/same/current.mp4")
        responses["/libraries/8/search"] = """[{"id":23,"view_item_id":501}]"""
        val current = spec(MediaItem(23, 8, "현재", "movie", viewItemId = 501), "moon & star")
        client.playbackMarkers(current)
        assertEquals(PlaybackNeighbors(), client.playbackNeighbors(current))
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("moon & star", server.takeRequest().requestUrl!!.queryParameter("q"))
    }

    @Test fun candidateMetadataChecksAreBounded() = runBlocking {
        responses["/libraries/8/media_info/90"] = info(100, 90, "/media/current/file.mp4")
        responses["/libraries/8/movies"] = (100..120).joinToString(prefix = "[", postfix = "]") { """{"id":$it,"view_item_id":${it + 1000}}""" }
        (101..120).forEach { responses["/libraries/8/media_info/item/$it"] = info(it, it, "/media/different/$it.mp4") }
        assertEquals(PlaybackNeighbors(), client.playbackNeighbors(spec(MediaItem(100, 8, "현재", "movie", viewItemId = 1100))))
        assertEquals(10, server.requestCount)
    }

    @Test fun olderPlaybackWithoutSourceContextDoesNotGuessACatalog() = runBlocking {
        assertEquals(PlaybackNeighbors(), client.playbackNeighbors(PlaybackSpec("작품", "source", itemId = 23, libraryId = 8, partId = 90)))
        assertEquals(0, server.requestCount)
    }
}
