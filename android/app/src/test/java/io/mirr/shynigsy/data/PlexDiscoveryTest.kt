package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexDiscoveryTest {
    @Test fun rolesAndFileInformationUsePmsIdentifiersAndFileBasenames() {
        val client = PlexClient("https://media.example/pms", "test-token", "test-device")
        val row = JSONObject("""{
            "Role":[
                {"id":"19","tag":"Person","role":"Lead","thumb":"https://metadata-static.plex.tv/people/19.jpg"},
                {"id":"19","tag":"Person"},
                {"tag":"Guest","thumb":"https://unrelated.example/photo"}
            ],
            "Media":[{"width":1920,"height":1080,"bitrate":7000,"videoCodec":"h264","Part":[{
                "id":7,"file":"/library/private/folder/movie.mkv","container":"mkv","size":8096,"Stream":[
                    {"streamType":1,"bitDepth":10},{"streamType":2,"language":"Korean","channels":6},
                    {"streamType":3,"language":"English","codec":"srt","key":"/library/streams/14","forced":1}
                ]}]}]
        }""")
        val cast = plexCast(row, client)
        assertEquals(2, cast.size)
        assertEquals("19", cast.first().ppkey)
        assertTrue(cast.first().portrait.orEmpty().startsWith("https://media.example/pms/photo/:/transcode?"))
        assertFalse(cast.last().canLoadWorks)
        assertNull(cast.last().portrait)
        val file = plexFiles(row).single()
        assertEquals("movie.mkv", file.fileName)
        assertEquals("1920×1080", file.resolution)
        assertEquals(7_000_000L, file.bitrateBps)
        assertEquals(6, file.audioTracks!!.single().channels)
        assertEquals(true, file.subtitles!!.single().forced)
    }

    @Test fun actorWorksUseAuthorizedLibraryFiltersAndKeepPartialResults() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = PlexClient(server.url("/").toString(), "test-token", "test-device")
            server.enqueue(MockResponse().setBody("""{"MediaContainer":{"Metadata":[
                {"type":"movie","ratingKey":"31","title":"A","librarySectionID":4},
                {"type":"movie","ratingKey":"32","title":"Elsewhere","librarySectionID":33}
            ]}}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            val result = client.actorLibraryWorks(CastMember("Person", ppkey = "19"), listOf(
                Library(4, "Movies", "movie"), Library(5, "Series", "tv"),
            ))
            assertEquals(listOf(31), result.items.map { it.id })
            assertTrue(result.incomplete)
            val first = server.takeRequest()
            assertEquals("19", first.requestUrl!!.queryParameter("actor"))
            assertEquals("/library/sections/4/all", first.requestUrl!!.encodedPath)
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("type"))
        }
    }
}
