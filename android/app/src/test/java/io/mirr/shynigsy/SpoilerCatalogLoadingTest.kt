package io.mirr.shynigsy

import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.ShyniClient
import io.mirr.shynigsy.data.SpoilerAccessRegistry
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real client scheduling against numeric loopback, using only an isolated synthetic PIN grant. */
class SpoilerCatalogLoadingTest {
    private lateinit var server: MockWebServer
    private lateinit var address: String
    private var reply: (RecordedRequest) -> MockResponse = { json("{}", 404) }
    private val privatePath = "/spoiler/client/libraries"
    private val publicPath = "/libraries"
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val privateRequests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/spoiler/client/unlock") return json("""{"token":"spc_priority-fixture","expires_in":1800}""")
                    if (path == privatePath) privateRequests += request
                    return reply(request)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        address = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun privateCatalog() = json("""{"libraries":[{"library_view_id":9,"name":"합성 비공개","type":"movie"}]}""")
    private fun publicCatalog() = json("""[{"id":1,"name":"합성 공개","type":"movie"}]""")
    private suspend fun client(onFailed: (Call) -> Unit = {}): ShyniClient {
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().url.encodedPath != "/spoiler/client/unlock") calls += call.request().url.encodedPath
            }
            override fun callFailed(call: Call, ioe: IOException) { onFailed(call) }
        }).build()
        return ShyniClient(address, "fixture-account", http, "fixture-device").also { it.unlockSpoiler("1234") }
    }
    private fun assertPrivateAuthorization() {
        val request = privateRequests.single()
        assertEquals("GET", request.method)
        assertEquals("Bearer fixture-account", request.getHeader("Authorization"))
        assertEquals("fixture-device", request.getHeader("X-Device-Id"))
        assertEquals("spc_priority-fixture", request.getHeader("X-Shyni-Spoiler"))
    }

    @Test fun privateCatalogIsEnqueuedFirstAndCompletesWhileBothPublicCatalogSlotsAreStalled() { runBlocking {
        withTimeout(20_000) {
            val publicArrived = CountDownLatch(2)
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                privatePath -> privateCatalog()
                publicPath, "/continue-watching" -> {
                    publicArrived.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> json("{}", 404)
            } }
            val active = client()
            val priority = startPriorityHomeCatalog { active.spoilerLibraries() }
            assertEquals("UNDISPATCHED must enqueue before the caller starts public work", listOf(privatePath), calls.toList())
            val publicLibraries = async(Dispatchers.Default) { active.libraries() }
            val publicContinue = async(Dispatchers.Default) { active.continueWatching() }
            try {
                val libraries = priority.await().getOrThrow()
                assertEquals(listOf(Library(9, "합성 비공개", "movie", true)), libraries)
                assertTrue(withContext(Dispatchers.IO) { publicArrived.await(5, TimeUnit.SECONDS) })
                assertTrue(publicLibraries.isActive); assertTrue(publicContinue.isActive)
                assertPrivateAuthorization()
            } finally { publicLibraries.cancelAndJoin(); publicContinue.cancelAndJoin() }
        }
    } }

    @Test fun aNonCancellationCatalogFailureIsCapturedWithoutCancellingItsPublicSibling() { runBlocking {
        withTimeout(20_000) {
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                privatePath -> json("""{"detail":"synthetic optional catalog failure"}""", 503)
                publicPath -> publicCatalog()
                else -> json("{}", 404)
            } }
            val active = client()
            coroutineScope {
                val priority = startPriorityHomeCatalog { active.spoilerLibraries() }
                val public = async(Dispatchers.Default) { active.libraries() }
                val failure = priority.await().exceptionOrNull() as ApiException
                assertEquals(503, failure.status)
                assertEquals(listOf(Library(1, "합성 공개", "movie")), public.await())
                assertTrue(currentCoroutineContext().isActive)
                assertPrivateAuthorization()
            }
        }
    } }

    @Test fun theCallerCanPromoteAnAuthenticationFailureToCancelTheWholeLoadingGroup() { runBlocking {
        withTimeout(20_000) {
            val publicArrived = CountDownLatch(1)
            val publicCancelled = CountDownLatch(1)
            val publicPublished = AtomicBoolean(false)
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                privatePath -> {
                    if (!publicArrived.await(5, TimeUnit.SECONDS)) json("{}", 500)
                    else json("""{"detail":"synthetic expired authentication"}""", 401)
                }
                publicPath -> { publicArrived.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                else -> json("{}", 404)
            } }
            val active = client { if (it.request().url.encodedPath == publicPath) publicCancelled.countDown() }
            val outcome = runCatching {
                coroutineScope {
                    val priority = startPriorityHomeCatalog { active.spoilerLibraries() }
                    val public = async(Dispatchers.Default) { active.libraries() }
                    priority.await().getOrThrow()
                    public.await().also { publicPublished.set(true) }
                }
            }
            assertEquals(401, (outcome.exceptionOrNull() as ApiException).status)
            assertTrue(withContext(Dispatchers.IO) { publicCancelled.await(5, TimeUnit.SECONDS) })
            assertFalse(publicPublished.get())
            assertTrue(currentCoroutineContext().isActive)
            assertPrivateAuthorization()
        }
    } }

    @Test fun parentCancellationCancelsTheNativeHttpCallAndCannotPublishALateResult() { runBlocking {
        withTimeout(20_000) {
            val cancellation = startPriorityHomeCatalog<List<Library>> { throw CancellationException("synthetic cancellation") }
            val cancelledResult = runCatching { cancellation.await() }.exceptionOrNull()
            assertTrue("Cancellation must be rethrown rather than wrapped as an ordinary failure", cancelledResult is CancellationException)
            val privateArrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val httpCancelled = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val child = CompletableDeferred<Deferred<Result<List<Library>>>>()
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                privatePath -> {
                    privateArrived.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) json("{}", 500) else privateCatalog()
                }
                else -> json("{}", 404)
            } }
            val active = client { if (it.request().url.encodedPath == privatePath) httpCancelled.countDown() }
            val parent = launch(Dispatchers.Default) {
                val priority = startPriorityHomeCatalog { active.spoilerLibraries() }
                child.complete(priority)
                priority.await().getOrThrow()
                published.set(true)
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { privateArrived.await(5, TimeUnit.SECONDS) })
                val loading = child.await()
                parent.cancelAndJoin()
                assertTrue(withContext(Dispatchers.IO) { httpCancelled.await(5, TimeUnit.SECONDS) })
                release.countDown()
                assertTrue(parent.isCancelled); assertTrue(loading.isCancelled)
                assertFalse(published.get())
                assertPrivateAuthorization()
            } finally { release.countDown(); parent.cancelAndJoin() }
        }
    } }
}
