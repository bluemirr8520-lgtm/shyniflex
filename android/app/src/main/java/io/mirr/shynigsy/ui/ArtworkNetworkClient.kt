package io.mirr.shynigsy.ui

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Keep optional artwork downloads from competing with playback and catalog requests. */
internal class ArtworkNetworkClient(
    private val delegate: NetworkClient,
    maxConcurrentRequests: Int = 2,
    private val isBrowsing: () -> Boolean = { false },
) : NetworkClient {
    private val requests = Semaphore(maxConcurrentRequests)
    private val backgroundRequests = Semaphore(minOf(2, maxConcurrentRequests))

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T = requests.withPermit {
        // Coil reads/caches the response body inside this block. Limiting only
        // OkHttp's header callback would leave body downloads unconstrained.
        // Evaluate after the outer queue: a request composed while browsing may
        // reach its body only after the player has covered the library activity.
        // Existing bodies finish normally; new background bodies share two slots.
        if (isBrowsing()) delegate.executeRequest(request, block)
        else backgroundRequests.withPermit { delegate.executeRequest(request, block) }
    }
}
