package io.mirr.shynigsy.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All identities and works are synthetic; no account or server data is used. */
class ActorParallelLookupTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<String>()
    private val providerGate = CountDownLatch(1)
    private val currentViewGate = CountDownLatch(1)
    private val otherViewGate = CountDownLatch(1)
    private val privateViewGate = CountDownLatch(1)
    @Volatile private var blockProvider = false
    @Volatile private var blockCurrentView = false
    @Volatile private var blockOtherView = false
    @Volatile private var failOtherView = false
    @Volatile private var blockPrivateView = false
    @Volatile private var providerStatus = 200
    @Volatile private var currentViewStatus = 200
    @Volatile private var otherViewStatus = 200
    @Volatile private var includeProvider = true
    @Volatile private var duplicateViewIdentity = false
    private val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
    private val parent = MediaItem(1, 9, "Current", "show", viewItemId = 1001)
    private val libraries = listOf(Library(9, "Current TV", "tv"), Library(10, "Other TV", "tv"),
        Library(8, "Movies", "movie"), Library(11, "Locked TV", "tv", isSpoiler = true))

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += path
                    return when (path) {
                        "/people/tmdb/77" -> {
                            if (blockProvider) providerGate.await(8, TimeUnit.SECONDS)
                            if (providerStatus != 200) MockResponse().setResponseCode(providerStatus)
                            else MockResponse().setBody(provider())
                        }
                        "/libraries/9/shows" -> {
                            if (blockCurrentView) currentViewGate.await(8, TimeUnit.SECONDS)
                            if (currentViewStatus != 200) MockResponse().setResponseCode(currentViewStatus)
                            else MockResponse().setBody(shows(1..17, 9))
                        }
                        "/libraries/10/shows" -> {
                            if (blockOtherView) otherViewGate.await(8, TimeUnit.SECONDS)
                            if (otherViewStatus != 200) MockResponse().setResponseCode(otherViewStatus)
                            else if (failOtherView) MockResponse().setResponseCode(404)
                            else MockResponse().setBody(shows(21..21, 10))
                        }
                        "/libraries/11/shows" -> {
                            if (blockPrivateView) privateViewGate.await(8, TimeUnit.SECONDS)
                            MockResponse().setBody(shows(41..41, 11))
                        }
                        "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_synthetic-private","expires_in":1800}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() {
        providerGate.countDown()
        currentViewGate.countDown()
        otherViewGate.countDown()
        privateViewGate.countDown()
        server.shutdown()
        SpoilerAccessRegistry.clearAll()
    }

    @Test fun combinesSeventeenLocalWorksWithTwoProviderCreditsWithoutMovieScanning() = runBlocking {
        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)
        assertTrue((1..17).all { id -> result.items.any { it.libraryId == 9 && it.id == id } })
        assertTrue(result.items.any { it.libraryId == 8 && it.id == 300 })
        assertEquals(1, result.items.count { it.libraryId == 9 && it.viewItemId == 1001 })
        assertTrue(result.items.any { it.libraryId == 10 && it.id == 21 })
        assertFalse(requests.any { it.contains("/movies") || it.startsWith("/libraries/11/") })
    }

    @Test fun currentViewIsPublishedBeforeSlowProviderAndOtherView() = runBlocking {
        blockProvider = true
        blockOtherView = true
        val first = CompletableDeferred<List<MediaItem>>()
        val work = async(Dispatchers.Default) {
            client.actorWorks(actor, parent, libraries, allowLocalFallback = true) { items, _, _ ->
                if (items.any { it.libraryId == 9 && it.id == 17 }) first.complete(items)
            }
        }
        try {
            val visible = withTimeout(3_000L) { first.await() }
            assertTrue(visible.any { it.libraryId == 9 && it.id == 17 })
            assertFalse(work.isCompleted)
        } finally {
            providerGate.countDown()
            otherViewGate.countDown()
        }
        assertTrue(withTimeout(5_000L) { work.await() }.items.any { it.libraryId == 10 && it.id == 21 })
    }

    @Test fun failedViewKeepsFastResultsAndSignalsIncomplete() = runBlocking {
        failOtherView = true
        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)
        assertTrue(result.items.any { it.libraryId == 9 && it.id == 17 })
        assertTrue(result.incomplete)
        assertFalse(result.items.any { it.libraryId == 10 })
    }

    @Test fun deniedCurrentViewFailsEvenWhenOtherViewSucceeds() = runBlocking {
        currentViewStatus = 403
        val failure = runCatching { client.actorWorks(actor, parent, libraries, allowLocalFallback = true) }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 403)
    }

    @Test fun deniedOtherViewPreservesResultsAndSignalsIncomplete() = runBlocking {
        otherViewStatus = 403
        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)
        assertTrue(result.items.any { it.libraryId == 9 && it.id == 17 })
        assertTrue(result.incomplete)
    }

    @Test fun sameViewIdentityDeduplicatesDifferentRepresentativesButDistinctViewsRemainSeparate() = runBlocking {
        duplicateViewIdentity = true
        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)
        val currentView = result.items.filter { it.libraryId == 9 }
        assertEquals(1, currentView.count { it.viewItemId == 1001 })
        assertEquals(1, currentView.single { it.viewItemId == 1001 }.id)
        assertTrue(currentView.any { it.id == 1 && it.viewItemId == 1999 })
        assertTrue(result.items.any { it.libraryId == 10 && it.id == 21 })
    }

    @Test fun cancellationStopsPendingRequestsWithoutLaterProgress() = runBlocking {
        blockProvider = true
        blockOtherView = true
        val first = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<List<MediaItem>>()
        val work = async(Dispatchers.Default) {
            client.actorWorks(actor, parent, libraries, allowLocalFallback = true) { items, _, _ ->
                updates += items
                if (items.any { it.libraryId == 9 }) first.complete(Unit)
            }
        }
        try {
            withTimeout(3_000L) { first.await() }
            work.cancelAndJoin()
            val count = updates.size
            providerGate.countDown()
            otherViewGate.countDown()
            kotlinx.coroutines.delay(100L)
            assertEquals(count, updates.size)
        } finally {
            providerGate.countDown()
            otherViewGate.countDown()
        }
    }

    @Test fun changedAccountRejectsLateProviderResults() = runBlocking {
        blockProvider = true
        val first = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<List<MediaItem>>()
        val work = async(Dispatchers.Default) {
            runCatching { client.actorWorks(actor, parent, libraries, allowLocalFallback = true) { items, _, _ ->
                updates += items
                if (items.any { it.libraryId == 9 }) first.complete(Unit)
            } }
        }
        try {
            withTimeout(3_000L) { first.await() }
            client.token = "different-synthetic-account"
            val count = updates.size
            providerGate.countDown()
            val failure = withTimeout(5_000L) { work.await() }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.status == 401)
            assertEquals(count, updates.size)
        } finally { providerGate.countDown() }
    }

    @Test fun lockingPrivateAccessRejectsPendingResultsAndProgress() = runBlocking {
        client.unlockSpoiler("1234")
        blockProvider = true
        blockPrivateView = true
        val first = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<List<MediaItem>>()
        val work = async(Dispatchers.Default) {
            runCatching { client.actorWorks(actor, parent, libraries, allowLocalFallback = true) { items, _, _ ->
                updates += items
                if (items.any { it.libraryId == 9 }) first.complete(Unit)
            } }
        }
        try {
            withTimeout(3_000L) { first.await() }
            client.clearSpoilerGrant()
            val count = updates.size
            providerGate.countDown()
            privateViewGate.countDown()
            val failure = withTimeout(5_000L) { work.await() }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.status == 403)
            assertEquals(count, updates.size)
            assertTrue(updates.flatten().none { it.libraryId == 11 })
        } finally {
            providerGate.countDown()
            privateViewGate.countDown()
        }
    }

    @Test fun relatedWorksUsesLocalTvWhenProviderFailsWithoutMovieScanning() = runBlocking {
        providerStatus = 503
        val works = client.relatedWorks(parent, listOf(actor), libraries)
        assertTrue(works.any { it.libraryId == 9 && it.id == 17 })
        assertFalse(works.any { it.libraryId == 9 && it.id == 1 })
        assertFalse(requests.any { it.contains("/movies") })
    }

    @Test fun relatedWorksPropagatesProviderAuthorizationFailure() = runBlocking {
        providerStatus = 403
        val failure = runCatching { client.relatedWorks(parent, listOf(actor), libraries) }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 403)
        assertFalse(requests.any { it.endsWith("/shows") })
    }

    @Test fun relatedWorksPublishesCurrentViewThenAddsSlowOtherView() = runBlocking {
        providerStatus = 503
        blockOtherView = true
        val first = CompletableDeferred<List<MediaItem>>()
        val work = async(Dispatchers.Default) {
            client.relatedWorks(parent, listOf(actor), libraries) { partial ->
                if (partial.any { it.libraryId == 9 && it.id == 17 }) first.complete(partial)
            }
        }
        try {
            assertTrue(withTimeout(3_000L) { first.await() }.any { it.libraryId == 9 && it.id == 17 })
            assertFalse(work.isCompleted)
        } finally { otherViewGate.countDown() }
        val works = withTimeout(5_000L) { work.await() }
        assertTrue(works.any { it.libraryId == 9 && it.id == 17 })
        assertTrue(works.any { it.libraryId == 10 && it.id == 21 })
    }

    @Test fun relatedWorksPropagatesDeniedCurrentView() = runBlocking {
        providerStatus = 503
        currentViewStatus = 403
        val failure = runCatching { client.relatedWorks(parent, listOf(actor), libraries) }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 403)
    }

    @Test fun relatedWorksPublishesOtherViewWhileCurrentViewIsSlow() = runBlocking {
        providerStatus = 503
        blockCurrentView = true
        val first = CompletableDeferred<List<MediaItem>>()
        val work = async(Dispatchers.Default) {
            client.relatedWorks(parent, listOf(actor), libraries) { partial ->
                if (partial.any { it.libraryId == 10 && it.id == 21 }) first.complete(partial)
            }
        }
        try {
            assertTrue(withTimeout(3_000L) { first.await() }.any { it.libraryId == 10 && it.id == 21 })
            assertFalse(work.isCompleted)
        } finally { currentViewGate.countDown() }
        val works = withTimeout(5_000L) { work.await() }
        assertTrue(works.any { it.libraryId == 9 && it.id == 17 })
        assertTrue(works.any { it.libraryId == 10 && it.id == 21 })
    }

    @Test fun relatedWorksPreservesResultsAndFlagsOtherViewFailure() = runBlocking {
        providerStatus = 503
        for (status in listOf(403, 500)) {
            otherViewStatus = status
            var incomplete = false
            val works = client.relatedWorks(parent, listOf(actor), libraries, onIncomplete = { incomplete = true })
            assertTrue(works.any { it.libraryId == 9 && it.id == 17 })
            assertTrue(incomplete)
        }
    }

    @Test fun relatedWorksDoesNotFlagCompleteFallbackAsIncomplete() = runBlocking {
        providerStatus = 503
        var incomplete = false
        val works = client.relatedWorks(parent, listOf(actor), libraries, onIncomplete = { incomplete = true })
        assertTrue(works.any { it.libraryId == 9 && it.id == 17 })
        assertTrue(works.any { it.libraryId == 10 && it.id == 21 })
        assertFalse(incomplete)
    }

    private fun provider() = if (!includeProvider) """{"local_credits":[]}""" else if (duplicateViewIdentity) """{"local_credits":[
        {"metadata_type":2,"library_view_id":9,"metadata_item_id":99,"view_item_id":1001,"title":"Older representative"},
        {"metadata_type":2,"library_view_id":9,"metadata_item_id":1,"view_item_id":1999,"title":"Different work"}]}
    """ else """{"local_credits":[
        {"metadata_type":2,"library_view_id":9,"metadata_item_id":1,"view_item_id":1001,"title":"Current"},
        {"metadata_type":1,"library_view_id":8,"metadata_item_id":300,"view_item_id":1300,"title":"Movie"}]}
    """

    private fun shows(ids: IntRange, libraryId: Int): String = ids.joinToString(prefix = "[", postfix = "]") { id ->
        val view = if (libraryId == 9) 1000 + id else 2000 + id
        """{"id":$id,"view_item_id":$view,"title":"Synthetic $id"}"""
    }
}
