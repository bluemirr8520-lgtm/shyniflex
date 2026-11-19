package io.mirr.shynigsy.data

import io.mirr.shynigsy.player.defaultSubtitlesEnabled
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaOriginTest {
    private fun spec(item: MediaItem) = PlaybackSpec(item.title, "https://example.invalid/stream", itemId = item.id,
        libraryId = item.libraryId, partId = 7, sourceItem = item)
    private val movie = MediaItem(1, 3, "한국어로 번역된 제목", "movie")

    @Test fun parsesRealServerCountryFieldsAndStringEncodedExtra() {
        val client = ShyniClient("https://example.invalid", "fixture")
        for (key in listOf("countries_display", "country", "countries", "origin_countries", "production_countries")) {
            for (value in listOf<Any>("대한민국", JSONArray().put("KR"), JSONArray().put(JSONObject().put("iso_3166_1", "KR")))) {
                val extra = JSONObject().put(key, value).put("original_language", "ko")
                val row = JSONObject().put("id", 1).put("title", "합성 영화").put("extra", extra)
                assertEquals(listOf("KR"), client.parseMedia(row, 3, "movie").productionCountries)
                assertFalse(defaultSubtitlesEnabled(spec(client.parseMedia(row.put("extra", extra.toString()), 3, "movie"))))
            }
        }
    }

    @Test fun explicitCountriesTakePriorityOverOriginalLanguageAndDubSelection() {
        assertFalse(defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf("US", "KR"), originalLanguage = "en"))))
        assertTrue(defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf("US"), originalLanguage = "ko"))
            .copy(selectedAudioLanguage = "ko")))
        assertTrue(defaultSubtitlesEnabled(spec(movie).copy(selectedAudioLanguage = "kor")))
        assertTrue(defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf("KP")))))
    }

    @Test fun originalLanguageFallbackRequiresKoreanAndKnownPlayableType() {
        for (language in listOf("ko", "kor", "ko-KR", "KOR_kr", "한국어")) {
            assertFalse(defaultSubtitlesEnabled(spec(movie.copy(originalLanguage = language))))
            assertFalse(defaultSubtitlesEnabled(spec(movie.copy(type = "episode", originalLanguage = language))))
        }
        assertTrue(defaultSubtitlesEnabled(spec(movie.copy(originalLanguage = "ja"))))
        assertTrue(defaultSubtitlesEnabled(spec(movie.copy(type = "show", productionCountries = listOf("KR")))))
        assertTrue(defaultSubtitlesEnabled(spec(movie).copy(sourceItem = null)))
    }

    @Test fun doesNotGuessOriginFromTitlesGenresAudioOrSubtitleLanguages() {
        val item = movie.copy(title = "한국 드라마", summary = "대한민국 여행", genres = listOf("한국"))
        val row = item.asJson().put("language", "ko").put("audio_language", "ko").put("subtitle_language", "ko")
        assertTrue(mediaProductionCountries(row).isEmpty())
        assertNull(mediaOriginalLanguage(row))
        assertTrue(defaultSubtitlesEnabled(spec(item).copy(selectedAudioLanguage = "ko",
            subtitles = listOf(SubtitleTrack(1, "한국어", "https://example.invalid/sub", language = "ko")))))
    }

    @Test fun countryAndLanguageRoundTripThroughPlaybackSpecAndOlderPayloadsStayUnknown() {
        val item = movie.copy(productionCountries = listOf("KR"), originalLanguage = "ko")
        assertEquals(item, MediaItem.fromJson(item.toJson()))
        val restored = PlaybackSpec.fromJson(spec(item).toJson())
        assertEquals(item, restored.sourceItem)
        assertFalse(defaultSubtitlesEnabled(restored))
        val old = item.asJson().apply { remove("productionCountries"); remove("originalLanguage") }
        assertTrue(defaultSubtitlesEnabled(spec(MediaItem.fromJson(old))))
    }

    @Test fun homeSnapshotKeepsOriginWithoutAddingPlaybackOrAccountData() {
        val item = movie.copy(productionCountries = listOf("KR"), originalLanguage = "ko", resumeMs = 5_000)
        val scope = homeSnapshotScope("https://example.invalid", "fixture-token")!!
        val snapshot = HomeSnapshot(1_000, listOf(Library(3, "합성", "movie")), listOf(item), listOf(item),
            mapOf(3 to RecentLibraryStatus.READY))
        val bytes = encodeHomeSnapshot(scope, snapshot)!!
        val restored = decodeHomeSnapshot(scope, bytes, 1_001)!!
        assertEquals(item.productionCountries, restored.continueItems.single().productionCountries)
        assertEquals("ko", restored.recentItems.single().originalLanguage)
        assertFalse(defaultSubtitlesEnabled(spec(restored.continueItems.single())))
        assertFalse(String(bytes).contains("fixture-token"))
    }

    @Test fun detailOriginFlowsFromShowToSeasonAndEpisodeWithoutNewRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":100,"title":"합성 시리즈","extra":{"country":["KR"],"original_language":"ko"}}"""))
            server.enqueue(MockResponse().setBody("""[{"id":101,"season_number":1,"title":"시즌1"}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":102,"episode_number":1,"title":"1화"}]"""))
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            val show = client.details(MediaItem(100, 3, "합성 시리즈", "show"))
            val season = show.children.single()
            assertEquals(listOf("KR"), season.productionCountries)
            val episode = client.details(season).children.single()
            assertEquals(listOf("KR"), episode.productionCountries)
            assertEquals("ko", episode.originalLanguage)
            assertFalse(defaultSubtitlesEnabled(spec(episode)))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun malformedOrUnboundedOriginValuesDoNotBecomeKoreanEvidence() {
        assertTrue(mediaProductionCountries(JSONObject().put("country", "KR\nsecret")).isEmpty())
        assertTrue(mediaProductionCountries(JSONObject().put("country", "x".repeat(3_000))).isEmpty())
        assertTrue(mediaProductionCountries(JSONObject().put("country", "unknown")).isEmpty())
        assertNull(mediaOriginalLanguage(JSONObject().put("original_language", "ko\n".repeat(200))))
        assertEquals(listOf("KR"), mediaProductionCountries(JSONObject().put("countries", "[\"KR\"]")))
        assertEquals("ko-kr", mediaOriginalLanguage(JSONObject().put("original_language", "KO_KR")))
    }
}
