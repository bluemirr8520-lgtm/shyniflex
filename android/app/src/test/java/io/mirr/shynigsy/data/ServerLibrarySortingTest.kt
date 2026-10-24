package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The server web catalog defines sort parameters and ordering before local paging. */
class ServerLibrarySortingTest {
    private val movies = Library(8, "합성 영화", "movie")
    private val television = Library(9, "합성 시리즈", "tv")

    @Test fun movieAndShowSortRequestsUseServerFieldsAndExplicitDirections() {
        val movieQueries = listOf(
            LibrarySort.TITLE_ASC to "sort=title&sort_dir=asc",
            LibrarySort.TITLE_DESC to "sort=title&sort_dir=desc",
            LibrarySort.RECENT to "sort=added&sort_dir=desc",
            LibrarySort.YEAR_ASC to "sort=year&sort_dir=asc",
            LibrarySort.YEAR_DESC to "sort=year&sort_dir=desc",
            LibrarySort.RELEASED_ASC to "sort=released_at&sort_dir=asc",
            LibrarySort.RELEASED_DESC to "sort=released_at&sort_dir=desc",
            LibrarySort.RATING to "sort=rating&sort_dir=desc",
        )
        for ((sort, expected) in movieQueries) assertEquals(sort.name, expected, sort.query(movies))
        assertEquals("sort=title&sort_dir=asc", LibrarySort.TITLE_ASC.query(television))
        assertEquals("sort=title&sort_dir=desc", LibrarySort.TITLE_DESC.query(television))
        assertEquals("sort=added&sort_dir=desc", LibrarySort.RECENT.query(television))
        assertEquals("sort=latest_episode&sort_dir=desc", LibrarySort.LATEST_EPISODE.query(television))
        for (kind in listOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)) {
            assertEquals("sort=title", LibrarySort.TITLE_ASC.query(television, kind))
            assertEquals("sort=title", LibrarySort.TITLE_DESC.query(television, kind))
            assertEquals("sort=added", LibrarySort.RECENT.query(television, kind))
        }
        assertEquals("sort=latest_episode", LibrarySort.LATEST_EPISODE.query(television, LibraryContentType.EPISODES))
    }

    @Test fun unsupportedSortUsesTheDestinationCatalogsDefault() {
        assertEquals(LibrarySort.TITLE_ASC, LibrarySort.defaultFor(LibraryContentType.MOVIES))
        assertEquals(LibrarySort.TITLE_ASC, LibrarySort.defaultFor(LibraryContentType.SHOWS))
        assertEquals(LibrarySort.TITLE_ASC, LibrarySort.defaultFor(LibraryContentType.SEASONS))
        assertEquals(LibrarySort.LATEST_EPISODE, LibrarySort.defaultFor(LibraryContentType.EPISODES))
        assertEquals(LibrarySort.TITLE_ASC, LibrarySort.YEAR_DESC.forContentType(LibraryContentType.SHOWS))
        assertEquals(LibrarySort.TITLE_ASC, LibrarySort.LATEST_EPISODE.forContentType(LibraryContentType.MOVIES))
        assertEquals(LibrarySort.TITLE_ASC, LibrarySort.RELEASED_ASC.forContentType(LibraryContentType.SEASONS))
        assertEquals(LibrarySort.LATEST_EPISODE, LibrarySort.RATING.forContentType(LibraryContentType.EPISODES))
        assertEquals(LibrarySort.TITLE_DESC, LibrarySort.TITLE_DESC.forContentType(LibraryContentType.EPISODES))
        assertEquals("sort=latest_episode", LibrarySort.YEAR_DESC.query(television, LibraryContentType.EPISODES))
    }

    @Test fun titleDirectionsPreserveServerNaturalOrderAndTieOrderAcrossInterleavedPages() = runBlocking {
        // In particular, Part 2 and Part 10 must retain the server's collation.
        // Each direction also has an authoritative tie order, not an app reversal.
        val ascending = listOf(2, 10) + (1..125).filter { it != 2 && it != 10 }
        val descending = listOf(10, 2) + (125 downTo 1).filter { it != 2 && it != 10 }
        withServer(reply = { request ->
            assertEquals("/libraries/8/movies", request.requestUrl!!.encodedPath)
            body(rows(if (request.requestUrl!!.queryParameter("sort_dir") == "asc") ascending else descending))
        }) { client, requests ->
            val ascPages = mutableListOf<MediaPage>()
            val descPages = mutableListOf<MediaPage>()
            for (offset in listOf(0, 60, 120)) {
                ascPages += client.items(movies, offset = offset, sort = LibrarySort.TITLE_ASC)
                descPages += client.items(movies, offset = offset, sort = LibrarySort.TITLE_DESC)
            }
            assertEquals(ascending, ascPages.flatMap { it.items }.map { it.id })
            assertEquals(descending, descPages.flatMap { it.items }.map { it.id })
            assertTrue((ascPages + descPages).all { it.total == 125 })
            assertEquals(listOf(60, 60, 5), ascPages.map { it.items.size })
            val catalogs = requests.filter { it.requestUrl!!.encodedPath == "/libraries/8/movies" }
            assertEquals(2, catalogs.size)
            assertEquals(listOf("asc", "desc"), catalogs.map { it.requestUrl!!.queryParameter("sort_dir") })
            for (request in catalogs) {
                assertEquals("title", request.requestUrl!!.queryParameter("sort"))
                assertNull(request.requestUrl!!.queryParameter("offset"))
            }
            assertEquals(8, requests.size) // Two catalogs and progress for all six visible pages.
        }
    }

    @Test fun movieYearReleaseRatingAndAddedPreserveResponseOrderInsteadOfResortingMetadata() = runBlocking {
        val selections = listOf(
            LibrarySort.YEAR_ASC to "sort=year&sort_dir=asc",
            LibrarySort.YEAR_DESC to "sort=year&sort_dir=desc",
            LibrarySort.RELEASED_ASC to "sort=released_at&sort_dir=asc",
            LibrarySort.RELEASED_DESC to "sort=released_at&sort_dir=desc",
            LibrarySort.RATING to "sort=rating&sort_dir=desc",
            LibrarySort.RECENT to "sort=added&sort_dir=desc",
        )
        val response = """[{"id":3,"title":"Z","year":2024,"rating":1,"released_at":"2024-01-01"},{"id":1,"title":"A"},{"id":2,"title":"B","year":2020,"rating":9,"released_at":"2020-01-01"}]"""
        withServer(reply = { body(response) }) { client, requests ->
            for ((sort, expectedQuery) in selections) {
                val result = client.items(movies, sort = sort)
                assertEquals(sort.name, listOf(3, 1, 2), result.items.map { it.id })
                assertEquals(3, result.total)
                assertEquals(expectedQuery, requests.last { it.requestUrl!!.encodedPath == "/libraries/8/movies" }
                    .requestUrl!!.encodedQuery)
            }
            assertEquals(12, requests.size)
        }
    }

    @Test fun showsAddedAndLatestEpisodeRemainDifferentServerSelections() = runBlocking {
        withServer(reply = { request ->
            when (request.requestUrl!!.queryParameter("sort")) {
                "added" -> body(rows(listOf(1, 2)))
                "latest_episode" -> body(rows(listOf(2, 1)))
                else -> MockResponse().setResponseCode(400)
            }
        }) { client, requests ->
            val added = client.items(television, sort = LibrarySort.RECENT)
            val latest = client.items(television, sort = LibrarySort.LATEST_EPISODE)
            assertEquals(listOf(1, 2), added.items.map { it.id })
            assertEquals(listOf(2, 1), latest.items.map { it.id })
            val catalogs = requests.filter { it.requestUrl!!.encodedPath == "/libraries/9/shows" }
            assertEquals(listOf("sort=added&sort_dir=desc", "sort=latest_episode&sort_dir=desc"),
                catalogs.map { it.requestUrl!!.encodedQuery })
        }
    }

    @Test fun episodeDefaultAndUnsupportedSortShareLatestSnapshotButAddedGetsItsOwnCatalog() = runBlocking {
        withServer(reply = { request ->
            when (request.requestUrl!!.encodedPath) {
                "/libraries/9/episodes" -> body(rows(if (request.requestUrl!!.queryParameter("sort") == "added")
                    (1..65).toList() else (65 downTo 1).toList()))
                "/libraries/9/shows" -> body("[]")
                else -> MockResponse().setResponseCode(404)
            }
        }) { client, requests ->
            val first = client.items(television, contentType = LibraryContentType.EPISODES)
            val normalizedContinuation = client.items(television, offset = 60, sort = LibrarySort.YEAR_DESC,
                contentType = LibraryContentType.EPISODES)
            val added = client.items(television, sort = LibrarySort.RECENT, contentType = LibraryContentType.EPISODES)
            assertEquals((65 downTo 6).toList(), first.items.map { it.id })
            assertEquals((5 downTo 1).toList(), normalizedContinuation.items.map { it.id })
            assertEquals((1..60).toList(), added.items.map { it.id })
            assertTrue(listOf(first, normalizedContinuation, added).all { it.total == 65 })
            val catalogs = requests.filter { it.requestUrl!!.encodedPath == "/libraries/9/episodes" }
            assertEquals(listOf("sort=latest_episode", "sort=added"), catalogs.map { it.requestUrl!!.encodedQuery })
            assertEquals(7, requests.size) // Two catalogs, two facet lookups, three progress pages.
        }
    }

    @Test fun nestedTitleDescendingReversesTheWholeCatalogBeforeSearchAndPaging() = runBlocking {
        withServer(reply = { request ->
            when (request.requestUrl!!.encodedPath) {
                "/libraries/9/episodes" -> body(rows((1..135).toList()))
                "/libraries/9/shows" -> body("[]")
                else -> MockResponse().setResponseCode(404)
            }
        }) { client, requests ->
            val pages = listOf(0, 60, 120).map { offset ->
                client.items(television, offset = offset, query = "회차", sort = LibrarySort.TITLE_DESC,
                    contentType = LibraryContentType.EPISODES)
            }
            assertEquals((135 downTo 1).toList(), pages.flatMap { it.items }.map { it.id })
            assertEquals(listOf(60, 60, 15), pages.map { it.items.size })
            assertTrue(pages.all { it.total == 135 })
            val catalog = requests.single { it.requestUrl!!.encodedPath == "/libraries/9/episodes" }
            assertEquals("sort=title", catalog.requestUrl!!.encodedQuery)
            assertEquals(5, requests.size)
        }
    }

    private fun rows(ids: List<Int>): String = JSONArray(ids.map { id ->
        JSONObject().put("id", id).put("title", when (id) {
            2 -> "회차 Part 2"
            10 -> "회차 Part 10"
            else -> "회차 동률"
        }).put("show_id", 1)
    }).toString()

    private fun body(value: String) = MockResponse().setBody(value)

    private suspend fun withServer(reply: (RecordedRequest) -> MockResponse,
        test: suspend (ShyniClient, List<RecordedRequest>) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return if (request.requestUrl!!.encodedPath == "/playback/progress") body("{}") else reply(request)
                }
            }
            start()
        }
        try { test(ShyniClient(server.url("/").toString(), "synthetic-sort-test"), requests) }
        finally { server.shutdown() }
    }
}
