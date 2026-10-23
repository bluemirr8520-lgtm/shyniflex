package io.mirr.shynigsy.data

import okhttp3.Dispatcher
import okhttp3.OkHttpClient

internal enum class ShyniRequestLane { CATALOG, PLAYBACK, CONTROL }

/** Reserve capacity for playback, authentication and writes when slow catalog reads are still pending. */
internal class ShyniRequestLanes(private val base: OkHttpClient) {
    // Five requests per origin in total, matching OkHttp's default host limit.
    // Keep the injected transport's interceptors, pool, executor and stricter limits.
    private fun client(slots: Int): OkHttpClient = base.newBuilder().dispatcher(
        Dispatcher(base.dispatcher.executorService).apply {
            maxRequests = minOf(slots, base.dispatcher.maxRequests)
            maxRequestsPerHost = minOf(slots, base.dispatcher.maxRequestsPerHost)
        },
    ).build()

    private val catalog = client(2)
    private val playback = client(2)
    private val control = client(1)
    // A caller can deliberately supply a smaller shared budget (including test transports).
    private val preserveSharedLimit = base.dispatcher.maxRequests < 5 || base.dispatcher.maxRequestsPerHost < 5
    private val singleShared by lazy { base.newBuilder().retryOnConnectionFailure(false).build() }
    private val singleCatalog by lazy { catalog.newBuilder().retryOnConnectionFailure(false).build() }
    private val singlePlayback by lazy { playback.newBuilder().retryOnConnectionFailure(false).build() }
    private val singleControl by lazy { control.newBuilder().retryOnConnectionFailure(false).build() }

    fun client(path: String, hasBody: Boolean, method: String, overrideLane: ShyniRequestLane?, singleAttempt: Boolean): OkHttpClient {
        if (preserveSharedLimit) return if (singleAttempt) singleShared else base
        val lane = overrideLane ?: when {
            // A new PIN grant must not wait behind the old grant's two pending metadata reads.
            path.startsWith("/auth/") || path == "/spoiler/client/unlock" || path == "/spoiler/client/lock" ||
                (hasBody && (path == "/playback/progress" ||
                    (method == "PUT" && path.startsWith("/continue-watching/") && path.endsWith("/watch-state")))) -> ShyniRequestLane.CONTROL
            path == "/playback/versions" || path == "/playback/decision" ||
                path.startsWith("/stream-ticket/") || path.startsWith("/playback/transcode-ticket/") ||
                path.startsWith("/subtitles/") -> ShyniRequestLane.PLAYBACK
            else -> ShyniRequestLane.CATALOG
        }
        return when (lane) {
            ShyniRequestLane.CATALOG -> if (singleAttempt) singleCatalog else catalog
            ShyniRequestLane.PLAYBACK -> if (singleAttempt) singlePlayback else playback
            ShyniRequestLane.CONTROL -> if (singleAttempt) singleControl else control
        }
    }
}
