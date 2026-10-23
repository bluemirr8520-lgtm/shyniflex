package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackCompletionTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val metadata = PlaybackMetadataDispatcher()
    private val current = PlaybackSpec("작품", "https://server/stream/90", itemId = 23,
        libraryId = 8, partId = 90, viewItemId = 501,
        sourceItem = MediaItem(23, 8, "작품", "movie", viewItemId = 501))
    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { dispatcher = metadata; start() }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun respond(text: String) = server.enqueue(MockResponse().setBody(text))

    @Test fun completionMarksTheCurrentPartsActualMetadataNotMergedRepresentative() = runBlocking {
        respond("""{"item":{"id":456},"parts":[{"id":90,"metadata_item_id":456}]}""")
        respond("""{"ok":true,"watched":true}""")
        client.markPlaybackWatched(current)
        val lookup = server.takeRequest()
        assertEquals("/libraries/8/media_info/90", lookup.requestUrl!!.encodedPath)
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/continue-watching/456/watch-state", write.requestUrl!!.encodedPath)
        val data = JSONObject(write.body.readUtf8())
        assertEquals(90, data.getInt("part_id"))
        assertEquals(8, data.getInt("library_view_id"))
        assertEquals(501, data.getInt("view_item_id"))
        assertTrue(data.getBoolean("watched"))
    }

    @Test fun contradictoryMetadataFailsBeforeAnyCompletionWrite() = runBlocking {
        respond("""{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":456}]}""")
        assertTrue(runCatching { client.markPlaybackWatched(current) }.exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
    }

    @Test fun absentSelectedPartFailsBeforeAnyCompletionWrite() = runBlocking {
        respond("""{"item":{"id":23},"parts":[{"id":91,"metadata_item_id":23}]}""")
        assertTrue(runCatching { client.markPlaybackWatched(current) }.exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
    }

    @Test fun markerRefreshSharesBoundedPartIdentityCacheWithCompletion() = runBlocking {
        respond("""{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":23}],"markers":[]}""")
        respond("""{"ok":true,"watched":true}""")
        client.playbackMarkers(current)
        client.markPlaybackWatched(current)
        assertEquals(2, server.requestCount)
    }

    @Test fun navigationPinsThePhysicallyVerifiedPartAndRejectsServerSubstitution() = runBlocking {
        respond("""{"selected":{"part_id":99},"library_view_id":8,"view_item_id":501}""")
        val item = current.sourceItem!!.copy(navigationPartId = 91, folderKey = "opaque-folder-hash")
        assertTrue(runCatching { client.playback(item) }.exceptionOrNull() is ApiException)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(91, body.getInt("part_id"))
        assertEquals(1, server.requestCount)
    }

    @Test fun successfulNavigationKeepsPinnedPartAcrossDecisionAndPlaybackState() = runBlocking {
        respond("""{"selected":{"part_id":91},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
        respond("""{"ticket":"stream-ticket"}""")
        metadata.enqueueMetadata("/subtitles/91", MockResponse().setBody("[]"))
        metadata.enqueueMetadata("/playback/progress", MockResponse().setBody("{}"))
        val result = client.playback(current.sourceItem!!.copy(navigationPartId = 91, folderKey = "folder-hash"))
        assertEquals(91, result.partId)
        assertEquals(91, result.requestedPartId)
        assertEquals(91, result.sourceItem!!.navigationPartId)
        assertEquals("folder-hash", result.sourceItem!!.folderKey)
        assertEquals(91, JSONObject(server.takeRequest().body.readUtf8()).getInt("part_id"))
    }

    @Test fun privateCompletionRequiresCurrentEphemeralGrantEvenWhenPartInfoWasCached() = runBlocking {
        respond("""{"token":"spc_ephemeral","expires_in":300}""")
        client.unlockSpoiler("1234")
        server.takeRequest()
        val privatePlayback = current.copy(sourceItem = current.sourceItem!!.copy(isSpoiler = true))
        respond("""{"item":{"id":456},"parts":[{"id":90,"metadata_item_id":456}]}""")
        respond("""{"ok":true,"watched":true}""")
        client.markPlaybackWatched(privatePlayback)
        val lookup = server.takeRequest()
        val write = server.takeRequest()
        assertEquals("spc_ephemeral", lookup.getHeader("X-Shyni-Spoiler"))
        assertEquals("spc_ephemeral", write.getHeader("X-Shyni-Spoiler"))
        assertEquals("/continue-watching/456/watch-state", write.requestUrl!!.encodedPath)
        assertFalse(privatePlayback.toJson().contains("spc_ephemeral"))
        client.clearSpoilerGrant()
        assertEquals(403, (runCatching { client.markPlaybackWatched(privatePlayback) }.exceptionOrNull() as ApiException).status)
        assertEquals(3, server.requestCount)
    }

    @Test fun navigationAndOpaqueFolderIdentitySurvivePlaybackRoundtrip() {
        val source = current.sourceItem!!.copy(navigationPartId = 91, folderKey = "opaque-folder-hash")
        val spec = current.copy(sourceItem = source)
        assertEquals(spec, PlaybackSpec.fromJson(spec.toJson()))
    }
}
