package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.player.PlaybackCompletionSignals.LocalCompletion
import io.mirr.shynigsy.player.PlaybackCompletionSignals.LocalCompletionStatus
import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackCompletionTest {
    private val movie = MediaItem(10, 2, "fixture", "movie", viewItemId = 3, resumeMs = 4_000)
    private fun receipt(status: LocalCompletionStatus = LocalCompletionStatus.PENDING) =
        LocalCompletion(1, "account", movie, 7, 0, 1_000, 5_000, status)

    @Test fun pendingDisplaysCompletionWithoutChangingTheStoredSnapshot() {
        val raw = AppState(items = listOf(movie), recentItems = listOf(movie),
            continueItems = listOf(movie), continueDetails = movie)
        val displayed = applyLocalPlaybackCompletions(raw, listOf(receipt())) { true }
        assertTrue(displayed.items.single().watched)
        assertTrue(displayed.recentItems.single().watched)
        assertTrue(displayed.continueItems.isEmpty())
        assertNull(displayed.continueDetails)
        assertFalse(raw.items.single().watched)
        assertEquals(listOf(movie), raw.continueItems)
        assertEquals(movie, raw.continueDetails)
    }

    @Test fun failureOrRemovalRollsBackToTheServerSnapshot() {
        val raw = AppState(items = listOf(movie), continueItems = listOf(movie))
        assertEquals(raw, applyLocalPlaybackCompletions(raw, listOf(receipt(LocalCompletionStatus.FAILED))) { true })
        assertEquals(raw, applyLocalPlaybackCompletions(raw, emptyList()) { true })
        assertTrue(applyLocalPlaybackCompletions(raw, listOf(receipt(LocalCompletionStatus.CONFIRMED))) { true }.items.single().watched)
    }

    @Test fun accountChoiceGrantAndExpiryFenceTheDisplayReceipt() {
        val public = receipt()
        assertTrue(isCurrentLocalPlaybackCompletion(public, "account", true, 8, false, 2_000))
        assertFalse(isCurrentLocalPlaybackCompletion(public, "another", true, 7, true, 2_000))
        assertFalse(isCurrentLocalPlaybackCompletion(public, "account", false, 7, true, 2_000))
        assertFalse(isCurrentLocalPlaybackCompletion(public, "account", true, 7, true, 5_000))
        assertFalse(isCurrentLocalPlaybackCompletion(public, "account", true, 7, true, 999))
        val private = public.copy(item = movie.copy(isSpoiler = true))
        assertTrue(isCurrentLocalPlaybackCompletion(private, "account", true, 7, true, 2_000))
        assertFalse(isCurrentLocalPlaybackCompletion(private, "account", true, 8, true, 2_000))
        assertFalse(isCurrentLocalPlaybackCompletion(private, "account", true, 7, false, 2_000))
    }
}
