package io.mirr.shynigsy.player

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
}
