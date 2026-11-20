package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import org.junit.Assert.*
import org.junit.Test

class DefaultSubtitlePolicyTest {
    private val movie = MediaItem(1, 3, "한국어로 표시된 합성 작품", "movie")
    private fun spec(item: MediaItem = movie) = PlaybackSpec(item.title, "https://example.invalid/stream",
        itemId = item.id, libraryId = item.libraryId, partId = 7, sourceItem = item)

    @Test fun onlyExplicitCurrentAudioCanSupplyTheMissingOriginFallback() {
        val source = spec().copy(selectedAudioLanguage = "kor", selectedSubtitleId = 11,
            subtitles = listOf(SubtitleTrack(11, "한국어", "https://example.invalid/sub", language = "kor")))
        assertTrue("Existing callers preserve the unknown-origin default", defaultSubtitlesEnabled(source))
        assertFalse(defaultSubtitlesEnabled(source, selectedAudioLanguage = "kor"))
        assertEquals("A caption preference must not invent Korean production metadata", movie, source.sourceItem)
    }

    @Test fun currentKoreanAudioDisablesAutomaticCaptionsForUnknownMoviesAndEpisodes() {
        for (type in listOf("movie", "episode")) {
            for (language in listOf(null, "und", "unk", "mul", "zxx", "UND-kr")) {
                val source = spec(movie.copy(type = type, originalLanguage = language))
                assertTrue("Unknown metadata alone keeps the previous behavior", defaultSubtitlesEnabled(source))
                for (audio in listOf("ko", "kor", "ko-KR", "KOR_kr", " ko "))
                    assertFalse("$type/$language/$audio", defaultSubtitlesEnabled(source, audio))
            }
        }
    }

    @Test fun foreignCountryOrOriginalLanguagePreservesSubtitlesForAKoreanDub() {
        for (country in listOf("US", "JP", "GB", "KP")) {
            assertTrue(defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf(country))), "ko"))
        }
        for (language in listOf("en", "eng", "ja", "jpn", "EN-us")) {
            assertTrue(defaultSubtitlesEnabled(spec(movie.copy(originalLanguage = language)), "kor"))
        }
        assertTrue("Explicit foreign country still takes priority over a contradictory language",
            defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf("US"), originalLanguage = "ko")), "ko"))
    }

    @Test fun knownKoreanOriginKeepsCaptionsOffRegardlessOfSelectedAudio() {
        assertFalse(defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf("KR"))), "en"))
        assertFalse(defaultSubtitlesEnabled(spec(movie.copy(productionCountries = listOf("US", "KR"))), null))
        assertFalse(defaultSubtitlesEnabled(spec(movie.copy(originalLanguage = "ko")), "ja"))
    }

    @Test fun absentOrUnrecognizedAudioCannotDisableCaptions() {
        for (audio in listOf(null, "", "und", "en", "eng", "ja", "ko,en", "ko--KR", "한국 드라마"))
            assertTrue("Unknown selected audio: $audio", defaultSubtitlesEnabled(spec(), audio))
        assertTrue(defaultSubtitlesEnabled(spec(movie.copy(type = "show")), "ko"))
        assertTrue(defaultSubtitlesEnabled(spec().copy(sourceItem = null), "ko"))
    }

    @Test fun explicitUnknownCountryIsMissingMetadataAndDoesNotBlockTheFallback() {
        for (country in listOf("국가 없음", "unknown", "미상", "N/A")) {
            val source = spec(movie.copy(productionCountries = listOf(country)))
            assertTrue(defaultSubtitlesEnabled(source))
            assertFalse(defaultSubtitlesEnabled(source, "ko"))
        }
    }
}
