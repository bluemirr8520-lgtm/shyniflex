package io.mirr.shynigsy.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryArtworkPriorityTest {
    @Test fun supplementalCountsWaitUntilThePosterSettles() = runBlocking {
        val artwork = CompletableDeferred<Unit>()
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            awaitLibraryArtwork(artwork, minimumDelayMs = 0, maxWaitMs = 1_000)
        }
        assertFalse(ready.isCompleted)
        artwork.complete(Unit)
        withTimeout(1_000) { ready.await() }
    }

    @Test fun aHangingPosterDoesNotStarveCountsOrCancelTheImageSignal() = runBlocking {
        val artwork = CompletableDeferred<Unit>()
        withTimeout(1_000) { awaitLibraryArtwork(artwork, minimumDelayMs = 0, maxWaitMs = 20) }
        assertFalse(artwork.isCompleted)
        assertFalse(artwork.isCancelled)
        artwork.complete(Unit)
    }

    @Test fun aCachedOrMissingImageStillLeavesTheInitialQuietPeriod() = runBlocking {
        val artwork = CompletableDeferred(Unit)
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            awaitLibraryArtwork(artwork, minimumDelayMs = 20, maxWaitMs = 1_000)
        }
        assertFalse(ready.isCompleted)
        withTimeout(1_000) { ready.await() }
    }

    @Test fun leavingTheCardCancelsItsWaitWithoutCancelingSharedArtwork() = runBlocking {
        val artwork = CompletableDeferred<Unit>()
        val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
            awaitLibraryArtwork(artwork, minimumDelayMs = 0, maxWaitMs = 1_000)
        }
        obsolete.cancelAndJoin()
        assertFalse(artwork.isCancelled)
        artwork.complete(Unit)
        withTimeout(1_000) { awaitLibraryArtwork(artwork, minimumDelayMs = 0, maxWaitMs = 1) }
    }
}
