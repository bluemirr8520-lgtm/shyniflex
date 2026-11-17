package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun manualChoiceCommitsAfterAnInflightWriteAndSupersedesOlderQueuedWrites() = runBlocking {
        val item = MediaItem(2141, 28, "fixture", "episode", viewItemId = 2051)
        val scope = "ordered-manual-choice-fixture"
        val oldRevision = PlaybackCompletionSignals.choiceRevision()
        val releaseActive = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            PlaybackCompletionSignals.withWatchMutation {
                assertTrue(PlaybackCompletionSignals.isChoiceCurrent(scope, item, oldRevision))
                order += "active-start"
                releaseActive.await()
                order += "active-committed"
            }
        }
        // The manual fence is recorded before waiting for the active HTTP write.
        PlaybackCompletionSignals.invalidateForManualChoice(scope, item)
        val manual = async(start = CoroutineStart.UNDISPATCHED) {
            PlaybackCompletionSignals.withWatchMutation { order += "manual-unwatched" }
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            PlaybackCompletionSignals.withWatchMutation {
                if (PlaybackCompletionSignals.isChoiceCurrent(scope, item, oldRevision))
                    order += "stale-watch-write"
                else order += "stale-skipped"
            }
        }
        assertEquals(listOf("active-start"), order)
        releaseActive.complete(Unit)
        withTimeout(2_000) { active.await(); manual.await(); queued.await() }
        assertEquals(listOf("active-start", "active-committed", "manual-unwatched", "stale-skipped"), order)
        assertTrue(PlaybackCompletionSignals.isChoiceCurrent(scope,
            item.copy(id = 2142, viewItemId = 2052), oldRevision))
    }
}
