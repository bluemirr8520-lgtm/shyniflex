package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackSubtitleInspectionTest {
    private fun track(id: Int) = SubtitleTrack(id, "subtitle", "https://example.invalid/sub", source = "external")

    @Test fun acceptsExplicitExternalTextAndValidEmptyResponse() {
        val raw = JSONArray("""[{"id":4,"source":"external","format":"srt","playable":true},
            {"id":5,"source":"external","format":"ASS"}]""")
        assertTrue(areRawSubtitlesSafeForExo(raw, listOf(track(4), track(5))))
        assertTrue(areRawSubtitlesSafeForExo(JSONObject("""{"subtitles":[]}"""), emptyList()))
    }

    @Test fun rejectsHiddenUnsupportedAndUnknownRowsEvenWhenPlayableListIsEmpty() {
        listOf(
            """[{"id":4,"source":"external","format":"pgs"}]""",
            """[{"id":4,"source":"embedded","format":"srt"}]""",
            """[{"id":4,"source":"external","format":"srt","playable":false}]""",
            """[{"id":4,"format":"srt"}]""",
            """[{"id":4,"source":"external"}]""",
            """[null]""",
        ).forEach { assertFalse(areRawSubtitlesSafeForExo(JSONArray(it), emptyList())) }
    }

    @Test fun rejectsMalformedResponsesDuplicatesAndRowsThatWereDiscarded() {
        assertFalse(areRawSubtitlesSafeForExo(JSONObject("{}"), emptyList()))
        assertFalse(areRawSubtitlesSafeForExo(JSONObject("""{"subtitles":null}"""), emptyList()))
        assertFalse(areRawSubtitlesSafeForExo(JSONObject("""{"subtitles":[],"items":[]}"""), emptyList()))
        val duplicate = JSONArray("""[{"id":4,"source":"external","format":"srt"},
            {"id":4,"source":"external","format":"ass"}]""")
        assertFalse(areRawSubtitlesSafeForExo(duplicate, listOf(track(4), track(4))))
        assertFalse(areRawSubtitlesSafeForExo(duplicate, listOf(track(4))))
    }

    @Test fun permitsOnlyOneIdentifiedMovTextWithExactStreamIndex() {
        val embedded = SubtitleTrack(7, "embedded", "https://example.invalid/sub",
            source = "embedded", streamIndex = 4, format = "mov_text")
        val valid = JSONArray("""[{"id":7,"source":"embedded","format":"mov_text","stream_index":4}]""")
        assertTrue(areRawSubtitlesSafeForExo(valid, listOf(embedded)))
        assertFalse(areRawSubtitlesSafeForExo(valid, listOf(embedded.copy(streamIndex = 5))))
        assertFalse(areRawSubtitlesSafeForExo(JSONArray("""[{"id":7,"source":"embedded","format":"mov_text"}]"""), listOf(embedded)))
        assertFalse(areRawSubtitlesSafeForExo(JSONArray("""[{"id":7,"source":"embedded","format":"mov_text","stream_index":-1}]"""), listOf(embedded)))
        assertFalse(areRawSubtitlesSafeForExo(JSONArray("""[{"id":7,"source":"embedded","format":"srt","stream_index":4}]"""), listOf(embedded)))
        assertFalse(areRawSubtitlesSafeForExo(JSONArray("""[{"id":7,"source":"embedded","format":"mov_text","stream_index":4},
            {"id":8,"source":"embedded","format":"tx3g","stream_index":5}]"""),
            listOf(embedded, embedded.copy(id = 8, streamIndex = 5, format = "tx3g"))))
    }

    @Test fun inspectionReusesOneAuthorizedRequestAndLeavesOrdinaryContractIntact() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"id":4,"source":"external","format":"srt","lang_code":"ko"}]"""))
            val client = ShyniClient(server.url("/").toString(), "account")
            val item = MediaItem(23, 8, "movie", "movie", viewItemId = 501)
            val spec = PlaybackSpec("movie", server.url("/stream/90?ticket=original").toString(),
                itemId = 23, libraryId = 8, partId = 90, viewItemId = 501, sessionId = "session", sourceItem = item)
            val inspected = client.playbackSubtitlesForEngine(spec)
            assertTrue(inspected.safeForExo); assertEquals(listOf(4), inspected.tracks.map { it.id })
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/subtitles/90", request.requestUrl!!.encodedPath)
            assertEquals("8", request.requestUrl!!.queryParameter("library_view_id"))
            assertEquals("501", request.requestUrl!!.queryParameter("view_item_id"))
            assertEquals("original", inspected.tracks.single().url.toHttpUrl().queryParameter("ticket"))
            assertTrue(runCatching { client.playbackSubtitles(spec) }.isFailure)
            assertTrue(runCatching { client.playbackSubtitlesForEngine(spec.copy(partId = 91)) }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun serverMovTextKeepsTheExactStreamIndexForNativeCrossCheck() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"id":7,"source":"embedded","format":"mov_text","stream_index":4}]"""))
            val client = ShyniClient(server.url("/").toString(), "account")
            val item = MediaItem(23, 8, "movie", "movie", viewItemId = 501)
            val spec = PlaybackSpec("movie", server.url("/stream/90?ticket=original").toString(),
                itemId = 23, libraryId = 8, partId = 90, viewItemId = 501, sessionId = "session", sourceItem = item)
            val inspected = client.playbackSubtitlesForEngine(spec)
            assertTrue(inspected.safeForExo)
            assertEquals(4, inspected.tracks.single().streamIndex)
            assertEquals("embedded", inspected.tracks.single().source)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
