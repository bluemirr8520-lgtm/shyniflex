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

class MediaFileInfoTest {
    private val item = MediaItem(23, 8, "제목", "movie", viewItemId = 501)
    private val minimal = """{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":23,"video_codec":"hevc"}]}"""
    private fun parse(value: String, selected: Int? = null) = parseMediaFileInfo(JSONObject(value), item, selected)

    @Test fun preservesEachFileAndTracksButNeverTheirPathsOrUrls() {
        val files = parse("""{"item":{"id":23},"parts":[
            {"id":90,"metadata_item_id":23,"file_path":"/private/videos/영화.mkv","file_size":4294967296,
             "container":"mkv","video_codec":"hevc","video_profile":"Main 10","video_bit_depth":10,
             "resolution":"3840x2160","fps":23.976,"hdr_format":"HDR10","bitrate_bps":25000000,
             "audio_codec":"eac3","audio_channels":6,
             "audio_tracks":[{"codec":"eac3","lang_code":"ko","channels":6,"is_default":true}],
             "subtitles":[{"lang_code":"ko","format":"ass","source":"embedded","forced":false,"sdh":true},
                          {"lang_code":"en","format":"srt","source":"external","file_path":"/secret/subtitle.srt","url":"https://private.invalid/token"}]},
            {"id":91,"metadata_item_id":23,"file_path":"C:\\videos\\second.mp4","subtitles":[]}]}""")
        assertEquals(listOf(90, 91), files.map { it.partId })
        val first = files.first()
        assertEquals("영화.mkv", first.fileName); assertEquals("second.mp4", files.last().fileName)
        assertEquals("3840x2160", first.resolution); assertEquals("HEVC", first.videoCodec!!.uppercase())
        assertEquals(4_294_967_296, first.fileSizeBytes); assertEquals(10, first.videoBitDepth)
        assertEquals(23.976, first.frameRate!!, 0.0001); assertEquals("HDR10", first.hdrFormat)
        assertEquals(6, first.audioTracks!!.single().channels)
        assertEquals(listOf("ko", "en"), first.subtitles!!.map { it.language })
        assertEquals(listOf("embedded", "external"), first.subtitles!!.map { it.source })
        assertFalse(files.toString().contains("/private")); assertFalse(files.toString().contains("/secret"))
        assertFalse(files.toString().contains("https://")); assertFalse(files.toString().contains("C:\\"))
    }

    @Test fun distinguishesMissingAndMalformedTrackListsFromReportedEmptyLists() {
        assertNull(parse(minimal).single().subtitles)
        val absent = parse("""{"parts":[{"id":90,"subtitles":null,"audio_tracks":"unknown"}]}""").single()
        assertNull(absent.subtitles); assertNull(absent.audioTracks)
        val malformed = parse("""{"parts":[{"id":90,"subtitles":[{} ,null],"audio_tracks":["bad"]}]}""").single()
        assertNull(malformed.subtitles); assertNull(malformed.audioTracks)
        val empty = parse("""{"parts":[{"id":90,"subtitles":[],"audio_tracks":[]}]}""").single()
        assertEquals(emptyList<MediaFileSubtitle>(), empty.subtitles)
        assertEquals(emptyList<MediaFileAudio>(), empty.audioTracks)
    }

    @Test fun invalidNumericAndUntrustedTextRemainUnknown() {
        val file = parse("""{"parts":[{"id":90,"file_path":"https://private.invalid/video?secret=value",
            "video_codec":"https://private.invalid/codec","container":"mkv\nsecret","file_size":-1,
            "bitrate_bps":"9223372036854775808","video_bit_depth":0,"audio_channels":99,"fps":"NaN",
            "width":1920,"height":1080}]}""").single()
        assertNull(file.fileName); assertNull(file.videoCodec); assertNull(file.container)
        assertNull(file.fileSizeBytes); assertNull(file.bitrateBps); assertNull(file.videoBitDepth)
        assertNull(file.audioChannels); assertNull(file.frameRate); assertEquals("1920×1080", file.resolution)
    }

