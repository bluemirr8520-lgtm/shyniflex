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

    private fun request(name: String) = NetworkRequest("https://artwork.invalid/$name")

    private fun runArtworkTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }
}
