package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.*
import org.junit.Test

class EpisodeCompletionNavigationTest {
    private val episode = MediaItem(42, 8, "episode", "episode", viewItemId = 51, showId = 19, seasonNumber = 2)
    private val show = MediaItem(19, 8, "show", "show", viewItemId = 51)
    private val detail = MediaDetails(show, listOf(episode))

    @Test fun restoresExistingSeriesFromEpisodeDetailOrLaterEpisode() {
        val stack = listOf(detail, MediaDetails(episode))
        assertEquals(0, completedEpisodeSeriesIndex(stack, episode))
        assertEquals(0, completedEpisodeSeriesIndex(stack, episode.copy(id = 43)))
        assertTrue(verifiedEpisodeInSeries(detail, episode))
    }

    @Test fun rejectsWrongLibraryViewShowOrPrivateScope() {
        val alternatives = listOf(episode.copy(libraryId = 9), episode.copy(viewItemId = 52),
            episode.copy(showId = 20), episode.copy(isSpoiler = true), episode.copy(viewItemId = null))
        alternatives.forEach { other ->
            assertNull(completedEpisodeSeriesIndex(listOf(detail), other))
            assertFalse(verifiedEpisodeInSeries(detail, other))
        }
    }

    @Test fun missingShowMayUseOnlyAnExactExistingChild() {
        val noShow = episode.copy(showId = null)
        assertEquals(0, completedEpisodeSeriesIndex(listOf(detail), noShow))
        assertNull(completedEpisodeSeriesIndex(listOf(detail), noShow.copy(id = 999)))
        assertFalse(verifiedEpisodeInSeries(detail, noShow))
    }

    @Test fun freshSeriesRequiresMatchingEpisodeAndView() {
        assertFalse(verifiedEpisodeInSeries(detail.copy(children = emptyList()), episode))
        assertFalse(verifiedEpisodeInSeries(detail.copy(children = listOf(episode.copy(viewItemId = 99))), episode))
        assertFalse(verifiedEpisodeInSeries(detail.copy(item = show.copy(id = 20)), episode))
        assertFalse(verifiedEpisodeInSeries(detail.copy(children = listOf(episode.copy(id = 99))), episode))
    }

    @Test fun privateGrantRotationRequiresFreshServerVerification() {
        val privateEpisode = episode.copy(isSpoiler = true)
        assertTrue(mayReuseCompletedSeries(privateEpisode, 3, 3))
        assertFalse(mayReuseCompletedSeries(privateEpisode, 3, 4))
        assertFalse(mayReuseCompletedSeries(privateEpisode, null, 4))
        assertTrue(mayReuseCompletedSeries(episode, null, 4))
    }
}
