package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WatchedVersionFallbackTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val movie = MediaItem(23, 8, "작품", "movie", viewItemId = 501)
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { server.shutdown() }
    private fun respond(text: String) = server.enqueue(MockResponse().setBody(text))
    private fun change(): Pair<String?, JSONObject> {
        server.takeRequest()
        val request = server.takeRequest()
        return request.path to JSONObject(request.body.readUtf8())
    }

    @Test fun exactMetadataOwnershipWinsOverAlternateSourceInfo() = runBlocking {
        respond("""{"options":[{"metadata_item_id":24,"part_id":88},{"metadata_item_id":23,"part_id":90}],
            "source_info":{"metadata_item_id":24,"part_id":88}}""")
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(movie, true)
        val (path, data) = change()
        assertEquals("/continue-watching/23/watch-state", path)
        assertEquals(90, data.getInt("part_id"))
    }

    @Test fun mergedMovieUsesAuthorizedSourceInfoWithItsActualMetadataAndOriginalView() = runBlocking {
        respond("""{"options":[{"metadata_item_id":24,"part_id":88},{"metadata_item_id":25,"part_id":89}],
            "source_info":{"metadata_item_id":24,"part_id":88}}""")
        respond("""{"ok":true,"watched":false}""")
        client.setWatched(movie, false)
        val (path, data) = change()
        assertEquals("/continue-watching/24/watch-state", path)
        assertEquals(88, data.getInt("part_id"))
        assertEquals(8, data.getInt("library_view_id"))
        assertEquals(501, data.getInt("view_item_id"))
        assertFalse(data.getBoolean("watched"))
    }

    @Test fun singleAuthorizedMovieOptionCanResolveAnUnavailableRepresentative() = runBlocking {
        respond("""{"options":[{"metadata_item_id":24,"part_id":88}]}""")
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(movie, true)
        assertEquals("/continue-watching/24/watch-state", change().first)
    }

    @Test fun alternateEpisodeMustMatchBothKnownNumbersIncludingSpecialZero() = runBlocking {
        respond("""{"options":[],"source_info":{"metadata_item_id":24,"part_id":88,"season_number":0,"episode_number":0}}""")
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(movie.copy(type = "episode", seasonNumber = 0, episodeNumber = 0), true)
        val (path, data) = change()
        assertEquals("/continue-watching/24/watch-state", path)
        assertEquals(88, data.getInt("part_id"))
    }

    @Test fun mismatchingOrUnknownEpisodeIdentityNeverFallsBack() = runBlocking {
        val current = movie.copy(type = "episode", seasonNumber = 1, episodeNumber = 2)
        val options = listOf(
            """{"metadata_item_id":24,"part_id":88,"season_number":1,"episode_number":3}""",
            """{"metadata_item_id":24,"part_id":88,"season_number":2,"episode_number":2}""",
            """{"metadata_item_id":24,"part_id":88}""",
            """{"metadata_item_id":24,"part_id":88,"season_number":"invalid","episode_number":2}""",
        )
        options.forEach { option ->
            respond("""{"options":[$option],"source_info":$option}""")
            assertTrue(runCatching { client.setWatched(current, true) }.exceptionOrNull() is ApiException)
        }
        respond("""{"source_info":{"metadata_item_id":24,"part_id":88,"season_number":1,"episode_number":2}}""")
        assertTrue(runCatching { client.setWatched(current.copy(seasonNumber = null), true) }.exceptionOrNull() is ApiException)
        assertEquals(5, server.requestCount)
    }

    @Test fun nonpositiveFractionalAndOverflowedPhysicalIdsAreRejectedBeforeWrites() = runBlocking {
        listOf("""{"metadata_item_id":0,"part_id":88}""", """{"metadata_item_id":24,"part_id":-1}""",
            """{"metadata_item_id":24.5,"part_id":88}""", """{"metadata_item_id":24,"part_id":4294967384}""").forEach { option ->
            respond("""{"options":[$option],"source_info":$option}""")
            assertTrue(runCatching { client.setWatched(movie, true) }.exceptionOrNull() is ApiException)
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun contradictoryPartOwnershipNeverChoosesEitherMetadataId() = runBlocking {
        respond("""{"options":[{"metadata_item_id":23,"part_id":88},{"metadata_item_id":24,"part_id":88}],
            "source_info":{"metadata_item_id":24,"part_id":88}}""")
        assertTrue(runCatching { client.setWatched(movie, true) }.exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
    }

    @Test fun multipleAlternatesWithoutAuthoritativeSourceInfoAreAmbiguous() = runBlocking {
        respond("""{"options":[{"metadata_item_id":24,"part_id":88},{"metadata_item_id":25,"part_id":89}]}""")
        assertTrue(runCatching { client.setWatched(movie, true) }.exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
    }

    @Test fun contradictoryEpisodeNumbersForSamePhysicalPairAreNotIgnored() = runBlocking {
        respond("""{"options":[{"metadata_item_id":24,"part_id":88,"season_number":1,"episode_number":3}],
            "source_info":{"metadata_item_id":24,"part_id":88,"season_number":1,"episode_number":2}}""")
        assertTrue(runCatching { client.setWatched(movie.copy(type = "episode", seasonNumber = 1, episodeNumber = 2), true) }
            .exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
    }
}
