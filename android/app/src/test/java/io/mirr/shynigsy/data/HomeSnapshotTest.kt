package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeSnapshotTest {
    private val now = 1_800_000_000_000L
    private val scope = homeSnapshotScope("https://server.example", "fixture-session")!!
    private val movies = Library(1, "영화", "movie")
    private val shows = Library(2, "시리즈", "tv")
    private val movie = MediaItem(10, 1, "영화 제목", "movie", viewItemId = 110,
        poster = "https://server.example/poster/10.jpg", durationMs = 9_000_000, resumeMs = 200_000,
        watchState = MediaWatchState.IN_PROGRESS)
    private val episode = MediaItem(20, 2, "회차", "episode", viewItemId = 220, showId = 200,
        seasonNumber = 1, episodeNumber = 6, showTitle = "시리즈 제목", summary = "설명",
        displaySubtitle = "제6화", durationMs = 3_600_000, resumeMs = 800_000,
        backdrop = "https://image.example/art.jpg?w=1280&quality=90", thumbnail = "https://server.example/thumb.jpg",
        year = 2026, genres = listOf("드라마"), episodeCount = 12, addedAtMs = now - 100,
        watchState = MediaWatchState.IN_PROGRESS, markers = listOf(PlaybackMarker("intro", 0, 5000)),
        folderKey = "private-folder-digest", navigationPartId = 901, playbackPartId = 902)
    private fun snapshot() = HomeSnapshot(now, listOf(movies, shows), listOf(movie), listOf(episode),
        mapOf(1 to RecentLibraryStatus.READY, 2 to RecentLibraryStatus.EMPTY))
    private fun encoded(value: HomeSnapshot = snapshot()) = encodeHomeSnapshot(scope, value)!!
    private fun decoded(value: HomeSnapshot = snapshot()) = decodeHomeSnapshot(scope, encoded(value), now)!!

    @Test fun scopeUsesNormalizedHttpsOriginAndExactSessionWithoutPlaintextCredentials() {
        assertEquals(scope, homeSnapshotScope(" HTTPS://SERVER.example:443/ ", "fixture-session"))
        assertNotEquals(scope, homeSnapshotScope("https://server.example:8443", "fixture-session"))
        assertNotEquals(scope, homeSnapshotScope("https://another.example", "fixture-session"))
        assertNotEquals(scope, homeSnapshotScope("https://server.example", "another-session"))
        assertTrue(scope.matches(Regex("[a-f0-9]{64}")))
        assertFalse(scope.contains("fixture"))
        for (address in listOf("http://server.example", "https://user:password@server.example",
                "https://server.example/path", "https://server.example?token=secret", "https://server.example#fragment")) {
            assertNull(homeSnapshotScope(address, "fixture-session"))
        }
        assertNull(homeSnapshotScope("https://server.example", ""))
        assertNull(homeSnapshotScope("https://server.example", null))
        assertNull(homeSnapshotScope("https://server.example", "fixture\nvalue"))
    }

    @Test fun roundTripKeepsDisplayIdentityProgressAndArtworkButNotPlaybackHints() {
        val result = decoded()
        assertEquals(snapshot().libraries, result.libraries)
        assertEquals(listOf(movie), result.recentItems)
        assertEquals(listOf(episode.copy(markers = emptyList(), folderKey = null,
            navigationPartId = null, playbackPartId = null)), result.continueItems)
        assertEquals(snapshot().recentLibraryStatus, result.recentLibraryStatus)
        assertEquals(now, result.savedAtMs)
        val payload = String(encoded(), Charsets.UTF_8)
        for (forbidden in listOf("fixture-session", "private-folder-digest", "navigationPartId", "playbackPartId", "markers")) {
            assertFalse(forbidden, payload.contains(forbidden))
        }
    }

    @Test fun privateDuplicateAndUnknownLibraryRowsNeverReachTheStoredPayload() {
        val privateLibrary = Library(3, "비공개 라이브러리 이름", "movie", isSpoiler = true)
        val source = snapshot().copy(libraries = listOf(movies, shows, privateLibrary.copy(isSpoiler = false), privateLibrary),
            recentItems = listOf(movie, movie.copy(id = 11, isSpoiler = true, title = "비공개 작품"),
                movie.copy(id = 12, libraryId = 3, title = "비공개 중복"), movie.copy(id = 13, libraryId = 99, title = "권한 없음")),
            continueItems = listOf(episode, movie.copy(libraryId = 3)),
            recentLibraryStatus = mapOf(1 to RecentLibraryStatus.READY, 3 to RecentLibraryStatus.READY, 99 to RecentLibraryStatus.READY))
        val result = decoded(source)
        assertEquals(listOf(movies, shows), result.libraries)
        assertEquals(listOf(movie), result.recentItems)
        assertEquals(listOf(episode.id), result.continueItems.map { it.id })
        assertEquals(setOf(1), result.recentLibraryStatus.keys)
        val payload = String(encoded(source), Charsets.UTF_8)
        assertFalse(payload.contains("비공개")); assertFalse(payload.contains("권한 없음"))
    }

    @Test fun conflictingTypesAndRowsInTheWrongLibraryAreDiscarded() {
        val source = snapshot().copy(libraries = listOf(movies, shows, movies.copy(type = "tv")),
            recentItems = listOf(movie, movie.copy(libraryId = 2), episode.copy(type = "show")))
        val result = decoded(source)
        assertEquals(listOf(shows), result.libraries)
        assertEquals(listOf(episode.id), result.recentItems.map { it.id })
    }

    @Test fun signedArtworkAndStreamUrlsAreExcludedWithoutChangingSafeCoilKeys() {
        val safe = "https://IMAGE.example:443/thumb.jpg?w=640&h=360&format=webp"
        assertEquals(safe, homeSnapshotArtworkUrl(safe))
        assertEquals("http://image.example/thumb.jpg", homeSnapshotArtworkUrl("http://image.example/thumb.jpg"))
        for (url in listOf("file:///private/poster.jpg", "https://user:secret@image.example/a.jpg",
                "https://image.example/a.jpg#secret", "https://image.example/a.jpg?token=secret",
                "https://image.example/a.jpg?X-Amz-Signature=secret", "https://image.example/a.jpg?api_key=secret",
                "https://image.example/a.jpg?width=secret", "https://image.example/a.jpg?width=0",
                "https://image.example/a.jpg?w=12&w=24", "https://image.example/playback/secret",
                "https://image.example/%73tream/secret", "https://image.example/video.m3u8")) {
            assertNull(url, homeSnapshotArtworkUrl(url))
        }
        val result = decoded(snapshot().copy(recentItems = listOf(movie.copy(poster = "https://server.example/a.jpg?token=secret",
            backdrop = "https://server.example/download/stream-secret", thumbnail = safe))))
        assertNull(result.recentItems.single().poster); assertNull(result.recentItems.single().backdrop)
        assertEquals(safe, result.recentItems.single().thumbnail)
        assertFalse(String(encoded(result), Charsets.UTF_8).contains("secret"))
    }

    @Test fun cacheMustMatchSessionSchemaAndAValidTwentyFourHourTimestamp() {
        val bytes = encoded()
        assertNotNull(decodeHomeSnapshot(scope, bytes, now + HOME_SNAPSHOT_MAX_AGE_MS))
        assertNull(decodeHomeSnapshot(scope, bytes, now + HOME_SNAPSHOT_MAX_AGE_MS + 1))
        assertNull(decodeHomeSnapshot(scope, bytes, now - 1))
        assertNull(decodeHomeSnapshot(scope, bytes, 0))
        assertNull(decodeHomeSnapshot("b".repeat(64), bytes, now))
        assertNull(decodeHomeSnapshot("invalid", bytes, now))
        assertNull(encodeHomeSnapshot("invalid", snapshot()))
        assertNull(encodeHomeSnapshot(scope, snapshot().copy(savedAtMs = 0)))
        val unsupported = JSONObject(String(bytes, Charsets.UTF_8)).put("schema", 2).toString().toByteArray()
        assertNull(decodeHomeSnapshot(scope, unsupported, now))
    }

    @Test fun damagedUtf8JsonNumbersArraysAndUnknownStatusesAreCacheMisses() {
        assertNull(decodeHomeSnapshot(scope, byteArrayOf(0xc3.toByte(), 0x28), now))
        assertNull(decodeHomeSnapshot(scope, "{broken".toByteArray(), now))
        assertNull(decodeHomeSnapshot(scope, ByteArray(HOME_SNAPSHOT_MAX_BYTES + 1), now))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", 1.5) },
            { it.put("savedAtMs", "1800000000000") },
            { it.getJSONArray("recentItems").getJSONObject(0).put("id", 2_147_483_648L) },
            { it.getJSONArray("recentItems").getJSONObject(0).put("id", 10.5) },
            { it.getJSONArray("recentItems").getJSONObject(0).put("watched", "true") },
            { it.getJSONArray("recentItems").getJSONObject(0).put("summary", JSONObject()) },
            { it.getJSONObject("recentLibraryStatus").put("1", "UNKNOWN") },
            { it.put("libraries", JSONArray((1..129).map { id -> JSONObject().put("id", id) })) },
            { it.put("continueItems", JSONArray((1..31).map { JSONObject() })) },
        )
        mutations.forEach { mutate ->
            val data = JSONObject(String(encoded(), Charsets.UTF_8)).also(mutate).toString().toByteArray()
            assertNull(decodeHomeSnapshot(scope, data, now))
        }
    }

    @Test fun cachedDataIsSanitizedAgainWhenReadAndCannotInjectPrivateRowsOrFileHints() {
        val value = JSONObject(String(encoded(), Charsets.UTF_8))
        value.getJSONArray("recentItems").getJSONObject(0).put("isSpoiler", true)
        value.getJSONArray("continueItems").getJSONObject(0)
            .put("navigationPartId", 999).put("playbackPartId", 998).put("folderKey", "hidden")
        val result = decodeHomeSnapshot(scope, value.toString().toByteArray(), now)!!
        assertTrue(result.recentItems.isEmpty())
        assertNull(result.continueItems.single().navigationPartId)
        assertNull(result.continueItems.single().playbackPartId)
        assertNull(result.continueItems.single().folderKey)
        value.getJSONArray("libraries").getJSONObject(1).put("isSpoiler", true)
        assertTrue(decodeHomeSnapshot(scope, value.toString().toByteArray(), now)!!.continueItems.isEmpty())
    }

    @Test fun snapshotsBoundLibraryRowsContinueRowsAndDuplicatesInLibraryOrder() {
        val libraryList = (1..140).map { Library(it, "목록 $it", "movie") }
        val items = libraryList.flatMap { library -> (1..25).map { index ->
            movie.copy(id = index, libraryId = library.id, viewItemId = index + 1000)
        } }
        val source = HomeSnapshot(now, libraryList, items + items,
            items.take(40) + listOf(movie.copy(watched = true), movie.copy(resumeMs = 0)),
            libraryList.associate { it.id to RecentLibraryStatus.READY })
        val result = decoded(source)
        assertEquals(128, result.libraries.size)
        assertEquals(128 * 20, result.recentItems.size)
        assertTrue(result.recentItems.groupingBy { it.libraryId }.eachCount().all { it.value == 20 })
        assertEquals((1..128).toList(), result.recentItems.map { it.libraryId }.distinct())
        assertEquals(30, result.continueItems.size)
        assertEquals(128, result.recentLibraryStatus.size)
    }

    @Test fun knownEmptyCatalogAndSectionStatusesArePreserved() {
        val empty = HomeSnapshot(now, emptyList(), emptyList(), emptyList(), emptyMap())
        assertEquals(empty, decoded(empty))
        val sections = snapshot().copy(recentItems = emptyList(), continueItems = emptyList(),
            recentLibraryStatus = mapOf(1 to RecentLibraryStatus.EMPTY, 2 to RecentLibraryStatus.ERROR))
        assertEquals(sections, decoded(sections))
    }

    @Test fun completedAndNonPlayableContinueRowsDoNotReturnAfterRelaunch() {
        val source = snapshot().copy(continueItems = listOf(episode, movie.copy(watched = true),
            movie.copy(watchState = MediaWatchState.WATCHED), movie.copy(resumeMs = 0), episode.copy(type = "show")))
        assertEquals(listOf(episode.id), decoded(source).continueItems.map { it.id })
    }

    @Test fun hugeDisplayMetadataNeverProducesAnOversizedCache() {
        val libraries = (1..128).map { Library(it, "목록 $it", "movie") }
        val longImage = "https://server.example/" + "x".repeat(1900) + ".jpg"
        val rows = libraries.flatMap { library -> (1..20).map { index -> movie.copy(id = index,
            libraryId = library.id, poster = longImage, backdrop = longImage, thumbnail = longImage) } }
        assertNull(encodeHomeSnapshot(scope, HomeSnapshot(now, libraries, rows, emptyList(), emptyMap())))
    }
}
