package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.player.PlaybackCompletionSignals.LocalCompletionStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlaybackCompletionDisplayRegistryTest {
    private val item = MediaItem(41, 8, "fixture", "episode", viewItemId = 51,
        showId = 61, seasonNumber = 1, episodeNumber = 2)
    private fun registry(now: () -> Long = { 1_000L }) = PlaybackCompletionDisplayRegistry(
        choiceCurrent = { _, _, _ -> true }, now = now)
    private fun accept(registry: PlaybackCompletionDisplayRegistry, item: MediaItem = this.item): Long {
        var token: Long? = null
        assertTrue(registry.accept("scope", item, null, 0) { token = it; true })
        return requireNotNull(token)
    }

    @Test fun acceptedWriteShowsPendingWithoutConfirmingOrChangingTheSourceItem() {
        val registry = registry()
        val token = accept(registry)
        val receipt = registry.events.value.single()
        assertEquals(token, receipt.sequence)
        assertEquals(LocalCompletionStatus.PENDING, receipt.status)
        assertFalse(receipt.item.watched)
        assertFalse(item.watched)
        assertEquals(1_000L + PlaybackCompletionDisplayRegistry.PENDING_LIFETIME_NANOS, receipt.expiresNanos)
    }

    @Test fun closedQueueCannotPublishACompletionHint() {
        val registry = registry()
        assertFalse(registry.accept("scope", item, null, 0) { false })
        assertTrue(registry.events.value.isEmpty())
    }

    @Test fun invalidIdentityStillEnqueuesWithoutAnOptimisticReceipt() {
        val registry = registry()
        for (invalid in listOf(item.copy(id = 0), item.copy(libraryId = 0),
            item.copy(viewItemId = null), item.copy(type = "show"))) {
            assertTrue(registry.accept("scope", invalid, null, 0) { token -> assertNull(token); true })
        }
        assertTrue(registry.accept(null, item, null, 0) { token -> assertNull(token); true })
        assertTrue(registry.events.value.isEmpty())
    }

    @Test fun anImmediatelyAwakenedWriterCannotAcknowledgeBeforeRegistration() {
        val registry = registry()
        val writer = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var acknowledged = false
        try {
            assertTrue(registry.accept("scope", item, null, 0) { token ->
                writer.execute {
                    started.countDown()
                    acknowledged = registry.acknowledge(token)
                    completed.countDown()
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                // Writer runs concurrently while enqueue still owns the publication lock.
                true
            })
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(acknowledged)
            assertEquals(LocalCompletionStatus.CONFIRMED, registry.events.value.single().status)
        } finally { writer.shutdownNow() }
    }

    @Test fun acknowledgementPromotesTheSameReceiptWithoutAWithdrawalGap() {
        var time = 10L
        val registry = registry { time }
        val token = accept(registry)
        val pending = registry.events.value.single()
        time += 500L
        assertTrue(registry.acknowledge(token))
        val acknowledged = registry.events.value.single()
        assertEquals(pending.sequence, acknowledged.sequence)
        assertEquals(pending.createdNanos, acknowledged.createdNanos)
        assertEquals(LocalCompletionStatus.CONFIRMED, acknowledged.status)
        assertEquals(time + PlaybackCompletionDisplayRegistry.RESOLVED_LIFETIME_NANOS, acknowledged.expiresNanos)
        assertFalse(registry.fail(token, showFailure = true))
        registry.discard(listOf(token))
        assertEquals(LocalCompletionStatus.CONFIRMED, registry.events.value.single().status)
    }

    @Test fun failedWatchWriteLeavesOnlyAFailureReceiptAndCannotBecomeALateSuccess() {
        val registry = registry()
        val token = accept(registry)
        assertTrue(registry.fail(token, showFailure = true))
        assertEquals(LocalCompletionStatus.FAILED, registry.events.value.single().status)
        assertFalse(registry.acknowledge(token))
        assertEquals(LocalCompletionStatus.FAILED, registry.events.value.single().status)
    }

    @Test fun cancellingOneWriteDoesNotWithdrawNewerOrConfirmedReceipts() {
        val registry = registry()
        val older = accept(registry)
        val current = accept(registry)
        val confirmed = accept(registry, item.copy(id = 42, episodeNumber = 3))
        assertTrue(registry.acknowledge(confirmed))
        assertTrue(registry.fail(older, showFailure = false))
        assertEquals(listOf(current, confirmed), registry.events.value.map { it.sequence })
        registry.discard(listOf(current, confirmed))
        assertEquals(confirmed, registry.events.value.single().sequence)
    }

    @Test fun manualUnwatchInvalidatesPendingAndLateAckAcrossViews() {
        val choices = PlaybackWatchChoiceEpoch()
        val registry = PlaybackCompletionDisplayRegistry(choices::isCurrent)
        val token = accept(registry)
        choices.record("scope", item.copy(viewItemId = 99))
        registry.removeStale()
        assertTrue(registry.events.value.isEmpty())
        assertFalse(registry.acknowledge(token))
        assertTrue(registry.accept("scope", item, null, 0) { token -> assertNull(token); true })
    }

    @Test fun accountAndEpisodeChoicesDoNotDiscardUnrelatedReceipts() {
        val choices = PlaybackWatchChoiceEpoch()
        val registry = PlaybackCompletionDisplayRegistry(choices::isCurrent)
        val token = accept(registry)
        choices.record("other-account", item)
        choices.record("scope", item.copy(id = 42, episodeNumber = 3, viewItemId = 52))
        registry.removeStale()
        assertEquals(token, registry.events.value.single().sequence)
    }

    @Test fun expiredHintsAreRemovedAndCannotBeResurrectedByAnOldWriter() {
        var time = 100L
        val registry = registry { time }
        val token = accept(registry)
        time += PlaybackCompletionDisplayRegistry.PENDING_LIFETIME_NANOS
        registry.removeStale()
        assertTrue(registry.events.value.isEmpty())
        assertFalse(registry.acknowledge(token))
        assertFalse(registry.fail(token, showFailure = true))
    }

    @Test fun resolvedReceiptsExpireFromTheAcknowledgementTime() {
        var time = 100L
        val registry = registry { time }
        val token = accept(registry)
        time += 30_000_000_000L
        assertTrue(registry.acknowledge(token))
        time += PlaybackCompletionDisplayRegistry.RESOLVED_LIFETIME_NANOS - 1
        registry.removeStale()
        assertEquals(token, registry.events.value.single().sequence)
        time++
        registry.removeStale()
        assertTrue(registry.events.value.isEmpty())
    }

    @Test fun hintStorageIsBoundedEvenIfWritersNeverRespond() {
        val registry = registry()
        repeat(100) { accept(registry) }
        assertEquals(PlaybackCompletionDisplayRegistry.MAX_RECEIPTS, registry.events.value.size)
        assertEquals(100L, registry.events.value.last().sequence)
    }

    @Test fun manualChoiceImmediatelyPublishesRemovalThroughTheAppSignal() {
        val scope = "manual-removal-fixture"
        var token: Long? = null
        assertTrue(PlaybackCompletionSignals.acceptPending(scope, item, null,
            PlaybackCompletionSignals.choiceRevision()) { token = it; true })
        assertTrue(PlaybackCompletionSignals.presentation.value.any { it.sequence == token })
        PlaybackCompletionSignals.invalidateForManualChoice(scope, item)
        assertFalse(PlaybackCompletionSignals.presentation.value.any { it.sequence == token })
        assertFalse(PlaybackCompletionSignals.acknowledgeLocal(token))
    }
}
