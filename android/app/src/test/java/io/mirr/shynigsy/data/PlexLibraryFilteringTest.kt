package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexLibraryFilteringTest {
    @Test fun watchStatusGenreAndYearIntersectAndUnknownHistoryIsNotUnwatched() {
        val base = MediaItem(1, 8, "합성 영화", "movie", year = 2024, genres = listOf("드라마"))
        val items = listOf(base.copy(watchState = MediaWatchState.UNWATCHED),
            base.copy(id = 2, watchState = MediaWatchState.IN_PROGRESS, resumeMs = 3_000),
            base.copy(id = 3, watchState = MediaWatchState.WATCHED, watched = true),
            base.copy(id = 4), base.copy(id = 5, year = 2020, watchState = MediaWatchState.WATCHED))
        assertEquals(listOf(1, 2), filteredCatalog(items, "드라마", 2024, LibraryWatchFilter.UNWATCHED).items.map { it.id })
        assertEquals(listOf(2), filteredCatalog(items, "드라마", 2024, LibraryWatchFilter.IN_PROGRESS).items.map { it.id })
        assertEquals(listOf(3), filteredCatalog(items, "드라마", 2024, LibraryWatchFilter.WATCHED).items.map { it.id })
        assertEquals(5, filteredCatalog(items, null, null).total)
        assertEquals(listOf(2024, 2020), filteredCatalog(items, null, null, LibraryWatchFilter.WATCHED).years)
    }

    @Test fun missingYearAndRuntimeStayLastForBothDirections() {
        val items = listOf(MediaItem(1, 8, "unknown", "movie"),
            MediaItem(2, 8, "older", "movie", durationMs = 20_000, year = 2020),
            MediaItem(3, 8, "newer", "movie", durationMs = 10_000, year = 2024))
        fun ids(sort: LibrarySort) = sortedCatalog(items, sort, LibraryContentType.MOVIES).map { it.id }
        assertEquals(listOf(3, 2, 1), ids(LibrarySort.YEAR_DESC))
        assertEquals(listOf(2, 3, 1), ids(LibrarySort.YEAR_ASC))
        assertEquals(listOf(2, 3, 1), ids(LibrarySort.DURATION_DESC))
        assertEquals(listOf(3, 2, 1), ids(LibrarySort.DURATION_ASC))
    }

    @Test fun tvKindsUseRealLibraryRoutesAndMovieLibrariesCannotSelectEpisodes() = runBlocking {
        val tv = Library(9, "TV", "tv")
        assertEquals(LibraryContentType.SHOWS, LibraryContentType.defaultFor(tv))
        assertEquals(listOf(LibraryContentType.SHOWS, LibraryContentType.SEASONS, LibraryContentType.EPISODES), LibraryContentType.availableFor(tv))
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[]"))
            val client = ShyniClient(server.url("/").toString(), "synthetic")
            assertTrue(client.items(tv, contentType = LibraryContentType.SEASONS).items.isEmpty())
            assertTrue(client.items(tv, contentType = LibraryContentType.EPISODES, sort = LibrarySort.RECENT).items.isEmpty())
            assertEquals("/libraries/9/seasons?sort=title", server.takeRequest().path)
            assertEquals("/libraries/9/episodes?sort=latest_episode&sort_dir=desc", server.takeRequest().path)
            val failure = runCatching { client.items(Library(8, "영화", "movie"), contentType = LibraryContentType.EPISODES) }.exceptionOrNull()
            assertTrue(failure is ApiException)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun movieWatchFilterRunsBeforePagingAndReusesItsExactHistorySnapshot() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val rows = JSONArray((1..135).map { JSONObject().put("id", it).put("title", "합성 $it").put("year", 2024) })
            server.enqueue(MockResponse().setBody(rows.toString()))
            for (ids in (1..135).toList().chunked(100)) {
                server.enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray(ids.filter { it % 2 == 1 }
                    .map { JSONObject().put("metadata_item_id", it).put("completed", true) })).toString()))
            }
            val client = ShyniClient(server.url("/").toString(), "synthetic")
            val library = Library(8, "영화", "movie")
            val first = client.items(library, watchFilter = LibraryWatchFilter.WATCHED, year = 2024)
            val next = client.items(library, offset = 60, watchFilter = LibraryWatchFilter.WATCHED, year = 2024)
            assertEquals(68, first.total)
            assertEquals((1..119 step 2).toList(), first.items.map { it.id })
            assertEquals((121..135 step 2).toList(), next.items.map { it.id })
            assertEquals("/libraries/8/movies?sort=title", server.takeRequest().path)
            repeat(2) { assertEquals("8", server.takeRequest().requestUrl!!.queryParameter("library_view_id")) }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun seriesWatchStateUsesAllAuthorizedEpisodesAndMatchesViewIdentityAcrossVersions() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"id":1,"title":"전체 시청","view_item_id":50},{"id":2,"title":"일부 시청","view_item_id":60},{"id":3,"title":"미시청","view_item_id":70}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":11,"show_id":101,"view_item_id":50},{"id":12,"show_id":102,"view_item_id":50},{"id":21,"show_id":2,"view_item_id":60},{"id":22,"show_id":2,"view_item_id":60},{"id":31,"show_id":3,"view_item_id":70}]"""))
            server.enqueue(MockResponse().setBody("""{"items":[{"metadata_item_id":11,"completed":true},{"metadata_item_id":12,"completed":true},{"metadata_item_id":21,"completed":true}]}"""))
            val client = ShyniClient(server.url("/").toString(), "synthetic")
            val page = client.items(Library(9, "TV", "tv"), watchFilter = LibraryWatchFilter.WATCHED)
            assertEquals(listOf(1), page.items.map { it.id })
            assertEquals(MediaWatchState.WATCHED, page.items.single().watchState)
            assertEquals("/libraries/9/shows?sort=title", server.takeRequest().path)
            assertEquals("/libraries/9/episodes?sort=title", server.takeRequest().path)
            assertEquals("11,12,21,22,31", server.takeRequest().requestUrl!!.queryParameter("item_ids"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun optionalIdentityFailureNeverTurnsUnknownRowsIntoUnwatched() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"id":11,"title":"합성 회차","view_item_id":50,"show_id":1}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":1,"title":"합성 쇼","view_item_id":50}]"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"에피소드의 정체성을 확인할 수 없습니다."}"""))
            val page = ShyniClient(server.url("/").toString(), "synthetic").items(Library(9, "TV", "tv"),
                contentType = LibraryContentType.EPISODES, watchFilter = LibraryWatchFilter.UNWATCHED)
            assertEquals(0, page.total)
            assertTrue(page.items.isEmpty())
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun seasonsInheritOnlyMatchingViewFacetsAndKeepSpecialSeasonZero() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"id":7,"title":"특별편","logical_season_no":0,"show_id":100,"view_item_id":50,"episode_count":2}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":1,"title":"같은 View","view_item_id":50,"year":2024,"genres":["드라마"]},{"id":100,"title":"다른 View","view_item_id":60,"year":2000,"genres":["액션"]}]"""))
            val page = ShyniClient(server.url("/").toString(), "synthetic").items(Library(9, "TV", "tv"),
                contentType = LibraryContentType.SEASONS, genre = "드라마", year = 2024)
            val season = page.items.single()
            assertEquals("season", season.type)
            assertEquals(0, season.seasonNumber)
            assertEquals(2, season.episodeCount)
            assertEquals(2024, season.year)
            assertEquals(listOf("드라마"), season.genres)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun permissionFailuresStayFailuresRatherThanAnEmptyWatchedResult() = runBlocking {
        for (status in listOf(401, 403)) {
            val server = MockWebServer().apply { start() }
            try {
                server.enqueue(MockResponse().setBody("""[{"id":1,"title":"합성 영화"}]"""))
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"허용되지 않음"}"""))
                val failure = runCatching { ShyniClient(server.url("/").toString(), "synthetic")
                    .items(Library(8, "영화", "movie"), watchFilter = LibraryWatchFilter.UNWATCHED) }.exceptionOrNull()
                assertEquals(status, (failure as ApiException).status)
                assertEquals(2, server.requestCount)
            } finally { server.shutdown() }
        }
    }

    @Test fun lockedPrivateLibraryCannotFetchAnyNewTypeOrWatchFilter() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val client = ShyniClient(server.url("/").toString(), "synthetic")
            for (kind in LibraryContentType.availableFor(Library(9, "private", "tv"))) {
                val failure = runCatching { client.items(Library(9, "private", "tv", isSpoiler = true),
                    contentType = kind, watchFilter = LibraryWatchFilter.UNWATCHED) }.exceptionOrNull()
                assertEquals(403, (failure as ApiException).status)
            }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun aggregateDoesNotClaimEmptyOrPartiallyUnknownSeriesIsUnwatched() {
        val show = MediaItem(1, 9, "합성 쇼", "show")
        val watched = MediaItem(11, 9, "합성 회차", "episode", watched = true, watchState = MediaWatchState.WATCHED)
        assertNull(aggregateWatchState(show, emptyList()).watchState)
        assertNull(aggregateWatchState(show, listOf(watched, watched.copy(id = 12, watchState = null))).watchState)
        assertEquals(MediaWatchState.IN_PROGRESS, aggregateWatchState(show,
            listOf(watched, watched.copy(id = 12, watched = false, watchState = MediaWatchState.UNWATCHED))).watchState)
    }

    @Test fun newWatchMetadataRoundTripsWithoutLosingPrivacyAndOldJsonRemainsCompatible() {
        val item = MediaItem(1, 9, "합성", "show", isSpoiler = true, watchState = MediaWatchState.IN_PROGRESS, episodeCount = 5)
        assertEquals(item, MediaItem.fromJson(item.toJson()))
        val old = MediaItem.fromJson("""{"id":1,"libraryId":9,"title":"합성","type":"show"}""")
        assertNull(old.watchState)
        assertNull(old.episodeCount)
    }
}
