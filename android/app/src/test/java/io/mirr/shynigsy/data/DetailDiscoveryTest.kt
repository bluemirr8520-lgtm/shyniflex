package io.mirr.shynigsy.data

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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DetailDiscoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val unlocks = AtomicInteger()
    private var release = CountDownLatch(1)
    @Volatile private var reply: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404).setBody("{}") }
    private val movies = Library(8, "Synthetic movies", "movie")
    private val tv = Library(9, "Synthetic TV", "tv")
    private val movie = MediaItem(11, 8, "Synthetic movie", "movie", viewItemId = 101)
    private val actor = CastMember("Synthetic actor", tmdbId = 71)

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        requests.clear(); unlocks.set(0); release = CountDownLatch(1)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return when (request.requestUrl!!.encodedPath) {
                        "/spoiler/client/unlock" -> MockResponse().setBody(
                            """{"token":"spc_synthetic_${unlocks.incrementAndGet()}","expires_in":1800}""")
                        "/playback/progress" -> MockResponse().setBody("""{"items":[]}""")
                        else -> reply(request)
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() { release.countDown(); SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun credit(id: Int, library: Int = 8, type: Int = 1) = JSONObject()
        .put("metadata_type", type).put("library_view_id", library)
        .put("representative_metadata_item_id", id).put("metadata_item_id", id + 1000)
        .put("title", "Synthetic work $id").put("poster", "/poster/$id")
    private fun people(vararg rows: JSONObject) = MockResponse().setBody(JSONObject()
        .put("local_credits", JSONArray(rows.toList()))
        .put("tmdb", JSONObject().put("cast", JSONArray().put(JSONObject().put("title", "Not a local work"))))
        .toString())
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Synthetic request must arrive", latch.await(5, TimeUnit.SECONDS))
    }
    private fun section(vararg cast: JSONObject): JSONObject {
        val groups = JSONArray()
            .put(JSONObject().put("id", "cast").put("items", JSONArray(cast.toList())))
            .put(JSONObject().put("id", "crew").put("items", JSONArray().put(JSONObject().put("title", "Synthetic crew"))))
        return JSONObject().put("metadata_sections", JSONArray().put(JSONObject()
            .put("presentation", "person_row").put("groups", groups)))
    }
    private fun person(name: String, type: String = "open_person", value: Any = "71", role: String = "Lead") = JSONObject()
        .put("title", name).put("subtitle", role).put("image_url", "/portrait/1")
        .put("action", JSONObject().put("type", type).put("value", value))

    @Test fun movieDetailReusesItsResponseForNeutralCastWithoutAnotherMetadataRequest() = runBlocking {
        reply = { MockResponse().setBody(section(person("Synthetic actor"), person("Synthetic actor", role = "Guest"),
            person("Synthetic local actor", "actor_ppkey", "synthetic-ppkey"),
            person("Synthetic unknown", "unknown_action", "72")).put("id", 11).put("title", movie.title).toString()) }
        val result = client.details(movie)
        assertEquals(listOf("Synthetic actor", "Synthetic local actor", "Synthetic unknown"), result.castMembers.map { it.name })
        assertEquals("Lead / Guest", result.castMembers.first().role)
        assertEquals(71, result.castMembers.first().tmdbId)
        assertEquals("synthetic-ppkey", result.castMembers[1].ppkey)
        assertFalse(result.castMembers.last().canLoadWorks)
        assertEquals(server.url("/portrait/1").toString(), result.castMembers.first().portrait)
        assertEquals(listOf("/libraries/8/movies/11", "/playback/progress"), requests.map { it.requestUrl!!.encodedPath })
        assertEquals("full", requests.first().requestUrl!!.queryParameter("fields"))
        assertNull(requests.last().requestUrl!!.queryParameter("fields"))
        assertEquals(2, server.requestCount)
    }

    @Test fun fullShowDetailsExposeServerLocalPpkeyCastWithoutAnotherRequest() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/9/shows/21" -> {
                val body = JSONObject().put("id", 21).put("title", "Synthetic show")
                if (request.requestUrl!!.queryParameter("fields") == "full") {
                    body.put("extra", JSONObject().put("actors", JSONArray().put(
                        JSONObject().put("name", "Synthetic actor").put("ppkey", "local-actor-id"))))
                }
                MockResponse().setBody(body.toString())
            }
            "/libraries/9/shows/21/seasons" -> MockResponse().setBody("[]")
            else -> MockResponse().setResponseCode(404)
        } }
        val detail = client.details(MediaItem(21, 9, "Synthetic show", "show"))
        assertEquals("local-actor-id", detail.castMembers.single().ppkey)
        assertEquals(listOf("/libraries/9/shows/21", "/libraries/9/shows/21/seasons"),
            requests.map { it.requestUrl!!.encodedPath })
        assertEquals("full", requests.first().requestUrl!!.queryParameter("fields"))
    }

    @Test fun serverWithoutFieldsOptionFallsBackToTheSameViewWithoutEscalating() = runBlocking {
        reply = { request -> if (request.requestUrl!!.queryParameter("fields") == "full")
            MockResponse().setResponseCode(422).setBody("{}")
        else MockResponse().setBody(section(person("Synthetic actor")).put("id", 11)
            .put("title", movie.title).toString()) }
        val detail = client.details(movie)
        assertEquals("Synthetic actor", detail.castMembers.single().name)
        assertEquals(listOf("full", null), requests.filter { it.requestUrl!!.encodedPath == "/libraries/8/movies/11" }
            .map { it.requestUrl!!.queryParameter("fields") })
        assertTrue(requests.all { it.requestUrl!!.queryParameter("admin") == null })
    }

    @Test fun forbiddenFullDetailsNeverRetryWithWeakerRequest() = runBlocking {
        reply = { MockResponse().setResponseCode(403).setBody("{}") }
        val error = runCatching { client.details(movie) }.exceptionOrNull()
        assertTrue(error is ApiException && error.status == 403)
        assertEquals(1, requests.size)
        assertEquals("full", requests.single().requestUrl!!.queryParameter("fields"))
    }

    @Test fun showAndEpisodeReuseExistingShowMetadataAndNeverAddACastRequest() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/9/shows/21" -> MockResponse().setBody(section(person("Synthetic show actor"))
                .put("id", 21).put("title", "Synthetic show").toString())
            "/libraries/9/shows/21/seasons" -> MockResponse().setBody("[]")
            else -> MockResponse().setResponseCode(404)
        } }
        val show = client.details(MediaItem(21, 9, "Synthetic show", "show"))
        val episode = client.details(MediaItem(31, 9, "Synthetic episode", "episode", showId = 21))
        assertEquals("Synthetic show actor", show.castMembers.single().name)
        assertEquals(show.castMembers, episode.castMembers)
        assertEquals(2, requests.count { it.requestUrl!!.encodedPath == "/libraries/9/shows/21" })
        assertTrue(requests.filter { it.requestUrl!!.encodedPath == "/libraries/9/shows/21" }
            .all { it.requestUrl!!.queryParameter("fields") == "full" })
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/libraries/9/shows/21/seasons" })
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/playback/progress" })
        assertEquals(4, server.requestCount)
    }

    @Test fun castParserKeepsNamesAsLabelsDoesNotReplaceEmptyCastWithCrewAndSupportsExistingExtra() {
        val legacy = JSONObject().put("extra", JSONObject().put("actors", JSONArray()
            .put(JSONObject().put("name", "Namesake").put("role", "First").put("tmdb_id", 71))
            .put(JSONObject().put("name", "Namesake").put("role", "Second").put("tmdb_id", 72))
            .put(JSONObject().put("name", "No identity").put("thumb", "https://user:secret@example.test/photo"))))
        val cast = client.parseCastMembers(legacy)
        assertEquals(3, cast.size)
        assertNotEquals(cast[0].key, cast[1].key)
        assertFalse(cast[2].canLoadWorks)
        assertNull(cast[2].portrait)
        assertTrue(client.parseCastMembers(section().put("extra", legacy.getJSONObject("extra"))).isEmpty())
        assertFalse(CastMember("Invalid", tmdbId = 0, ppkey = "\n").canLoadWorks)
        assertEquals(0, server.requestCount)
    }

    @Test fun peopleUsesOneViewRequestAndOnlyExplicitAuthorizedLocalRepresentativeIdentities() = runBlocking {
        reply = { people(credit(12).put("browse_view_id", 900).put("view_item_id", JSONObject.NULL), credit(12),
            credit(22, 9, 2).put("view_item_id", 202),
            credit(32).put("representative_metadata_item_id", JSONObject.NULL).put("metadata_item_id", 32),
            credit(23, 9, 1), credit(24, 404), credit(25).put("context_kind", "section"),
            credit(26).put("remote", true), credit(27).put("federated_id", "synthetic-remote"),
            credit(28).put("representative_metadata_item_id", 0), credit(29).put("view_item_id", -1),
            credit(30).put("library_section_id", 8).apply { remove("library_view_id") }) }
        val result = client.actorWorks(actor, movie, listOf(movies, tv))
        assertEquals(listOf(12, 22, 32), result.items.map { it.id })
        assertEquals(listOf("movie", "show", "movie"), result.items.map { it.type })
        assertNull(result.items.first().viewItemId)
        assertEquals(202, result.items[1].viewItemId)
        assertNull(result.items.last().viewItemId)
        assertEquals(server.url("/poster/12").toString(), result.items.first().poster)
        assertTrue(result.items.all { it.watchState == null })
        assertFalse(result.currentLibraryOnly)
        val request = requests.single()
        assertEquals("/people/tmdb/71", request.requestUrl!!.encodedPath)
        assertEquals("view", request.requestUrl!!.queryParameter("navigation_context"))
        assertEquals("Bearer synthetic-account", request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Shyni-Spoiler"))
        assertEquals(1, server.requestCount)
    }

    @Test fun relatedUsesOnlyFirstExactTmdbActorExcludesTheCurrentMovieOrShowAndCapsDisplay() = runBlocking {
        reply = { people(*((11..41).map { credit(it) } + credit(21, 9, 2)).toTypedArray()) }
        val cast = listOf(CastMember("Label only"), CastMember("Local", ppkey = "synthetic"), actor, actor.copy(tmdbId = 72))
        val related = client.relatedWorks(movie, cast, listOf(movies, tv))
        assertEquals(20, related.size)
        assertTrue(related.none { it.type == "movie" && it.id == 11 })
        assertEquals("/people/tmdb/71", requests.single().requestUrl!!.encodedPath)
        reply = { people(credit(21, 9, 2), credit(22, 9, 2)) }
        val episode = MediaItem(31, 9, "Synthetic episode", "episode", showId = 21)
        assertEquals(listOf(22), client.relatedWorks(episode, listOf(actor), listOf(tv)).map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test fun noIdentityOrNoKnownParentShowNeverTriggersAutomaticRelatedLookup() = runBlocking {
        assertTrue(client.relatedWorks(movie, listOf(CastMember("Local", ppkey = "synthetic")), listOf(movies)).isEmpty())
        assertTrue(client.relatedWorks(MediaItem(31, 9, "Synthetic episode", "episode"), listOf(actor), listOf(tv)).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun ppkeySearchesEveryAccessibleTvViewAndEscapesItsIdentity() = runBlocking {
        reply = { request -> MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
            "/libraries/9/shows/21", "/libraries/10/shows/24" ->
                section(person("Synthetic local actor", "actor_ppkey", "local & 한글/")).toString()
            "/libraries/10/shows" -> JSONArray().put(JSONObject().put("id", 24).put("title", "Other show")).toString()
            "/libraries/8/movies" -> "[]"
            else -> JSONArray().put(JSONObject().put("id", 21).put("title", "Synthetic show")
            .put("view_item_id", 201)).put(JSONObject().put("id", 22).put("title", "Wrong View").put("library_view_id", 404))
            .put(JSONObject().put("id", 23).put("title", "Wrong type").put("type", "movie")).toString()
        }) }
        val localActor = CastMember("Synthetic local actor", ppkey = "local & 한글/")
        val result = client.actorWorks(localActor, movie, listOf(tv, movies, Library(10, "More TV", "tv"),
            Library(11, "Locked TV", "tv", true)), scanMovieDetails = true)
        assertFalse(result.currentLibraryOnly)
        assertFalse(result.tvLibrariesOnly)
        assertFalse(result.incomplete)
        assertEquals(listOf(21, 24), result.items.map { it.id })
        val searches = requests.filter { it.requestUrl!!.encodedPath.endsWith("/shows") }
        assertEquals(setOf("/libraries/9/shows", "/libraries/10/shows"), searches.map { it.requestUrl!!.encodedPath }.toSet())
        assertTrue(searches.all { it.requestUrl!!.queryParameter("actor_ppkey") == "local & 한글/" })
        assertTrue(searches.all { it.requestUrl!!.queryParameter("limit") == null && it.requestUrl!!.queryParameter("offset") == null })
        val movieOnly = client.actorWorks(localActor, movie, listOf(movies), scanMovieDetails = true)
        assertTrue(movieOnly.items.isEmpty())
        assertFalse(movieOnly.incomplete)
        assertEquals(4, server.requestCount)
    }

    @Test fun ppkeySearchesMoviesAcrossViewsAndVerifiesTheFullCastWithoutMatchingNamesAlone() = runBlocking {
        val late = (1..120).map { person("Other actor $it", "actor_ppkey", "other-$it") } +
            person("Actor", "actor_ppkey", "abc")
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/8/movies" -> MockResponse().setBody("""[{"id":11,"title":"From extra"},{"id":12,"title":"Late actor"},{"id":13,"title":"Namesake"}]""")
            "/libraries/10/movies" -> MockResponse().setBody("""[{"id":21,"title":"Another view"}]""")
            "/libraries/8/movies/11" -> MockResponse().setBody(section(person("Different cast"))
                .put("extra", JSONObject().put("actors", JSONArray().put(JSONObject().put("name", "Actor").put("ppkey", "abc")))).toString())
            "/libraries/8/movies/12" -> MockResponse().setBody(section(*late.toTypedArray()).toString())
            "/libraries/8/movies/13" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "different")).toString())
            "/libraries/10/movies/21" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), movie,
            listOf(movies, Library(10, "Other movies", "movie"), Library(11, "Locked", "movie", true)),
            scanMovieDetails = true)
        assertEquals(listOf(11, 12, 21), result.items.map { it.id })
        assertTrue(result.incomplete) // A same-named person has a different identity.
        assertTrue(requests.none { it.requestUrl!!.encodedPath.startsWith("/libraries/11/") })
        assertTrue(requests.filter { it.requestUrl!!.encodedPath.matches(Regex("/libraries/\\d+/movies/\\d+")) }
            .all { it.requestUrl!!.queryParameter("fields") == "full" })
    }

    @Test fun failedMovieDetailKeepsConfirmedWorksAndReportsIncomplete() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/8/movies" -> MockResponse().setBody("""[{"id":11,"title":"Confirmed"},{"id":12,"title":"Unavailable"}]""")
            "/libraries/8/movies/11" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(503)
        } }
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), movie, listOf(movies), scanMovieDetails = true)
        assertEquals(listOf(11), result.items.map { it.id })
        assertTrue(result.incomplete)
    }

    @Test fun movieSearchReportsOrderedSnapshotsAndStopsAfterRepeatedFailures() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/8/movies" -> MockResponse().setBody(JSONArray((1..10).map {
                JSONObject().put("id", it).put("title", "Movie $it") }).toString())
            "/libraries/8/movies/1" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(503)
        } }
        val updates = mutableListOf<Triple<List<MediaItem>, Int, Int>>()
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), movie, listOf(movies), scanMovieDetails = true) { found, done, total ->
            updates.add(Triple(found, done, total))
        }
        assertEquals(listOf(1), result.items.map { it.id })
        assertTrue(result.incomplete)
        assertEquals(listOf(0, 4, 8), updates.map { it.second })
        assertTrue(updates.all { it.third == 10 })
        assertEquals(emptyList<Int>(), updates.first().first.map { it.id })
        assertEquals(listOf(1), updates.last().first.map { it.id })
        assertTrue(requests.none { it.requestUrl!!.encodedPath.endsWith("/movies/9") })
    }

    @Test fun movieSearchReportsMissingIdsAndServerIndicatedTruncation() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/8/movies" -> MockResponse().setBody("""{"items":[{"id":11,"title":"Known"},{"title":"Missing id"}],"total":7}""")
            "/libraries/8/movies/11" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), movie, listOf(movies), scanMovieDetails = true)
        assertEquals(listOf(11), result.items.map { it.id })
        assertTrue(result.incomplete)
    }

    @Test fun movieSearchFallsBackWhenTheFullFieldsOptionIsUnsupported() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/8/movies" -> MockResponse().setBody("""[{"id":11,"title":"Known"}]""")
            "/libraries/8/movies/11" -> if (request.requestUrl!!.queryParameter("fields") != null)
                MockResponse().setResponseCode(400) else MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), movie, listOf(movies), scanMovieDetails = true)
        assertEquals(listOf(11), result.items.map { it.id })
        assertFalse(result.incomplete)
        assertEquals(2, requests.count { it.requestUrl!!.encodedPath == "/libraries/8/movies/11" })
    }

    @Test fun neutralRecordCanLinkDirectlyProvidedTmdbAndLocalIds() {
        val row = person("Actor", "actor_ppkey", "abc").put("tmdb_id", 71)
        val parsed = client.parseCastMembers(section(row)).single()
        assertEquals(71, parsed.tmdbId)
        assertEquals("abc", parsed.ppkey)
    }

    @Test fun emptyTmdbCreditsFallBackToTheSameActorsLocalIdentity() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/people/tmdb/71" -> people()
            "/libraries/8/movies" -> MockResponse().setBody("""[{"id":11,"title":"Known"}]""")
            "/libraries/8/movies/11" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(404)
        } }
        val progress = mutableListOf<List<Int>>()
        val result = client.actorWorks(CastMember("Actor", tmdbId = 71, ppkey = "abc"), movie, listOf(movies), allowLocalFallback = true, scanMovieDetails = true) { found, _, _ ->
            progress.add(found.map { it.id })
        }
        assertEquals(listOf(11), result.items.map { it.id })
        assertEquals(listOf(11), progress.last())
        assertEquals("/people/tmdb/71", requests.first().requestUrl!!.encodedPath)
    }

    @Test fun manualActorLookupFallsBackWhenPeopleEndpointIsUnavailable() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/people/tmdb/71" -> MockResponse().setResponseCode(404)
            "/libraries/8/movies" -> MockResponse().setBody("""[{"id":11,"title":"Known"}]""")
            "/libraries/8/movies/11" -> MockResponse().setBody(section(person("Actor", "actor_ppkey", "abc")).toString())
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.actorWorks(CastMember("Actor", tmdbId = 71, ppkey = "abc"), movie,
            listOf(movies), allowLocalFallback = true, scanMovieDetails = true)
        assertEquals(listOf(11), result.items.map { it.id })
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/8/movies/11" })
    }

    @Test fun automaticActorLookupNeverScansLibrariesWhenPeopleEndpointIsUnavailable() = runBlocking {
        reply = { MockResponse().setResponseCode(404) }
        val selected = CastMember("Actor", tmdbId = 71, ppkey = "abc")
        assertTrue(runCatching { client.actorWorks(selected, movie, listOf(movies)) }.exceptionOrNull() is ApiException)
        assertEquals(listOf("/people/tmdb/71"), requests.map { it.requestUrl!!.encodedPath })
        requests.clear()
        reply = { people() }
        assertTrue(client.actorWorks(selected, movie, listOf(movies)).items.isEmpty())
        assertEquals(listOf("/people/tmdb/71"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun actorLookupDoesNotFallBackAfterAuthorizationFailure() = runBlocking {
        reply = { MockResponse().setResponseCode(403) }
        val selected = CastMember("Actor", tmdbId = 71, ppkey = "abc")
        val error = runCatching { client.actorWorks(selected, movie, listOf(movies), allowLocalFallback = true) }.exceptionOrNull()
        assertEquals(403, (error as? ApiException)?.status)
        assertEquals(listOf("/people/tmdb/71"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun ppkeyTrustsFilteredTvResultsAcrossViewsWithoutFetchingRepresentativeDetails() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/9/shows" -> MockResponse().setBody("""[{"id":21,"title":"Known show"}]""")
            // Server filters merged members by ppkey; representative details may
            // omit the particular merged member that caused this match.
            "/libraries/10/shows" -> MockResponse().setBody("""[{"id":24,"title":"Merged show"}]""")
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"),
            MediaItem(21, 9, "Show", "show"), listOf(tv, Library(10, "More", "tv")))
        assertEquals(listOf(21, 24), result.items.map { it.id })
        assertFalse(result.incomplete)
        assertTrue(requests.all { it.requestUrl!!.queryParameter("actor_ppkey") == "abc" })
        assertTrue(requests.none { it.requestUrl!!.encodedPath == "/libraries/10/shows/24" })
    }

    @Test fun ppkeyIncludesUnlockedPrivateViewsButMarksAFailedViewIncomplete() = runBlocking {
        client.unlockSpoiler("1234")
        reply = { request -> if (request.requestUrl!!.encodedPath == "/libraries/10/shows")
            MockResponse().setResponseCode(503) else MockResponse().setBody("[]") }
        val views = listOf(movies, tv, Library(10, "Private", "tv", true))
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), movie, views, scanMovieDetails = true)
        assertTrue(result.incomplete)
        assertTrue(requests.filter { it.requestUrl!!.encodedPath.endsWith("/shows") }.all {
            it.getHeader("X-Shyni-Spoiler") == "spc_synthetic_1" })
    }

    @Test fun tmdbCreditsAreNotTruncatedAtTwoHundred() = runBlocking {
        reply = { people(*(1..250).map { credit(it) }.toTypedArray()) }
        assertEquals(250, client.actorWorks(actor, movie, listOf(movies)).items.size)
    }

    @Test fun actorIdsAreAlwaysResolvedAgainstTheCurrentServerAndAccount() = runBlocking {
        reply = { people(credit(12)) }
        val otherServer = MockWebServer().apply { start() }
        try {
            otherServer.enqueue(people(credit(13)))
            val otherClient = ShyniClient(otherServer.url("/").toString(), "different-account", deviceId = "other-device")
            assertEquals(listOf(12), client.actorWorks(actor, movie, listOf(movies)).items.map { it.id })
            assertEquals(listOf(13), otherClient.actorWorks(actor, movie, listOf(movies)).items.map { it.id })
            assertEquals("Bearer different-account", otherServer.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer synthetic-account", requests.single().getHeader("Authorization"))
        } finally { otherServer.shutdown() }
    }

    @Test fun ppkeyAccountChangeDoesNotReturnPartialResultsFromTheOldAccount() = runBlocking {
        val started = CountDownLatch(1)
        reply = { started.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody("[]") }
        val pending = async(Dispatchers.Default) { runCatching { client.actorWorks(CastMember("Actor", ppkey = "abc"),
            MediaItem(21, 9, "Show", "show"), listOf(tv)) } }
        try {
            await(started)
            client.token = "other-synthetic-account"
            release.countDown()
            val error = withTimeout(5_000) { pending.await() }.exceptionOrNull()
            assertEquals(401, (error as ApiException).status)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun ppkeyMalformedOtherViewKeepsConfirmedResultsAndMarksThemIncomplete() = runBlocking {
        reply = { request -> MockResponse().setBody(if (request.requestUrl!!.encodedPath == "/libraries/9/shows")
            """[{"id":21,"title":"Known"}]""" else """{"unexpected":[]}""") }
        val result = client.actorWorks(CastMember("Actor", ppkey = "abc"), MediaItem(21, 9, "Show", "show"),
            listOf(tv, Library(10, "Different TV", "tv")))
        assertEquals(listOf(21), result.items.map { it.id })
        assertTrue(result.incomplete)
    }

    @Test fun ppkeyAuthenticationFailureIsNotMisreportedAsPartialResults() = runBlocking {
        for (failedPath in listOf("/libraries/10/shows")) {
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                failedPath -> MockResponse().setResponseCode(401).setBody("{}")
                "/libraries/9/shows" -> MockResponse().setBody("""[{"id":21,"title":"Known"}]""")
                "/libraries/10/shows" -> MockResponse().setBody("""[{"id":24,"title":"Other"}]""")
                else -> MockResponse().setResponseCode(404)
            } }
            val error = runCatching { client.actorWorks(CastMember("Actor", ppkey = "abc"),
                MediaItem(21, 9, "Show", "show"), listOf(tv, Library(10, "Other", "tv"))) }.exceptionOrNull()
            assertEquals(401, (error as ApiException).status)
        }
    }

    @Test fun privateViewWinsOverItsPublicShadowAndLockedPrivateParentsCannotSendRequests() = runBlocking {
        val error = runCatching { client.actorWorks(actor, movie, listOf(movies, movies.copy(isSpoiler = true))) }.exceptionOrNull()
        assertEquals(403, (error as ApiException).status)
        assertEquals(0, server.requestCount)
    }

    @Test fun privateRowsWinBeforeLockedFilteringAndUnlockedWorksUseOnlyTheCapturedLiveGrant() = runBlocking {
        reply = { people(credit(12).put("spoiler_enabled", false), credit(12).put("spoiler_enabled", true),
            credit(13), credit(22, 9, 2).put("spoiler_enabled", false)) }
        val locked = client.actorWorks(actor, movie, listOf(movies, tv.copy(isSpoiler = true)))
        assertEquals(listOf(13), locked.items.map { it.id })
        client.unlockSpoiler("1234")
        val unlocked = client.actorWorks(actor, movie, listOf(movies, tv, tv.copy(isSpoiler = true)))
        assertEquals(listOf(12, 13, 22), unlocked.items.map { it.id })
        assertTrue(unlocked.items.first().isSpoiler)
        assertTrue(unlocked.items.last().isSpoiler)
        assertEquals("spc_synthetic_1", requests.last().getHeader("X-Shyni-Spoiler"))
        assertEquals("synthetic-device", requests.last().getHeader("X-Device-Id"))
        assertEquals(3, server.requestCount)
    }

    @Test fun accountChangesAndGrantRotationRejectOlderPendingPeopleResponses() = runBlocking {
        for (rotateGrant in listOf(false, true)) {
            if (rotateGrant) client.unlockSpoiler("1234")
            val started = CountDownLatch(1)
            release = CountDownLatch(1)
            reply = { started.countDown(); release.await(5, TimeUnit.SECONDS); people(credit(12)) }
            val pending = async(Dispatchers.Default) { runCatching { client.actorWorks(actor, movie, listOf(movies)) } }
            try {
                await(started)
                if (rotateGrant) { client.clearSpoilerGrant(); client.unlockSpoiler("5678") }
                else client.token = "other-synthetic-account"
                release.countDown()
                val error = withTimeout(5_000) { pending.await() }.exceptionOrNull()
                assertEquals(if (rotateGrant) 403 else 401, (error as ApiException).status)
                if (rotateGrant) assertTrue(client.spoilerUnlocked)
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun cancellationStopsAnActualPendingPeopleRequestWithoutFallbackCatalogRequests() = runBlocking {
        val started = CountDownLatch(1)
        reply = { started.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val pending = async(Dispatchers.Default) { client.actorWorks(actor, movie, listOf(movies)) }
        await(started)
        withTimeout(5_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun failuresAndMalformedPeopleResponsesAreNotPretendedToBeEmptyFilmography() = runBlocking {
        for (status in listOf(401, 403, 503)) {
            reply = { MockResponse().setResponseCode(status).setBody("{}") }
            val error = runCatching { client.actorWorks(actor, movie, listOf(movies)) }.exceptionOrNull()
            assertEquals(status, (error as ApiException).status)
        }
        reply = { MockResponse().setBody("""{"tmdb":{"cast":[]}}""") }
        assertTrue(runCatching { client.actorWorks(actor, movie, listOf(movies)) }.exceptionOrNull() is ApiException)
        assertEquals(4, server.requestCount)
    }
}
