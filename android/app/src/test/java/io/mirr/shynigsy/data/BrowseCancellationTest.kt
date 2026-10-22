package io.mirr.shynigsy.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowseCancellationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val firstCatalogStarted = CountDownLatch(1)
    private val releaseFirstCatalog = CountDownLatch(1)
    private val catalogCalls = AtomicInteger()
    private val library = Library(8, "합성 영화", "movie")

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/libraries/8/movies" -> {
                        if (catalogCalls.incrementAndGet() == 1) {
                            firstCatalogStarted.countDown()
                            if (releaseFirstCatalog.await(10, TimeUnit.SECONDS)) MockResponse().setBody(rows(1..61))
                            else MockResponse().setResponseCode(504).setBody("{}")
                        } else MockResponse().setBody(rows(1001..1062))
                    }
                    "/playback/progress" -> MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "first-test-account", deviceId = "test-device")
    }

    @After fun close() {
        releaseFirstCatalog.countDown()
        SpoilerAccessRegistry.clearAll()
        server.shutdown()
    }

    @Test fun clearingPrivateAccessAlsoRejectsAnOlderPendingPublicCatalog() = runBlocking {
        assertInvalidatedCatalogCannotPublish("first-test-account") { client.clearSpoilerGrant() }
    }

    @Test fun changingAccountRejectsOldCatalogAndTheNextFetchUsesNewAccount() = runBlocking {
        assertInvalidatedCatalogCannotPublish("second-test-account") { client.token = "second-test-account" }
    }

    private suspend fun assertInvalidatedCatalogCannotPublish(expectedAccount: String, invalidate: () -> Unit) = coroutineScope {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.items(library) } }
        try {
            withContext(Dispatchers.IO) {
                assertTrue("The old HTTP request must be pending before invalidation", firstCatalogStarted.await(5, TimeUnit.SECONDS))
            }
            assertEquals(1, catalogCalls.get())
            invalidate()
            releaseFirstCatalog.countDown()

            val outcome = withTimeout(5_000) { pending.await() }
            assertTrue("An invalidated catalog must be cancelled, never returned", outcome.exceptionOrNull() is CancellationException)
            assertTrue("The late HTTP response must not restore the cleared cache", browseCache().isEmpty())
            assertEquals(1, server.requestCount)
            val oldRequest = takeRequest()
            assertEquals("Bearer first-test-account", oldRequest.getHeader("Authorization"))

            val fresh = client.items(library, offset = 0)
            assertEquals((1001..1060).toList(), fresh.items.map { it.id })
            assertEquals(62, fresh.total)
            assertFalse(browseCache().isEmpty())
            assertEquals(2, catalogCalls.get())
            val newCatalog = takeRequest()
            val newProgress = takeRequest()
            assertEquals("/libraries/8/movies", newCatalog.requestUrl!!.encodedPath)
            assertEquals("/playback/progress", newProgress.requestUrl!!.encodedPath)
            assertEquals("Bearer $expectedAccount", newCatalog.getHeader("Authorization"))
            assertEquals("Bearer $expectedAccount", newProgress.getHeader("Authorization"))
        } finally {
            releaseFirstCatalog.countDown()
            pending.cancelAndJoin()
        }
    }

    private fun browseCache(): Map<*, *> = ShyniClient::class.java.getDeclaredField("browseCache")
        .apply { isAccessible = true }.get(client) as Map<*, *>

    private fun rows(ids: IntRange): String = JSONArray(ids.map {
        JSONObject().put("id", it).put("title", "합성 영화 $it").put("view_item_id", it + 10_000)
    }).toString()

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
}
