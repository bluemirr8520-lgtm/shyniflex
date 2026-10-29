package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.ApiException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataLoadingTest {
    @Test fun aPreparedSourceLoadsOnlyOnceAcrossRepeatedReadyNotifications() {
        val gate = PlaybackMetadataGate()
        assertNull(gate.takePending())
        gate.reset()
        val first = gate.takePending()!!
        assertTrue(gate.isCurrent(first))
        // A seek/cache pause may deliver BUFFERING -> READY again for the same player.
        repeat(3) { assertNull(gate.takePending()) }
        gate.reset()
        assertFalse("A replacement source must invalidate the previous response", gate.isCurrent(first))
        assertNotNull("The replacement must get its own first-ready load", gate.takePending())
    }

    @Test fun releaseCancelsBothPendingAndAlreadyStartedSourcePublications() {
        val gate = PlaybackMetadataGate()
        gate.reset()
        gate.cancel()
        assertNull("Leaving before READY must not start optional requests", gate.takePending())
        gate.reset()
        val running = gate.takePending()!!
        gate.cancel()
        assertFalse("An already returning response must not repopulate a released player", gate.isCurrent(running))
        assertNull(gate.takePending())
    }

    @Test fun neighborsStartOnlyAfterMarkersHavePublishedTheirSharedSnapshot() = runBlocking {
        withTimeout(10_000) {
            val markersStarted = CompletableDeferred<Unit>()
            val markerResponse = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val work = async {
                loadPlaybackMetadataInOrder(
                    loadMarkers = { markersStarted.complete(Unit); markerResponse.await(); events += "cache+markers" },
                    loadNeighbors = { events += "neighbors-from-cache" },
                )
            }
            markersStarted.await()
            assertTrue("No neighbor request may race the current file's metadata response", events.isEmpty())
            markerResponse.complete(Unit)
            work.await()
            assertEquals(listOf("cache+markers", "neighbors-from-cache"), events)
        }
    }

    @Test fun eitherOptionalFailureLeavesTheOtherSuccessfulSectionAvailable() = runBlocking {
        val results = mutableListOf<String>()
        loadPlaybackMetadataInOrder(
            loadMarkers = { throw IOException("fixture unavailable markers") },
            loadNeighbors = { results += "neighbors" },
        )
        loadPlaybackMetadataInOrder(
            loadMarkers = { results += "markers" },
            loadNeighbors = { throw IOException("fixture unavailable catalog") },
        )
        assertEquals(listOf("neighbors", "markers"), results)
    }

    @Test fun sourceCancellationStopsThePendingMarkerAndNeverStartsNeighbors() = runBlocking {
        withTimeout(10_000) {
            val markersStarted = CompletableDeferred<Unit>()
            val lateResponse = CompletableDeferred<Unit>()
            var publications = 0
            var neighborRequests = 0
            val work = launch {
                loadPlaybackMetadataInOrder(
                    loadMarkers = { markersStarted.complete(Unit); lateResponse.await(); publications++ },
                    loadNeighbors = { neighborRequests++ },
                )
            }
            markersStarted.await()
            work.cancel()
            lateResponse.complete(Unit)
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(0, publications)
            assertEquals(0, neighborRequests)
        }
    }

    @Test fun transientMarkerFailureRetriesWithoutBlockingNeighbors() = runBlocking {
        withTimeout(10_000) {
            val retryWaiting = CompletableDeferred<Unit>()
            val allowRetry = CompletableDeferred<Unit>()
            val neighborsDone = CompletableDeferred<Unit>()
            var attempts = 0
            val work = launch {
                loadPlaybackMetadataInOrder(
                    loadMarkers = { if (++attempts == 1) throw SocketTimeoutException() },
                    loadNeighbors = { neighborsDone.complete(Unit) },
                    markerRetryDelaysMs = listOf(2_000, 10_000),
                    pause = { retryWaiting.complete(Unit); allowRetry.await() },
                )
            }
            retryWaiting.await()
            neighborsDone.await()
            assertEquals(1, attempts)
            allowRetry.complete(Unit)
            work.join()
            assertEquals(2, attempts)
        }
    }

    @Test fun authorizationAndUnsupportedResponsesNeverRetry() = runBlocking {
        for (status in listOf(401, 403, 404)) {
            var attempts = 0
            var neighbors = 0
            loadPlaybackMetadataInOrder(
                loadMarkers = { attempts++; throw ApiException("fixture", status) },
                loadNeighbors = { neighbors++ },
                markerRetryDelaysMs = listOf(2_000),
                pause = { fail("HTTP $status must not be retried") },
            )
            assertEquals(1, attempts)
            assertEquals(1, neighbors)
        }
    }

    @Test fun sourceCancellationStopsDelayedMarkerRetries() = runBlocking {
        withTimeout(10_000) {
            val retryWaiting = CompletableDeferred<Unit>()
            val allowRetry = CompletableDeferred<Unit>()
            var attempts = 0
            val work = launch {
                loadPlaybackMetadataInOrder(
                    loadMarkers = { attempts++; throw IOException("fixture") },
                    loadNeighbors = {}, markerRetryDelaysMs = listOf(2_000),
                    pause = { retryWaiting.complete(Unit); allowRetry.await() },
                )
            }
            retryWaiting.await()
            work.cancel()
            allowRetry.complete(Unit)
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(1, attempts)
        }
    }

    @Test fun transientMarkerRetryIsBounded() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        loadPlaybackMetadataInOrder(
            loadMarkers = { attempts++; throw ApiException("fixture", 503) },
            loadNeighbors = {}, markerRetryDelaysMs = listOf(2_000, 10_000, 30_000, 60_000),
            pause = { waits += it },
        )
        assertEquals(5, attempts)
        assertEquals(listOf(2_000L, 10_000L, 30_000L, 60_000L), waits)
    }

    @Test fun stalledFirstMarkerDoesNotBlockNeighborAndCanRetry() = runBlocking {
        withTimeout(5_000) {
            val neighborFinished = CompletableDeferred<Unit>()
            val neverFinishes = CompletableDeferred<Unit>()
            var attempts = 0
            var recovered = false
            val work = launch {
                loadPlaybackMetadataInOrder(
                    loadMarkers = {
                        if (++attempts == 1) neverFinishes.await()
                        else { delay(60); recovered = true }
                    },
                    loadNeighbors = { neighborFinished.complete(Unit) },
                    markerRetryDelaysMs = listOf(1), pause = {}, markerAttemptTimeoutMs = 30,
                    markerRetryTimeoutMs = 500,
                )
            }
            neighborFinished.await()
            work.join()
            assertEquals(2, attempts)
            assertTrue(recovered)
            assertFalse(neverFinishes.isCompleted)
        }
    }
}
