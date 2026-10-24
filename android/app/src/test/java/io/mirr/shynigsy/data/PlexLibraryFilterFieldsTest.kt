package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexLibraryFilterFieldsTest {
    @Test fun everySortHasOneFieldAndDirectionAndReversingTwiceRestoresIt() {
        assertEquals(listOf(LibrarySortField.TITLE, LibrarySortField.ADDED, LibrarySortField.YEAR,
            LibrarySortField.RELEASED_AT, LibrarySortField.RATING, LibrarySortField.LATEST_EPISODE),
            LibrarySortField.entries.toList())
        for (field in LibrarySortField.entries) {
            assertEquals(field, field.ascending.field)
            assertEquals(field, field.descending.field)
            assertEquals(field, field.defaultSort.field)
            assertEquals(field.isReversible, field.ascending.isAscending)
            assertFalse(field.descending.isAscending)
            assertEquals(field.descending, field.ascending.reversedDirection())
            assertEquals(field.ascending, field.descending.reversedDirection())
        }
        for (sort in LibrarySort.entries) assertEquals(sort, sort.reversedDirection().reversedDirection())
        assertEquals(LibrarySort.RECENT, LibrarySortField.ADDED.defaultSort)
    }

    @Test fun decadeIntersectsGenreYearAndWatchStateWithoutClassifyingUnknownValues() {
        val base = MediaItem(1, 8, "합성", "movie", year = 2024, genres = listOf("드라마"),
            watchState = MediaWatchState.UNWATCHED)
        val items = listOf(base, base.copy(id = 2, year = 1999), base.copy(id = 3, year = 2000),
            base.copy(id = 4, watchState = null), base.copy(id = 5, genres = listOf("액션")),
            base.copy(id = 6, year = null), base.copy(id = 7, watchState = MediaWatchState.WATCHED))
        val filtered = filteredCatalog(items, "드라마", 2024, LibraryWatchFilter.UNWATCHED, 2020)
        assertEquals(listOf(1), filtered.items.map { it.id })
        assertEquals(0, filteredCatalog(items, "드라마", 2024, LibraryWatchFilter.ALL, 1990).total)
        assertEquals(listOf(2), filteredCatalog(items, null, null, decade = 1990).items.map { it.id })
        assertEquals(listOf(3), filteredCatalog(items, null, null, decade = 2000).items.map { it.id })
        assertEquals(listOf(2024, 2000, 1999), filtered.years)
        assertEquals(setOf("액션", "드라마"), filtered.genres.toSet())
    }

    @Test fun noDecadeKeepsUnknownYearButSelectedDecadeRequiresARealYear() {
        val item = MediaItem(1, 8, "합성", "movie")
        assertEquals(1, filteredCatalog(listOf(item), null, null).total)
        assertEquals(0, filteredCatalog(listOf(item), null, null, decade = 2020).total)
    }

    @Test fun addedDateAcceptsOffsetIsoInstantsAndRejectsIncompleteInvalidOrNonStringValues() {
        assertEquals(1_704_067_200_000L, mediaAddedAt(JSONObject().put("added_at", "2024-01-01T00:00:00Z")))
        assertEquals(1_704_067_200_000L, mediaAddedAt(JSONObject().put("added_at", "2024-01-01T09:00:00+09:00")))
        assertNull(mediaAddedAt(JSONObject()))
        for (value in listOf<Any>(JSONObject.NULL, 2024, true, "", "2024", "2024-01-01", "2024-01-01T00:00:00",
            "2024-02-30T00:00:00Z", "2024-01-01T25:00:00Z", "not a date", JSONArray())) {
            assertNull(value.toString(), mediaAddedAt(JSONObject().put("added_at", value)))
        }
    }

    @Test fun availableFieldsAndTheirOrderMatchEachServerCatalog() {
        assertEquals(listOf(LibrarySortField.TITLE, LibrarySortField.ADDED, LibrarySortField.YEAR,
            LibrarySortField.RELEASED_AT, LibrarySortField.RATING), LibrarySortField.availableFor(LibraryContentType.MOVIES))
        assertEquals(listOf(LibrarySortField.TITLE, LibrarySortField.ADDED, LibrarySortField.LATEST_EPISODE),
            LibrarySortField.availableFor(LibraryContentType.SHOWS))
        assertEquals(listOf(LibrarySortField.TITLE, LibrarySortField.ADDED),
            LibrarySortField.availableFor(LibraryContentType.SEASONS))
        assertEquals(listOf(LibrarySortField.LATEST_EPISODE, LibrarySortField.TITLE, LibrarySortField.ADDED),
            LibrarySortField.availableFor(LibraryContentType.EPISODES))
        for (sort in listOf(LibrarySort.RECENT, LibrarySort.RATING, LibrarySort.LATEST_EPISODE)) {
            assertFalse(sort.field.isReversible)
            assertFalse(sort.isAscending)
            assertEquals(sort, sort.reversedDirection())
        }
    }

    @Test fun addedOrderStaysUnchangedThroughFilteringAndAllThreePages() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val rows = JSONArray((180 downTo 1).map { id -> JSONObject().put("id", id).put("title", "합성 $id")
                .put("year", if (id <= 135) 2024 else 1994).put("genres", JSONArray().put(if (id <= 150) "액션" else "코미디")) })
            server.enqueue(MockResponse().setBody(rows.toString()))
            repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
            val client = ShyniClient(server.url("/").toString(), "synthetic")
            val library = Library(8, "영화", "movie")
            suspend fun page(offset: Int) = client.items(library, offset = offset, sort = LibrarySort.RECENT,
                genre = "액션", year = 2024, decade = 2020)
            val first = page(0)
            val second = page(60)
            val last = page(120)
            assertEquals((135 downTo 76).toList(), first.items.map { it.id })
            assertEquals((75 downTo 16).toList(), second.items.map { it.id })
            assertEquals((15 downTo 1).toList(), last.items.map { it.id })
            assertTrue(listOf(first, second, last).all { it.total == 135 })
            assertEquals(listOf(2024, 1994), first.years)
            assertEquals("/libraries/8/movies?sort=added&sort_dir=desc", server.takeRequest().path)
            for (ids in listOf(135 downTo 76, 75 downTo 16, 15 downTo 1)) {
                assertEquals(ids.joinToString(","), server.takeRequest().requestUrl!!.queryParameter("item_ids"))
            }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun searchKeepsRelevanceOrderInsteadOfSortingPartialDateMetadata() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val rows = """[{"id":1,"title":"합성 미상"},{"id":2,"title":"합성 오래됨","added_at":"2020-01-01T00:00:00Z"},{"id":3,"title":"합성 최근","added_at":"2024-01-01T00:00:00Z"},{"id":4,"title":"합성 잘못된 날짜","added_at":"2024-02-30T00:00:00Z"}]"""
            repeat(2) { server.enqueue(MockResponse().setBody(rows)); server.enqueue(MockResponse().setBody("{}")) }
            val client = ShyniClient(server.url("/").toString(), "synthetic")
            val library = Library(8, "영화", "movie")
            val byTitle = client.items(library, query = "합성", sort = LibrarySort.TITLE_DESC)
            val newest = client.items(library, query = "합성", sort = LibrarySort.RECENT)
            assertEquals(listOf(1, 2, 3, 4), byTitle.items.map { it.id })
            assertEquals(listOf(1, 2, 3, 4), newest.items.map { it.id })
            assertNull(byTitle.items.last().addedAtMs)
            repeat(2) {
                val search = server.takeRequest().requestUrl!!
                assertEquals("/libraries/8/search", search.encodedPath)
                assertEquals("합성", search.queryParameter("q"))
                assertEquals("200", search.queryParameter("limit"))
                assertNull(search.queryParameter("sort"))
                assertNull(search.queryParameter("sort_dir"))
                server.takeRequest()
            }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun addedTimestampRoundTripsIncludingEpochAndPreservesPrivacyWithOldJsonCompatible() {
        for (timestamp in listOf<Long?>(null, 0, 1_704_067_200_000)) {
            val item = MediaItem(1, 8, "합성", "movie", isSpoiler = true, addedAtMs = timestamp)
            assertEquals(item, MediaItem.fromJson(item.toJson()))
        }
        assertNull(MediaItem.fromJson("""{"id":1,"libraryId":8,"title":"합성","type":"movie"}""").addedAtMs)
    }

    @Test fun searchWithoutTimestampsRetainsUnknownOrderRatherThanInventingDates() {
        val items = listOf(MediaItem(2, 8, "B", "movie"), MediaItem(1, 8, "A", "movie"))
        assertEquals(items, sortedCatalog(items, LibrarySort.RECENT, LibraryContentType.MOVIES, search = true))
        assertEquals(items, sortedCatalog(items, LibrarySort.TITLE_DESC, LibraryContentType.MOVIES, search = true))
    }
}
