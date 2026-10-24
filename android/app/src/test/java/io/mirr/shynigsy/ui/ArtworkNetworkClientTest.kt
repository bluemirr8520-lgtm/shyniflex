package io.mirr.shynigsy.ui

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ArtworkNetworkClientTest {
    private val delegate = object : NetworkClient {
        override suspend fun <T> executeRequest(
            request: NetworkRequest,
            block: suspend (response: NetworkResponse) -> T,
        ): T = block(NetworkResponse())
    }

    @Test fun aPermitCoversTheResponseBodyUntilItIsConsumed() = runArtworkTest {
        val client = ArtworkNetworkClient(delegate)
        val release = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("one")) { release.await(); "one" }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("two")) { release.await(); "two" }
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("three")) { thirdEntered.complete(Unit); "three" }
        }
        assertFalse("Headers alone must not release a body download slot", thirdEntered.isCompleted)

        release.complete(Unit)
        assertEquals(listOf("one", "two", "three"), listOf(first.await(), second.await(), third.await()))
        assertTrue(thirdEntered.isCompleted)
    }

    @Test fun aCanceledQueuedImageNeverStartsAndDoesNotConsumeTheNextSlot() = runArtworkTest {
        val client = ArtworkNetworkClient(delegate, maxConcurrentRequests = 1)
        val release = CompletableDeferred<Unit>()
        val canceledEntered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("one")) { release.await() }
        }
        val canceled = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("obsolete")) { canceledEntered.complete(Unit) }
        }
        canceled.cancelAndJoin()
        release.complete(Unit)
        first.await()

        assertEquals("current", client.executeRequest(request("current")) { "current" })
        assertFalse(canceledEntered.isCompleted)
    }

    @Test fun aFailedBodyReleasesItsPermitAndPreservesTheFailure() = runArtworkTest {
        val client = ArtworkNetworkClient(delegate, maxConcurrentRequests = 1)
        val failure = IOException("Synthetic image body failure")
        val observed = runCatching {
            client.executeRequest(request("broken")) { throw failure }
        }.exceptionOrNull()

        assertTrue(observed === failure)
        assertEquals("next", client.executeRequest(request("next")) { "next" })
    }

    @Test fun browsingAllowsFourBodiesWhileTheFifthWaits() = runArtworkTest {
        val client = ArtworkNetworkClient(delegate, maxConcurrentRequests = 4, isBrowsing = { true })
        val release = CompletableDeferred<Unit>()
        val entered = List(5) { CompletableDeferred<Unit>() }
        var active = 0
        var peak = 0
        val jobs = (0..4).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request(index.toString())) {
                active++
                peak = maxOf(peak, active)
                entered[index].complete(Unit)
                try { release.await() } finally { active-- }
            }
        } }
        assertTrue(entered.take(4).all { it.isCompleted })
        assertFalse(entered[4].isCompleted)
        assertEquals(4, active)
        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(4, peak)
        assertEquals(0, active)
    }

    @Test fun aHiddenLibraryStartsOnlyTwoBodiesEvenWithFourOuterSlots() = runArtworkTest {
        val client = ArtworkNetworkClient(delegate, maxConcurrentRequests = 4, isBrowsing = { false })
        val release = CompletableDeferred<Unit>()
        val entered = List(4) { CompletableDeferred<Unit>() }
        var active = 0
        var peak = 0
        val jobs = (0..3).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request(index.toString())) {
                active++
                peak = maxOf(peak, active)
                entered[index].complete(Unit)
                try { release.await() } finally { active-- }
            }
        } }
        assertTrue(entered.take(2).all { it.isCompleted })
        assertFalse(entered[2].isCompleted)
        assertFalse(entered[3].isCompleted)
        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(2, peak)
        assertEquals(0, active)
    }

    @Test fun queuedBrowsingRequestsRecheckVisibilityBeforeStartingTheirBodies() = runArtworkTest {
        var browsing = true
        val client = ArtworkNetworkClient(delegate, maxConcurrentRequests = 4, isBrowsing = { browsing })
        val entered = List(7) { CompletableDeferred<Unit>() }
        val release = List(7) { CompletableDeferred<Unit>() }
        val jobs = (0..6).map { index -> async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request(index.toString())) {
                entered[index].complete(Unit)
                release[index].await()
            }
        } }
        assertTrue(entered.take(4).all { it.isCompleted })
        browsing = false
        // Existing bodies drain normally. Three freed outer slots must admit
        // only two new bodies once the player has covered the library.
        (0..2).forEach { release[it].complete(Unit) }
        withTimeout(1_000) { entered[4].await(); entered[5].await() }
        assertFalse(entered[6].isCompleted)
        release[4].complete(Unit)
        withTimeout(1_000) { entered[6].await() }
        release.forEach { it.complete(Unit) }
        jobs.forEach { it.await() }
    }

    @Test fun cancelingAnInnerBackgroundWaitReturnsItsOuterSlot() = runArtworkTest {
        val client = ArtworkNetworkClient(delegate, maxConcurrentRequests = 4, isBrowsing = { false })
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { client.executeRequest(request("one")) { release.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { client.executeRequest(request("two")) { release.await() } }
        val obsoleteEntered = CompletableDeferred<Unit>()
        val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("obsolete")) { obsoleteEntered.complete(Unit) }
        }
        obsolete.cancelAndJoin()
        val currentEntered = CompletableDeferred<Unit>()
        val current = async(start = CoroutineStart.UNDISPATCHED) {
            client.executeRequest(request("current")) { currentEntered.complete(Unit) }
        }
        assertFalse(obsoleteEntered.isCompleted)
        assertFalse(currentEntered.isCompleted)
        release.complete(Unit)
        first.await(); second.await(); current.await()
        assertTrue(currentEntered.isCompleted)
        assertFalse(obsoleteEntered.isCompleted)
    }

    private fun request(name: String) = NetworkRequest("https://artwork.invalid/$name")

    private fun runArtworkTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }
}
