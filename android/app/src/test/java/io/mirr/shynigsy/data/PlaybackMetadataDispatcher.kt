package io.mirr.shynigsy.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest

/** Route the independent preparation reads without relying on TCP arrival order. */
internal class PlaybackMetadataDispatcher : QueueDispatcher() {
    private data class Pending(val response: MockResponse, val barrier: java.util.concurrent.CountDownLatch? = null)
    private val metadata = mutableMapOf<String, java.util.ArrayDeque<Pending>>()

    @Synchronized fun enqueueMetadata(path: String, response: MockResponse) {
        metadata.getOrPut(path) { java.util.ArrayDeque() }.addLast(Pending(response))
    }

    @Synchronized fun enqueueTogether(firstPath: String, first: MockResponse, secondPath: String, second: MockResponse) {
        val barrier = java.util.concurrent.CountDownLatch(2)
        metadata.getOrPut(firstPath) { java.util.ArrayDeque() }.addLast(Pending(first, barrier))
        metadata.getOrPut(secondPath) { java.util.ArrayDeque() }.addLast(Pending(second, barrier))
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath
        val pending = synchronized(this) {
            if (request.method == "GET" && path != null) metadata[path]?.let { queue ->
                queue.pollFirst() ?: Pending(MockResponse().setResponseCode(500)
                    .setBody("""{"detail":"Unexpected fixture metadata request"}"""))
            } else null
        }
        pending?.barrier?.let {
            it.countDown()
            if (!it.await(10, java.util.concurrent.TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                .setBody("""{"detail":"Fixture metadata requests were serialized"}""")
        }
        return pending?.response ?: super.dispatch(request)
    }
}
