package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState
import io.mirr.shynigsy.player.PlaybackCompletionSignals.ConfirmedWatch
import org.junit.Assert.*
import org.junit.Test

class ConfirmedPlaybackWatchTest {
    private val episode = MediaItem(23, 8, "episode", "episode", viewItemId = 501, resumeMs = 40_000)
    private fun event(item: MediaItem = episode, grant: Long? = null) =
        ConfirmedWatch(1, "scope", item, grant, 4, 1_000)

    @Test fun confirmedEpisodeIsAppliedImmediatelyOnlyToTheSameViewAndRemovedFromContinue() {
        val another = episode.copy(viewItemId = 502)
        val unknown = episode.copy(viewItemId = null)
        val show = MediaItem(1, 8, "show", "show", viewItemId = 501)
        val before = AppState(items = listOf(episode, another, unknown), continueItems = listOf(episode, another),
            detailStack = listOf(MediaDetails(show, children = listOf(episode, another))))
        val after = applyConfirmedPlaybackWatch(before, episode)
        assertEquals(MediaWatchState.WATCHED, after.items[0].watchState)
        assertEquals(0L, after.items[0].resumeMs)
        assertEquals(another, after.items[1]); assertEquals(unknown, after.items[2])
        assertEquals(listOf(another), after.continueItems)
        assertTrue(after.detailStack.single().children.first().watched)
        assertFalse(after.detailStack.single().children.last().watched)
    }

    @Test fun accountManualChoiceAndAgeFenceLateConfirmations() {
        val event = event()
        assertTrue(isCurrentPlaybackConfirmation(event, "scope", true, 3, false, 2_000))
        assertFalse(isCurrentPlaybackConfirmation(event, "other", true, 3, false, 2_000))
        assertFalse(isCurrentPlaybackConfirmation(event, "scope", false, 3, false, 2_000))
        assertFalse(isCurrentPlaybackConfirmation(event, "scope", true, 3, false, 60_000_001_001L))
    }

    @Test fun privateConfirmationRequiresTheSameActiveGrant() {
        val event = event(episode.copy(isSpoiler = true), 7)
        assertTrue(isCurrentPlaybackConfirmation(event, "scope", true, 7, true, 2_000))
        assertFalse(isCurrentPlaybackConfirmation(event, "scope", true, 8, true, 2_000))
        assertFalse(isCurrentPlaybackConfirmation(event, "scope", true, 7, false, 2_000))
    }

    @Test fun alternateEpisodeIdRequiresExactShowSeasonEpisodeAndView() {
        val played = episode.copy(showId = 9, seasonNumber = 1, episodeNumber = 2)
        val sameLogicalEpisode = played.copy(id = 99)
        val other = listOf(sameLogicalEpisode.copy(viewItemId = 502), sameLogicalEpisode.copy(showId = 10),
            sameLogicalEpisode.copy(seasonNumber = 2), sameLogicalEpisode.copy(episodeNumber = 3),
            sameLogicalEpisode.copy(showId = null))
        val after = applyConfirmedPlaybackWatch(AppState(items = listOf(sameLogicalEpisode) + other), played)
        assertTrue(after.items.first().watched)
        assertEquals(other, after.items.drop(1))
    }

    @Test fun twoConfirmationsSurviveAStaleRefreshAndAnInvalidatedOneIsIgnored() {
        val first = episode
        val second = episode.copy(id = 24)
        val events = listOf(event(first), event(second).copy(sequence = 2))
        val stale = AppState(items = listOf(first, second))
        assertTrue(applyConfirmedPlaybackWatches(stale, events) { true }.items.all { it.watched })
        val after = applyConfirmedPlaybackWatches(stale, events) { it.item.id == 24 }
        assertFalse(after.items[0].watched)
        assertTrue(after.items[1].watched)
    }
}
