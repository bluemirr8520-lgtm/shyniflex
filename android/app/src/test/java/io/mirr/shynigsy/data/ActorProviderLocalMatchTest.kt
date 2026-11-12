package io.mirr.shynigsy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic provider envelopes model the server modal's cast[].local_match contract. */
class ActorProviderLocalMatchTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<String>()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var blockProvider = false
    private var body = JSONObject()
    private val actor = CastMember("Synthetic actor", tmdbId = 77)
    private val parent = MediaItem(1, 9, "Current series", "show", viewItemId = 1001)
    private val libraries = listOf(Library(9, "TV", "tv"), Library(8, "Movies", "movie"),
        Library(11, "Private", "tv", isSpoiler = true))

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += path
                    return when (path) {
                        "/people/tmdb/77" -> {
                            entered.countDown()
                            if (blockProvider) release.await(5, TimeUnit.SECONDS)
                            MockResponse().setBody(body.toString())
                        }
                        "/libraries/9/shows" -> MockResponse().setBody(JSONArray((1..17).map {
                            JSONObject().put("id", it).put("view_item_id", 1000 + it).put("title", "Local series $it")
                        }).toString())
                        "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_synthetic-private","expires_in":1800}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() { release.countDown(); SpoilerAccessRegistry.clearAll(); server.shutdown() }

    @Test fun castLocalMatchesMergeWithDirectCreditsUsingLocalRatherThanTmdbIds() = runBlocking {
        body = envelope(listOf(
            credit(match(9, "tv", "local_id", 2).put("view_item_id", 1002)),
            credit(match(8, "movie", "metadata_item_id", 30)),
            credit(match(9, "show", "representative_metadata_item_id", 99).put("local_id", 2).put("view_item_id", 1001)),
            credit(match(9, "show", "local_id", 1).put("view_item_id", 1999)),
        )).put("local_credits", JSONArray().put(local(9, 2, 1).put("view_item_id", 1001)).put(local(8, 1, 30)))
        val result = client.actorWorks(actor, parent, libraries)
        assertEquals(4, result.items.size)
        assertEquals(setOf(1, 2, 30), result.items.map { it.id }.toSet())
        assertEquals(1, result.items.single { it.viewItemId == 1001 }.id)
        assertTrue(result.items.any { it.id == 1 && it.viewItemId == 1999 })
        assertEquals(1, result.items.count { it.libraryId == 8 && it.id == 30 })
        assertTrue(result.items.none { it.id == 900001 })
        assertEquals(listOf("/people/tmdb/77"), requests)
    }

    @Test fun castOnlyEnvelopeSupportsExplicitRepresentativePriorityAndTitleFallback() = runBlocking {
        val matched = match(9, "tv", "representative_metadata_item_id", 7)
            .put("local_id", 70).put("metadata_item_id", 700).put("poster_url", "/images/synthetic.jpg")
        matched.remove("title")
        body = envelope(listOf(credit(matched)))
        val result = client.actorWorks(actor, parent, libraries)
        assertEquals(7, result.items.single().id)
        assertEquals("Provider display title", result.items.single().title)
        assertEquals("show", result.items.single().type)
    }

    @Test fun sectionRemoteCrewLockedUnknownAndUnboundRowsNeverBecomePlayableWorks() = runBlocking {
        val valid = match(9, "tv", "local_id", 2)
        val variants = listOf(
            copy(valid).put("context_kind", "section"), copy(valid).put("context_kind", "unknown"),
            copy(valid).put("remote", true), copy(valid).put("federated_id", "remote:9"),
            copy(valid).put("locked", true), copy(valid).put("is_locked", true),
            copy(valid).put("library_view_id", 999), copy(valid).put("library_view_id", 11),
            copy(valid).put("library_view_id", 8), copy(valid).put("media_type", "episode"),
            copy(valid).put("representative_metadata_item_id", 0), copy(valid).put("view_item_id", -1),
            copy(valid).put("local_id", 2.5), copy(valid).put("library_view_id", "invalid"),
            copy(valid).apply { remove("library_view_id"); put("library_id", 9) },
            copy(valid).apply { remove("library_view_id"); put("library_section_id", 9) },
            copy(valid).apply { remove("local_id"); put("tmdb_id", 2) },
            copy(valid).put("spoiler_enabled", true),
        )
        val credits = variants.map(::credit) + credit(copy(valid)).put("remote", true) +
            credit(copy(valid)).put("spoiler_enabled", true) + JSONObject().put("tmdb_id", 2).put("title", "Not local")
        body = envelope(credits).also {
            it.getJSONObject("tmdb").put("crew", JSONArray().put(credit(copy(valid).put("local_id", 200))))
        }
        assertTrue(client.actorWorks(actor, parent, libraries).items.isEmpty())
        assertEquals(listOf("/people/tmdb/77"), requests)
    }

    @Test fun stricterSpoilerStateWinsWhenTheSameLocalWorkAppearsTwice() = runBlocking {
        body = envelope(listOf(credit(match(9, "tv", "local_id", 1).put("view_item_id", 1001).put("spoiler_enabled", true))))
            .put("local_credits", JSONArray().put(local(9, 2, 1).put("view_item_id", 1001)))
        assertTrue(client.actorWorks(actor, parent, libraries).items.isEmpty())
    }

    @Test fun providerCastAddsMoviesWithoutLosingSeventeenServerFilteredShows() = runBlocking {
        body = envelope(listOf(credit(match(8, "movie", "local_id", 301))))
            .put("local_credits", JSONArray().put(local(9, 2, 1).put("view_item_id", 1001)).put(local(8, 1, 300)))
        val result = client.actorWorks(actor.copy(ppkey = "synthetic-person"), parent, libraries, allowLocalFallback = true)
        assertEquals(19, result.items.size)
        assertTrue((1..17).all { id -> result.items.any { it.libraryId == 9 && it.id == id } })
        assertTrue(result.items.any { it.libraryId == 8 && it.id == 300 })
        assertTrue(result.items.any { it.libraryId == 8 && it.id == 301 })
        assertFalse(requests.any { it.contains("/movies") || it.startsWith("/libraries/11/") })
    }

    @Test fun changedAccountRejectsDelayedCastLocalMatches() = runBlocking {
        body = envelope(listOf(credit(match(9, "show", "local_id", 2))))
        blockProvider = true
        val work = async(Dispatchers.Default) { runCatching { client.actorWorks(actor, parent, libraries) } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        client.token = "other-synthetic-account"
        release.countDown()
        val failure = withTimeout(5_000) { work.await() }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 401)
    }

    @Test fun relockedSpoilerGrantRejectsDelayedPrivateMatches() = runBlocking {
        client.unlockSpoiler("1234")
        body = envelope(listOf(credit(match(11, "show", "local_id", 2))))
        blockProvider = true
        val work = async(Dispatchers.Default) { runCatching { client.actorWorks(actor, parent, libraries) } }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        client.clearSpoilerGrant()
        release.countDown()
        val failure = withTimeout(5_000) { work.await() }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 403)
    }

    private fun envelope(cast: List<JSONObject>) = JSONObject().put("tmdb", JSONObject().put("cast", JSONArray(cast)))
    private fun credit(match: JSONObject) = JSONObject().put("tmdb_id", 900001).put("title", "Provider display title").put("local_match", match)
    private fun match(view: Int, type: String, idKey: String, id: Int) = JSONObject().put("library_view_id", view)
        .put("media_type", type).put(idKey, id).put("title", "Local title $id")
    private fun local(view: Int, type: Int, id: Int) = JSONObject().put("library_view_id", view)
        .put("metadata_type", type).put("metadata_item_id", id).put("title", "Direct local title $id")
    private fun copy(value: JSONObject) = JSONObject(value.toString())
}
