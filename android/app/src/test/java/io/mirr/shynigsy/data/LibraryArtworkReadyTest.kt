package io.mirr.shynigsy.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

class LibraryArtworkReadyTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val library = Library(8, "합성 영화", "movie")
    private val catalogStarted = CountDownLatch(1)
    private val progressStarted = CountDownLatch(1)
    private val releaseCatalog = CountDownLatch(1)
    private val releaseProgress = CountDownLatch(1)
    private val progressCalls = AtomicInteger()
    private val catalogCalls = AtomicInteger()
    @Volatile private var holdCatalog = false
    @Volatile private var holdProgress = false
    @Volatile private var catalog = rows(listOf(1, 2))
    @Volatile private var history = "{}"

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/libraries/8/movies" -> {
                        catalogCalls.incrementAndGet()
                        catalogStarted.countDown()
                        if (holdCatalog && !releaseCatalog.await(10, TimeUnit.SECONDS)) MockResponse().setResponseCode(504)
                        else MockResponse().setBody(catalog)
                    }
                    "/playback/progress" -> {
                        progressCalls.incrementAndGet()
                        progressStarted.countDown()
                        if (holdProgress && !releaseProgress.await(10, TimeUnit.SECONDS)) MockResponse().setResponseCode(504)
                        else MockResponse().setBody(history)
                    }
                    "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_artwork-fixture","expires_in":1800}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "artwork-fixture-account", deviceId = "artwork-fixture-device")
    }

    @After fun close() {
        releaseCatalog.countDown()
        releaseProgress.countDown()
        SpoilerAccessRegistry.clearAll()
        server.shutdown()
    }

    @Test fun artworkPageArrivesWhileHistoryIsWithheldAndFinalResultUsesFreshHistory() = runBlocking {
        holdProgress = true
        catalog = """[{"id":1,"title":"첫 영화","completed":true,"position_ms":90000,"thumb_url":"/images/one"},{"id":2,"title":"다음 영화","position_ms":80000}]"""
        history = """{"items":[{"metadata_item_id":1,"completed":true},{"metadata_item_id":2,"position_ms":22000,"completed":false}]}"""
        val ready = CompletableDeferred<MediaPage>()
        val pending = async { client.items(library, onCatalogReady = { ready.complete(it) }) }
        try {
            val artwork = withTimeout(5_000) { ready.await() }
            await(progressStarted)
            assertFalse("Artwork must not wait for the withheld history response", pending.isCompleted)
            assertEquals(listOf(1, 2), artwork.items.map { it.id })
            assertTrue(artwork.items.all { it.watchState == null && !it.watched && it.resumeMs == 0L })
            assertTrue(artwork.items.first().poster!!.endsWith("/images/one"))
            releaseProgress.countDown()
            val enriched = withTimeout(5_000) { pending.await() }
            assertTrue(enriched.items.first().watched)
            assertEquals(MediaWatchState.WATCHED, enriched.items.first().watchState)
            assertEquals(MediaWatchState.IN_PROGRESS, enriched.items[1].watchState)
            assertEquals(22_000L, enriched.items[1].resumeMs)
            assertEquals(1, progressCalls.get())
        } finally {
            releaseProgress.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun eachArtworkPageKeepsTheSameFullServerSnapshotAndExactPageBounds() = runBlocking {
        val order = listOf(2, 10) + (125 downTo 1).filter { it != 2 && it != 10 }
        catalog = rows(order)
        val previews = CopyOnWriteArrayList<MediaPage>()
        val finalPages = listOf(0, 60, 120).map { offset ->
            client.items(library, offset = offset, onCatalogReady = { previews += it })
        }
        assertEquals(listOf(60, 60, 5), previews.map { it.items.size })
        assertEquals(order, previews.flatMap { it.items }.map { it.id })
        assertEquals(order, finalPages.flatMap { it.items }.map { it.id })
        assertTrue(previews.all { it.total == 125 && it.items.all { item -> item.watchState == null } })
        assertTrue(finalPages.flatMap { it.items }.all { it.watchState == MediaWatchState.UNWATCHED })
        assertEquals(1, catalogCalls.get())
        assertEquals(3, progressCalls.get())
    }

    @Test fun watchFiltersNeverPublishUnverifiedArtworkRowsBeforeHistoryFiltering() = runBlocking {
        catalog = rows((1..125).toList())
        history = JSONObject().put("items", JSONArray((1..125 step 2).map {
            JSONObject().put("metadata_item_id", it).put("completed", true)
        })).toString()
        val callbacks = AtomicInteger()
        val page = client.items(library, watchFilter = LibraryWatchFilter.WATCHED,
            onCatalogReady = { callbacks.incrementAndGet() })
        assertEquals(0, callbacks.get())
        assertEquals(63, page.total)
        assertEquals((1..119 step 2).toList(), page.items.map { it.id })
        assertTrue(page.items.all { it.watched && it.watchState == MediaWatchState.WATCHED })
        assertEquals(2, progressCalls.get())
    }

    @Test fun accountChangeInsideArtworkCallbackCancelsBeforeStartingHistory() = runBlocking {
        val callbacks = AtomicInteger()
        val failure = runCatching {
            client.items(library, onCatalogReady = {
                callbacks.incrementAndGet()
                client.token = "replacement-artwork-account"
            })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, callbacks.get())
        assertEquals(1, catalogCalls.get())
        assertEquals(0, progressCalls.get())
    }

    @Test fun accountChangeWhileHistoryIsPendingRejectsItsLateEnrichedResult() = runBlocking {
        holdProgress = true
        val ready = CompletableDeferred<Unit>()
        val pending = async { runCatching { client.items(library, onCatalogReady = { ready.complete(Unit) }) } }
        try {
            withTimeout(5_000) { ready.await() }
            await(progressStarted)
            client.token = "replacement-artwork-account"
            releaseProgress.countDown()
            val outcome = withTimeout(5_000) { pending.await() }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertEquals(1, progressCalls.get())
        } finally {
            releaseProgress.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun privateAccessIsRequiredBeforeArtworkAndRecheckedAfterTheCallback() = runBlocking {
        val privateLibrary = library.copy(isSpoiler = true)
        val callbacks = AtomicInteger()
        val locked = runCatching { client.items(privateLibrary, onCatalogReady = { callbacks.incrementAndGet() }) }.exceptionOrNull()
        assertEquals(403, (locked as ApiException).status)
        assertEquals(0, server.requestCount)
        client.unlockSpoiler("1234")
        val failure = runCatching {
            client.items(privateLibrary, onCatalogReady = { page ->
                callbacks.incrementAndGet()
                assertTrue(page.items.all { it.isSpoiler })
                client.clearSpoilerGrant()
            })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, callbacks.get())
        assertEquals(0, progressCalls.get())
    }

    @Test fun cancellationInsideAnArtworkCallbackDoesNotStartHistoryOrReturnAResult() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var returned = false
        val pending = launch {
            client.items(library, onCatalogReady = { ready.complete(Unit); awaitCancellation() })
            returned = true
        }
        withTimeout(5_000) { ready.await() }
        pending.cancelAndJoin()
        assertFalse(returned)
        assertEquals(0, progressCalls.get())
        assertEquals(1, catalogCalls.get())
    }

    @Test fun privateRowsInAPublicCatalogStillRequireTheCapturedLiveGrantBeforeArtwork() = runBlocking {
        catalog = """[{"id":1,"title":"비공개 합성 영화","spoiler_enabled":true,"view_item_id":1001}]"""
        val previews = CopyOnWriteArrayList<MediaPage>()
        val locked = runCatching { client.items(library, onCatalogReady = { previews += it }) }.exceptionOrNull()
        assertEquals(403, (locked as ApiException).status)
        assertTrue(previews.isEmpty())
        assertEquals(0, progressCalls.get())
        client.unlockSpoiler("1234")
        val result = client.items(library, onCatalogReady = { previews += it })
        assertEquals(1, previews.size)
        assertTrue(previews.single().items.single().isSpoiler)
        assertNull(previews.single().items.single().watchState)
        assertTrue(result.items.single().isSpoiler)
        assertEquals(2, catalogCalls.get())
        assertEquals(1, progressCalls.get())
    }

    @Test fun anInvalidatedPendingCatalogNeverInvokesTheArtworkCallback() = runBlocking {
        holdCatalog = true
        val callbacks = AtomicInteger()
        val pending = async { runCatching { client.items(library, onCatalogReady = { callbacks.incrementAndGet() }) } }
        try {
            await(catalogStarted)
            client.token = "replacement-artwork-account"
            releaseCatalog.countDown()
            val outcome = withTimeout(5_000) { pending.await() }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertEquals(0, callbacks.get())
            assertEquals(0, progressCalls.get())
        } finally {
            releaseCatalog.countDown()
            pending.cancelAndJoin()
        }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Expected fixture request did not arrive", latch.await(5, TimeUnit.SECONDS))
    }

    private fun rows(ids: List<Int>): String = JSONArray(ids.map { id ->
        JSONObject().put("id", id).put("title", "합성 영화 $id").put("view_item_id", id + 1000)
    }).toString()
}
