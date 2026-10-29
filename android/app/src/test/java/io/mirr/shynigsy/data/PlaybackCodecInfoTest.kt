package io.mirr.shynigsy.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackCodecInfoTest {
    private val item = MediaItem(23, 8, "movie", "movie", viewItemId = 501)
    private fun spec(url: String = "https://example.invalid/stream/90?ticket=t") = PlaybackSpec(
        "movie", url, itemId = 23, libraryId = 8, partId = 90, viewItemId = 501,
        selectedAudioCodec = "aac", sourceItem = item)
    private fun parse(json: String) = parsePlaybackCodecInfo(JSONObject(json), spec())

    @Test fun readsOnlyTheExactlySelectedPartAndPreservesObservedFields() {
        val result = parse("""{"selected_part_id":90,"item":{"id":23},"parts":[
          {"id":91,"video_codec":"wrong"},
          {"id":90,"metadata_item_id":23,"container":"mp4","resolution":"1920x1080","fps":24,
           "video_codec":"h264","video_profile":"High","video_bit_depth":8,"hdr_format":"SDR",
           "audio_codec":"aac","audio_profile":"LC","audio_channels":2,"subtitles":[{"format":"srt"}]}]}""")!!
        assertEquals(90, result.partId)
        assertEquals("mp4", result.container); assertEquals("h264", result.videoCodec)
        assertEquals("High", result.videoProfile); assertEquals(8, result.videoBitDepth)
        assertEquals("SDR", result.hdrFormat); assertEquals(1920, result.width); assertEquals(1080, result.height)
        assertEquals(24.0, result.frameRate!!, 0.0)
        assertEquals("aac", result.audioCodec); assertEquals("LC", result.audioProfile)
        assertEquals(2, result.audioChannels); assertEquals("aac", result.selectedAudioCodec)
        assertEquals(listOf("srt"), result.subtitleFormats)
    }

    @Test fun rejectsMismatchedAndAmbiguousFileIdentity() {
        assertNull(parse("""{"selected_part_id":91,"parts":[{"id":90}]}"""))
        assertNull(parse("""{"library_view_id":9,"view_item_id":501,"parts":[{"id":90}]}"""))
        assertNull(parse("""{"library_view_id":8,"view_item_id":502,"parts":[{"id":90}]}"""))
        assertNull(parse("""{"library_view_id":"invalid","parts":[{"id":90}]}"""))
        assertNull(parse("""{"view_item_id":0,"parts":[{"id":90}]}"""))
        assertNull(parse("""{"parts":[{"id":90},{"id":90}]}"""))
        assertNull(parse("""{"parts":[{"id":90,"part_id":91}]}"""))
        assertNull(parse("""{"parts":[{"id":90,"part_id":"bad"}]}"""))
        assertNull(parse("""{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":24}]}"""))
        assertNull(parse("""{"parts":[{"id":91}]}"""))
        assertNotNull(parse("""{"library_view_id":8,"view_item_id":501,"parts":[{"id":90}]}"""))
    }

    @Test fun unknownAndInconsistentDetailsRemainUnknown() {
        val result = parse("""{"parts":[{"id":90,"width":1280,"resolution":"1920x1080",
          "fps":"unknown","video_bit_depth":0,"subtitles":[{"format":null}]}]}""")!!
        assertNull(result.videoCodec); assertNull(result.container); assertNull(result.videoProfile)
        assertNull(result.videoBitDepth); assertNull(result.hdrFormat); assertNull(result.width)
        assertEquals(1080, result.height); assertNull(result.frameRate); assertNull(result.subtitleFormats)
        assertNull(parse("""{"parts":[{"id":90}]}""")!!.subtitleFormats)
        assertEquals(emptyList<String>(), parse("""{"parts":[{"id":90,"subtitles":[]}]}""")!!.subtitleFormats)
    }

    @Test fun usesTheAuthorizedExactPartAndViewWithoutChangingThePlayback() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"item":{"id":23},"parts":[{"id":90,"video_codec":"h264"}]}"""))
            val client = ShyniClient(server.url("/").toString(), "account")
            val evidence = client.playbackCodecInfo(spec(server.url("/stream/90?ticket=t").toString()))
            assertEquals("h264", evidence?.videoCodec)
            val request = server.takeRequest()
            assertEquals("/libraries/8/media_info/90", request.requestUrl!!.encodedPath)
            assertEquals("8", request.requestUrl!!.queryParameter("library_view_id"))
            assertEquals("501", request.requestUrl!!.queryParameter("view_item_id"))
        } finally { server.shutdown() }
    }

    @Test fun mismatchedOrConvertedSourcesDoNotTriggerAnyRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = ShyniClient(server.url("/").toString(), "account")
            val valid = spec(server.url("/stream/90?ticket=t").toString())
            assertNull(client.playbackCodecInfo(valid.copy(partId = 91)))
            assertNull(client.playbackCodecInfo(valid.copy(viewItemId = 502)))
            assertNull(client.playbackCodecInfo(valid.copy(url = "https://elsewhere.invalid/stream/90?ticket=t")))
            assertNull(client.playbackCodecInfo(valid.copy(url = server.url("/transcode/90?ticket=t&mode=video").toString())))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun accountChangeRejectsAResponseBeforeItCanBeUsedOrCached() = runBlocking {
        withTimeout(15_000) {
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown(); release.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"item":{"id":23},"parts":[{"id":90,"video_codec":"h264"}]}""")
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "first")
                val pending = async(Dispatchers.Default) {
                    runCatching { client.playbackCodecInfo(spec(server.url("/stream/90?ticket=t").toString())) }
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.token = "second"; release.countDown()
                    assertEquals(401, (pending.await().exceptionOrNull() as ApiException).status)
                } finally { release.countDown(); pending.cancelAndJoin() }
            } finally { release.countDown(); server.shutdown() }
        }
    }

    @Test fun privateGrantRotationRejectsOldMetadataBeforePublishing() = runBlocking {
        withTimeout(15_000) {
            SpoilerAccessRegistry.clearAll()
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_fixture","expires_in":300}""")
                    else -> {
                        arrived.countDown(); release.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody("""{"item":{"id":23},"parts":[{"id":90,"video_codec":"h264"}]}""")
                    }
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                client.unlockSpoiler("1234")
                val privateSpec = spec(server.url("/stream/90?ticket=t").toString()).copy(sourceItem = item.copy(isSpoiler = true))
                val pending = async(Dispatchers.Default) { runCatching { client.playbackCodecInfo(privateSpec) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.clearSpoilerGrant(); release.countDown()
                    assertEquals(403, (pending.await().exceptionOrNull() as ApiException).status)
                } finally { release.countDown(); pending.cancelAndJoin(); client.clearSpoilerGrant() }
            } finally { release.countDown(); SpoilerAccessRegistry.clearAll(); server.shutdown() }
        }
    }
}
