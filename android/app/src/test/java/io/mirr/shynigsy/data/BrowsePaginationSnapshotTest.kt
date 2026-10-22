package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowsePaginationSnapshotTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val catalogRequests = AtomicInteger()
    @Volatile private var catalog = "[]"
    private val library = Library(8, "합성 영화", "movie")

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/libraries/8/movies" -> {
                        catalogRequests.incrementAndGet()
                        MockResponse().setBody(catalog)
                    }
                    "/playback/progress" -> MockResponse().setBody("{}")
                    "/spoiler/client/unlock" -> MockResponse()
                        .setBody("""{"token":"spc_test-grant","expires_in":1800}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "test-account", deviceId = "test-device")
    }

    @After fun close() {
        SpoilerAccessRegistry.clearAll()
        server.shutdown()
    }

    @Test fun continuationKeepsExpiredCatalogSnapshotThroughLastAndEmptyPages() = runBlocking {
        catalog = rows(1..125)
        val first = client.items(library)
        expireSnapshot()
        // A catalog change must wait for a first-page refresh; offset 60 still means row 61.
        catalog = rows(1001..1180)
        val second = client.items(library, offset = 60)
        val last = client.items(library, offset = 120)
        val beyondLast = client.items(library, offset = 125)

        assertEquals((1..60).toList(), first.items.map { it.id })
        assertEquals((61..120).toList(), second.items.map { it.id })
        assertEquals((121..125).toList(), last.items.map { it.id })
        assertTrue(beyondLast.items.isEmpty())
        assertTrue(listOf(first, second, last, beyondLast).all { it.total == 125 })
        assertEquals(1, catalogRequests.get())
        assertEquals(4, server.requestCount) // One catalog and fresh progress for each nonempty page.
        assertEquals("/libraries/8/movies", takeRequest().requestUrl!!.encodedPath)
        assertEquals((1..60).joinToString(","), takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals((61..120).joinToString(","), takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals((121..125).joinToString(","), takeRequest().requestUrl!!.queryParameter("item_ids"))
    }

    @Test fun explicitFirstPageRefreshReplacesEvenUnexpiredSnapshotAndItsTotal() = runBlocking {
        catalog = rows(1..125)
        client.items(library)
        catalog = rows(1001..1062)

        val refreshed = client.items(library, offset = 0)
        val continuation = client.items(library, offset = 60)

        assertEquals((1001..1060).toList(), refreshed.items.map { it.id })
        assertEquals(listOf(1061, 1062), continuation.items.map { it.id })
        assertEquals(62, refreshed.total)
        assertEquals(62, continuation.total)
        assertEquals(2, catalogRequests.get())
        assertEquals(5, server.requestCount)
    }

    @Test fun accountChangeCannotReusePreviousAccountsContinuationSnapshot() = runBlocking {
        catalog = rows(1..125)
        client.items(library)
        expireSnapshot()
        catalog = rows(1001..1062)
        client.token = "other-test-account"

        val continuation = client.items(library, offset = 60)

        assertEquals(listOf(1061, 1062), continuation.items.map { it.id })
        assertEquals(62, continuation.total)
        assertEquals(2, catalogRequests.get())
        repeat(2) { takeRequest() }
        assertEquals("Bearer other-test-account", takeRequest().getHeader("Authorization"))
        assertEquals("Bearer other-test-account", takeRequest().getHeader("Authorization"))
    }

    @Test fun lockedPrivateSnapshotIsRejectedAndReunlockStartsFreshWithLiveGrant() = runBlocking {
        val privateLibrary = library.copy(isSpoiler = true)
        client.unlockSpoiler("1234")
        takeRequest()
        catalog = rows(1..125)
        client.items(privateLibrary)
        assertEquals("spc_test-grant", takeRequest().getHeader("X-Shyni-Spoiler"))
        assertEquals("spc_test-grant", takeRequest().getHeader("X-Shyni-Spoiler"))
        expireSnapshot()
        val continuation = client.items(privateLibrary, offset = 60)
        assertTrue(continuation.items.all { it.isSpoiler })
        assertEquals("spc_test-grant", takeRequest().getHeader("X-Shyni-Spoiler"))
        assertEquals(1, catalogRequests.get())

        client.clearSpoilerGrant()
        val requestsBeforeLockedRead = server.requestCount
        val failure = runCatching { client.items(privateLibrary, offset = 120) }.exceptionOrNull()
        assertEquals(403, (failure as ApiException).status)
        assertEquals(requestsBeforeLockedRead, server.requestCount)

        catalog = rows(1001..1062)
        client.unlockSpoiler("1234")
        takeRequest()
        val afterUnlock = client.items(privateLibrary, offset = 60)
        assertEquals(listOf(1061, 1062), afterUnlock.items.map { it.id })
        assertTrue(afterUnlock.items.all { it.isSpoiler })
        assertEquals(2, catalogRequests.get())
        repeat(2) {
            val request = takeRequest()
            assertEquals("spc_test-grant", request.getHeader("X-Shyni-Spoiler"))
            assertEquals("test-device", request.getHeader("X-Device-Id"))
        }
    }

    private fun rows(ids: IntRange): String = JSONArray(ids.map {
        JSONObject().put("id", it).put("title", "합성 영화 $it").put("view_item_id", it + 10_000)
    }).toString()

    /** Exercise the old 60-second boundary without sleeping or adding a production clock API. */
    private fun expireSnapshot() {
        val field = ShyniClient::class.java.getDeclaredField("browseCache").apply { isAccessible = true }
        val snapshots = field.get(client) as Map<*, *>
        assertTrue(snapshots.isNotEmpty())
        snapshots.values.forEach { snapshot ->
            val value = requireNotNull(snapshot)
            val timestamp = value.javaClass.getDeclaredField("at").apply { isAccessible = true }
            timestamp.setLong(value, 0L)
            assertEquals(0L, timestamp.getLong(value))
        }
    }

    private fun takeRequest(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
}
