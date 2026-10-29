package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlaybackCompletionSignalsTest {
    @Test fun manualChoiceOnlyInvalidatesTheAffectedConfirmation() {
        val item = MediaItem(1, 8, "episode", "episode", viewItemId = 501, showId = 3, seasonNumber = 1, episodeNumber = 1)
        val previous = PlaybackCompletionSignals.choiceRevision()
        PlaybackCompletionSignals.invalidateForManualChoice("scope", item)
        assertNotEquals(previous, PlaybackCompletionSignals.choiceRevision())
        assertFalse(PlaybackCompletionSignals.isChoiceCurrent("scope", item, previous))
        assertTrue(PlaybackCompletionSignals.isChoiceCurrent("scope", item.copy(id = 2, episodeNumber = 2), previous))
    }

    @Test fun accountScopeDoesNotExposeCredentialsAndSeparatesAccounts() {
        val scope = PlaybackCompletionSignals.scope("https://example.invalid", "secret")!!
        assertFalse(scope.contains("secret"))
        assertNotEquals(scope, PlaybackCompletionSignals.scope("https://example.invalid", "different"))
        assertNotEquals(scope, PlaybackCompletionSignals.scope("https://elsewhere.invalid", "secret"))
        assertNull(PlaybackCompletionSignals.scope("https://example.invalid", null))
    }

    @Test fun concurrentConfirmationsPublishInSequenceOrder() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val revision = PlaybackCompletionSignals.choiceRevision()
        try {
            repeat(16) { index -> executor.execute {
                try {
                    start.await()
                    val item = MediaItem(1000 + index, 88, "episode", "episode", viewItemId = 500 + index)
                    PlaybackCompletionSignals.confirm("parallel-test-scope", item, null, revision)
                } finally { done.countDown() }
            } }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            val sequences = PlaybackCompletionSignals.confirmations.value
                .filter { it.scope == "parallel-test-scope" }.map { it.sequence }
            assertEquals(16, sequences.size)
            assertEquals(sequences.sorted(), sequences)
            assertEquals(16, sequences.distinct().size)
        } finally { executor.shutdownNow() }
    }
}
