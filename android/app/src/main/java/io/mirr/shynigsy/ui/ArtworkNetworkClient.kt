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
) : NetworkClient {
    private val requests = Semaphore(maxConcurrentRequests)

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T = requests.withPermit {
        // Coil reads/caches the response body inside this block. Limiting only
        // OkHttp's header callback would leave body downloads unconstrained.
        delegate.executeRequest(request, block)
    }
}
