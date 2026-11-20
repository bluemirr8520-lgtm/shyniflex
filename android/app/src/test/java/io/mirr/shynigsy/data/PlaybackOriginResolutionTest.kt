package io.mirr.shynigsy.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaybackOriginResolutionTest {
    private fun source(type: String = "episode") = MediaItem(23, 8, "합성 영상", type,
        viewItemId = 501, showId = 100.takeIf { type == "episode" }, resumeMs = 3_000,
        seasonNumber = 1, episodeNumber = 2, playbackPartId = 90)
    private fun spec(server: MockWebServer, item: MediaItem = source()) = PlaybackSpec(item.title,
        server.url("/stream/90?ticket=fixture").toString(), itemId = item.id, libraryId = item.libraryId,
        partId = 90, viewItemId = item.viewItemId, sourceItem = item)
    private fun detail(id: Int = 100, type: String = "show", country: String = "KR") = JSONObject()
        .put("id", id).put("type", type).put("library_view_id", 8).put("view_item_id", 501)
        .put("extra", JSONObject().put("country", country)).toString()

    @Test fun fillsHomeEpisodeOriginUsingOneExactShowDetailAndKeepsPlaybackIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(detail())); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val current = spec(server)
            val resolved = client.resolvePlaybackOrigin(current)!!
            assertEquals(current.sourceItem!!.copy(productionCountries = listOf("KR")), resolved)
            val request = server.takeRequest()
            assertEquals("/libraries/8/shows/100?fields=full", request.path)
            assertEquals("GET", request.method)
            assertEquals(1, server.requestCount)
            val nextEpisode = current.sourceItem!!.copy(id = 24, episodeNumber = 3)
            assertEquals(listOf("KR"), client.resolvePlaybackOrigin(spec(server, nextEpisode))!!.productionCountries)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun foreignMovieWithKoreanTitleReceivesForeignCountryRatherThanAnAssumedKoreanOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(detail(23, "movie", "US"))); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            assertEquals(listOf("US"), client.resolvePlaybackOrigin(spec(server, source("movie")))!!.productionCountries)
            assertEquals("/libraries/8/movies/23?fields=full", server.takeRequest().path)
        }
    }

    @Test fun mergedShowRepresentativeRequiresExplicitSameViewAndBothMembershipIds() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val response = JSONObject(detail(id = 101)).put("show_ids", JSONArray(listOf(100, 101)))
            val variants = listOf(
                JSONObject(response.toString()).put("show_ids", JSONArray(listOf(101))),
                JSONObject(response.toString()).put("show_ids", JSONArray(listOf(100))),
                JSONObject(response.toString()).apply { remove("view_item_id") },
                JSONObject(response.toString()).apply { remove("library_view_id") },
            )
            val client = ShyniClient(server.url("/").toString(), "fixture")
            for (invalid in variants) {
                server.enqueue(MockResponse().setBody(invalid.toString()))
                assertNull(client.resolvePlaybackOrigin(spec(server)))
            }
            server.enqueue(MockResponse().setBody(response.toString()))
            assertEquals(listOf("KR"), client.resolvePlaybackOrigin(spec(server))!!.productionCountries)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun rejectsWrongIdentityViewTypeAndRemoteOrPrivateMetadata() = runBlocking {
        val invalid = listOf(
            "id" to 999, "metadata_item_id" to 999, "library_view_id" to 9,
            "view_item_id" to 999, "type" to "movie", "remote" to true,
            "federated_id" to "remote", "spoiler_enabled" to true,
        )
        MockWebServer().use { server ->
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            for ((key, value) in invalid) {
                server.enqueue(MockResponse().setBody(JSONObject(detail()).put(key, value).toString()))
                assertNull(key, client.resolvePlaybackOrigin(spec(server)))
            }
            assertEquals(invalid.size, server.requestCount)
        }
    }

    @Test fun knownOriginsAndInvalidPlaybackContextsNeverAddNetworkRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val country = source().copy(productionCountries = listOf("KR"))
            val language = source().copy(originalLanguage = "en")
            assertEquals(country, client.resolvePlaybackOrigin(spec(server, country)))
            assertEquals(language, client.resolvePlaybackOrigin(spec(server, language)))
            assertNull(client.resolvePlaybackOrigin(spec(server).copy(itemId = 999)))
            assertNull(client.resolvePlaybackOrigin(spec(server).copy(viewItemId = 999)))
            assertNull(client.resolvePlaybackOrigin(spec(server).copy(libraryId = 9)))
            assertNull(client.resolvePlaybackOrigin(spec(server, source().copy(showId = null))))
            assertNull(client.resolvePlaybackOrigin(spec(server).copy(partId = 0)))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun reusesAlreadyInspectedExactPartParentWithoutScanningShowsOrFetchingMediaInfoAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"item":{"id":23,"show_id":100},
                "parts":[{"id":90,"metadata_item_id":23}],"markers":[]}"""))
            server.enqueue(MockResponse().setBody(detail())); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val current = spec(server, source().copy(showId = null))
            client.playbackMarkers(current)
            assertEquals(listOf("KR"), client.resolvePlaybackOrigin(current)!!.productionCountries)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().path!!.startsWith("/libraries/8/media_info/90?"))
            assertEquals("/libraries/8/shows/100?fields=full", server.takeRequest().path)
        }
    }

    @Test fun rejectsCachedPartParentWhenPartBindingDoesNotMatch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"item":{"id":23,"show_id":100},
                "parts":[{"id":90,"metadata_item_id":999}],"markers":[]}""")); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val current = spec(server, source().copy(showId = null))
            client.playbackMarkers(current)
            assertNull(client.resolvePlaybackOrigin(current))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun existingServerWithoutFullFieldsGetsOnlyOneCompatibilityRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"unsupported fields"}"""))
            server.enqueue(MockResponse().setBody(detail())); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            assertEquals(listOf("KR"), client.resolvePlaybackOrigin(spec(server))!!.productionCountries)
            assertEquals("/libraries/8/shows/100?fields=full", server.takeRequest().path)
            assertEquals("/libraries/8/shows/100", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun permissionFailureDoesNotRetryAndLockedPrivateSourceDoesNotFetch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}")); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            assertEquals(403, (runCatching { client.resolvePlaybackOrigin(spec(server)) }.exceptionOrNull() as ApiException).status)
            assertEquals(403, (runCatching { client.resolvePlaybackOrigin(spec(server, source().copy(isSpoiler = true))) }
                .exceptionOrNull() as ApiException).status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lateResponseCannotCarryCountryAcrossAnAccountChange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(detail()).setBodyDelay(250, TimeUnit.MILLISECONDS)); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val pending = async(Dispatchers.Default) { runCatching { client.resolvePlaybackOrigin(spec(server)) } }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            client.token = "another-account"
            assertEquals(401, (pending.await().exceptionOrNull() as ApiException).status)
            server.enqueue(MockResponse().setBody(detail(country = "US")))
            assertEquals(listOf("US"), client.resolvePlaybackOrigin(spec(server))!!.productionCountries)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cachedCountryIsNotReusedAfterAccountChange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(detail())); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            assertEquals(listOf("KR"), client.resolvePlaybackOrigin(spec(server))!!.productionCountries)
            client.token = "another-account"
            server.enqueue(MockResponse().setBody(detail(country = "US")))
            assertEquals(listOf("US"), client.resolvePlaybackOrigin(spec(server))!!.productionCountries)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun outerCancellationRemainsCancellationRatherThanAnUnknownOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val result = runCatching { withTimeout(1_000) { client.resolvePlaybackOrigin(spec(server)) } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun originPresenceTreatsUndefinedLanguagesAndMalformedValuesAsUnknown() {
        for (language in listOf("und", "unk", "mul", "zxx", "ko\nsecret", ""))
            assertFalse(source().copy(originalLanguage = language).hasKnownMediaOrigin())
        for (language in listOf("en", "ja", "ko", "KOR", "ko_KR", "한국어"))
            assertTrue(source().copy(originalLanguage = language).hasKnownMediaOrigin())
        assertFalse(source().copy(productionCountries = listOf("unknown", "KR\nsecret")).hasKnownMediaOrigin())
        assertTrue(source().copy(productionCountries = listOf("US")).hasKnownMediaOrigin())
    }
}
