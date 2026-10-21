package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
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
        assertEquals(1, server.requestCount)
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
