package io.mirr.shynigsy.ui

import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingPresentationTest {
    @Test fun remainingMinutesRoundUpIncludingTheLastMillisecond() {
        assertEquals(2L, continuePlaybackProgress(120_001, 60_000)!!.remainingMinutes)
        assertEquals(1L, continuePlaybackProgress(60_001, 60_000)!!.remainingMinutes)
        assertEquals(0L, continuePlaybackProgress(60_000, 60_000)!!.remainingMinutes)
        assertEquals(.25f, continuePlaybackProgress(2_400_000, 600_000)!!.fraction, .0001f)
    }

    @Test fun unknownAndInvalidTimingDoesNotProduceMadeUpProgress() {
        assertNull(continuePlaybackProgress(0, 0))
        assertNull(continuePlaybackProgress(-1, 0))
        assertNull(continuePlaybackProgress(100, -1))
        assertNull(continuePlaybackProgress(100, 0))
        assertNull(continuePlaybackProgress(100, 101))
        assertEquals("재생", continueEpisodeCaption(MediaItem(1, 1, "영화", "movie")))
    }

    @Test fun veryLongDurationsDoNotOverflowCeilingOrSubtraction() {
        val maximum = continuePlaybackProgress(Long.MAX_VALUE, 1)!!
        assertEquals((Long.MAX_VALUE - 1) / 60_000 + 1, maximum.remainingMinutes)
        assertTrue(maximum.fraction > 0f)
        val finalMillisecond = continuePlaybackProgress(Long.MAX_VALUE, Long.MAX_VALUE - 1)!!
        assertEquals(1L, finalMillisecond.remainingMinutes)
        assertTrue(finalMillisecond.fraction in 0f..1f)
    }

    @Test fun episodeCaptionKeepsSpecialZeroAndAvoidsRepeatingServerSeasonEpisodePrefix() {
        val episode = MediaItem(1, 1, "회차 제목", "episode", seasonNumber = 0, episodeNumber = 0)
        assertEquals("S0 E0 · 회차 제목", continueEpisodeCaption(episode))
        assertEquals("S0 E0 · 회차 제목", continueEpisodeCaption(episode.copy(displaySubtitle = "S0 E0 · 서버 회차 제목")))
        assertEquals("S0 E0 · 서버 회차 제목", continueEpisodeCaption(episode.copy(title = "시리즈", showTitle = "시리즈",
            displaySubtitle = "S0 E0 · 서버 회차 제목")))
        assertEquals("S0 E0 · 서버 회차 제목", continueEpisodeCaption(episode.copy(title = "", displaySubtitle = "서버 회차 제목")))
        assertEquals("제목만 있는 회차", continueEpisodeCaption(episode.copy(seasonNumber = null, episodeNumber = null,
            title = "제목만 있는 회차")))
    }
}