    @Test fun rejectsMismatchedOrAmbiguousIdentityInsteadOfShowingAnotherFile() {
        val cases = listOf(
            """{"library_view_id":9,"parts":[{"id":90}]}""",
            """{"view_item_id":502,"parts":[{"id":90}]}""",
            """{"view_item_id":"bad","parts":[{"id":90}]}""",
            """{"item":{"id":24},"parts":[{"id":90}]}""",
            """{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":24}]}""",
            """{"parts":[{"id":90,"part_id":91}]}""",
            """{"parts":[{"id":90},{"id":90}]}""",
            """{"parts":[{"id":90.5}]}""",
            """{"parts":[{"id":90,"remote":true}]}""",
        )
        for (value in cases) assertTrue(value, runCatching { parse(value) }.exceptionOrNull() is ApiException)
        assertTrue(runCatching { parse("""{"parts":[{"id":91}]}""", 90) }.isFailure)
        assertTrue(runCatching { parse("""{"selected_part_id":91,"parts":[{"id":90}]}""", 90) }.isFailure)
    }

    @Test fun selectedVersionMayHaveAnotherPhysicalMetadataIdWithinTheAuthorizedView() {
        val result = parse("""{"library_view_id":8,"view_item_id":501,"selected_part_id":90,
            "item":{"id":24},"parts":[{"id":90,"metadata_item_id":24},{"id":91,"metadata_item_id":24}]}""", 90)
        assertEquals(listOf(90), result.map { it.partId })
    }

    @Test fun usesOneReadOnlyMetadataRequestAndCachesOnlyThatViewContext() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(minimal))
            server.enqueue(MockResponse().setBody(minimal))
            server.enqueue(MockResponse().setBody(minimal))
            val client = ShyniClient(server.url("/").toString(), "account")
            assertEquals(client.detailFileInfo(item), client.detailFileInfo(item))
            assertEquals(1, server.requestCount)
            var request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/libraries/8/media_info/item/23", request.requestUrl!!.encodedPath)
            assertEquals("8", request.requestUrl!!.queryParameter("library_view_id"))
            assertEquals("501", request.requestUrl!!.queryParameter("view_item_id"))
            assertNull(request.requestUrl!!.queryParameter("admin"))
            client.detailFileInfo(item.copy(viewItemId = 502))
            request = server.takeRequest()
            assertEquals("502", request.requestUrl!!.queryParameter("view_item_id"))
            assertEquals(listOf(90), client.detailFileInfo(item, 90).map { it.partId })
            request = server.takeRequest()
            assertEquals("/libraries/8/media_info/90", request.requestUrl!!.encodedPath)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun invalidTargetsAndLockedPrivateItemsDoNotCauseAnyNetworkRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = ShyniClient(server.url("/").toString(), "account")
            for (target in listOf(item.copy(type = "show"), item.copy(type = "season"), item.copy(viewItemId = null),
                    item.copy(id = 0), item.copy(libraryId = 0), item.copy(isSpoiler = true))) {
                assertTrue(runCatching { client.detailFileInfo(target) }.isFailure)
            }
            assertTrue(runCatching { client.detailFileInfo(item, -1) }.isFailure)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun failedParsingDoesNotPoisonTheRetryCache() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"parts":[{"id":90},{"id":90}]}"""))
            server.enqueue(MockResponse().setBody(minimal))
            val client = ShyniClient(server.url("/").toString(), "account")
            assertTrue(runCatching { client.detailFileInfo(item) }.isFailure)
            assertEquals(1, client.detailFileInfo(item).size)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun accountChangeRejectsAnInflightResponseBeforePublicationOrCaching() = runBlocking {
        withTimeout(15_000) {
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown(); release.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody(minimal)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "first")
                val pending = async(Dispatchers.Default) { runCatching { client.detailFileInfo(item) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.token = "second"; release.countDown()
                    assertEquals(401, (pending.await().exceptionOrNull() as ApiException).status)
                    assertEquals(1, client.detailFileInfo(item).size)
                    assertEquals(2, server.requestCount)
                } finally { release.countDown(); pending.cancelAndJoin() }
            } finally { release.countDown(); server.shutdown() }
        }
    }

    @Test fun lockingAfterACachedPrivateResponseCannotReturnThatCache() = runBlocking {
        SpoilerAccessRegistry.clearAll()
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"token":"spc_fixture","expires_in":300}"""))
            server.enqueue(MockResponse().setBody(minimal))
            val client = ShyniClient(server.url("/").toString(), "account")
            client.unlockSpoiler("1234")
            val target = item.copy(isSpoiler = true)
            assertEquals(1, client.detailFileInfo(target).size)
            assertEquals(1, client.detailFileInfo(target).size)
            assertEquals(2, server.requestCount)
            client.clearSpoilerGrant()
            assertEquals(403, (runCatching { client.detailFileInfo(target) }.exceptionOrNull() as ApiException).status)
            assertEquals(2, server.requestCount)
        } finally { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    }
}
