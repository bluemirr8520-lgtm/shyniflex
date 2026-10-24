package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowseOptionsTest {
    @Test fun metadataParsesNamedGenresAndRealYearsWithoutInventingValues() {
        val row = JSONObject("""{"genres":[" 액션 ",{"name":"드라마"},"액션"],"year":2024}""")
        assertEquals(listOf("액션", "드라마"), mediaGenres(row))
        assertEquals(2024, mediaYear(row))
        assertNull(mediaYear(JSONObject("""{"year":0}""")))
        assertEquals(2021, mediaYear(JSONObject("""{"release_date":"2021-04-03"}""")))
        assertEquals(emptyList<String>(), mediaGenres(JSONObject()))
    }

    @Test fun genreAndYearFiltersIntersectAndKeepAllFacetChoices() {
        val all = listOf(MediaItem(1, 8, "A", "movie", year = 2020, genres = listOf("액션")),
            MediaItem(2, 8, "B", "movie", year = 2024, genres = listOf("액션", "드라마")),
            MediaItem(3, 8, "C", "movie"))
        val filtered = filteredCatalog(all, "액션", 2024)
        assertEquals(listOf(2), filtered.items.map { it.id })
        assertEquals(1, filtered.total)
        assertEquals(listOf(2024, 2020), filtered.years)
        assertEquals(setOf("액션", "드라마"), filtered.genres.toSet())
        assertEquals(3, filteredCatalog(all, null, null).total)
        assertEquals(0, filteredCatalog(all, "없는 장르", null).total)
        assertEquals(all[1], MediaItem.fromJson(all[1].toJson()))
    }

    @Test fun wholeCatalogIsFilteredBeforeLocalPaginationAndProgressEnrichment() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val rows = JSONArray()
            for (id in 1..135) rows.put(JSONObject().put("id", id).put("title", "Movie $id")
                .put("year", if (id % 2 == 1) 2024 else 2020).put("genres", JSONArray().put("액션")))
            server.enqueue(MockResponse().setBody(rows.toString()))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            val client = ShyniClient(server.url("/").toString(), "token")
            val first = client.items(Library(8, "영화", "movie"), sort = LibrarySort.RECENT, genre = "액션", year = 2024)
            val second = client.items(Library(8, "영화", "movie"), offset = 60, sort = LibrarySort.RECENT, genre = "액션", year = 2024)
            assertEquals(68, first.total)
            assertEquals(60, first.items.size)
            assertEquals(119, first.items.last().id)
            assertEquals(8, second.items.size)
            assertEquals(121, second.items.first().id)
            assertEquals(listOf(2024, 2020), first.years)
            assertEquals("/libraries/8/movies?sort=added&sort_dir=desc", server.takeRequest().path)
            server.takeRequest(); server.takeRequest()
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun titleAndRecentSortUseKnownMovieAndSeriesRoutes() {
        val movie = Library(8, "영화", "movie")
        val series = Library(9, "시리즈", "tv")
        assertEquals("sort=title&sort_dir=asc", LibrarySort.TITLE_ASC.query(movie))
        assertEquals("sort=title&sort_dir=desc", LibrarySort.TITLE_DESC.query(movie))
        assertEquals("sort=added&sort_dir=desc", LibrarySort.RECENT.query(series))
        assertEquals("sort=latest_episode&sort_dir=desc", LibrarySort.LATEST_EPISODE.query(series))
    }
}
