package io.mirr.shynigsy

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.ShyniClient
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ViewModel cancellation flow; all HTTP is synthetic and never reaches DNS or a server. */
@RunWith(AndroidJUnit4::class)
class DetailDiscoveryFlowTest {
    private val parent = MediaItem(11, 8, "Synthetic parent", "movie", viewItemId = 101)
    private val actorA = CastMember("Synthetic actor A", tmdbId = 1)
    private val actorB = CastMember("Synthetic actor B", tmdbId = 2)

    @Test fun changingActorBeforeTheFirstResponsePublishesOnlyTheLatestActorWorks() = runBlocking {
        withFixture { fixture ->
            val incorrectRows = CopyOnWriteArrayList<Int>()
            val observer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                fixture.onMain {
                    observer.launch {
                        fixture.model.state.collect { state ->
                            val frame = state.detailDiscovery[detailDiscoveryKey(parent)]
                            if (frame?.selectedActor?.key == actorB.key)
                                incorrectRows.addAll(frame.actorItems.filter { it.id != 302 }.map { it.id })
                        }
                    }
                    fixture.model.selectDetailActor(actorA)
                }
                await(fixture.actorAEntered)
                fixture.onMain {
                    assertTrue(fixture.model.state.value.detailDiscovery[detailDiscoveryKey(parent)]!!.actorLoading)
                    assertFalse("Supplemental reads must not block the whole screen", fixture.model.state.value.busy)
                    fixture.model.selectDetailActor(actorB)
                }
                waitUntil {
                    fixture.model.state.value.detailDiscovery[detailDiscoveryKey(parent)]?.let {
                        it.selectedActor?.key == actorB.key && it.actorLoaded && !it.actorLoading
                    } == true
                }
                assertEquals(1L, fixture.releaseActorA.count)
                fixture.releaseActorA.countDown()
                await(fixture.actorAExited)
                delay(250)
                fixture.onMain {
                    val state = fixture.model.state.value
                    val frame = state.detailDiscovery[detailDiscoveryKey(parent)]!!
                    assertEquals(actorB, frame.selectedActor)
                    assertEquals(listOf(302), frame.actorItems.map { it.id })
                    assertTrue(frame.actorLoaded)
                    assertFalse(frame.actorLoading)
                    assertNull(frame.actorError)
                    assertFalse(state.busy)
                    assertEquals(parent, state.detailStack.single().item)
                    assertTrue(incorrectRows.isEmpty())
                }
                assertEquals(listOf("/people/tmdb/1", "/people/tmdb/2"), fixture.paths.toList())
            } finally { observer.cancel() }
        }
    }

    @Test fun leavingTheDetailCancelsAnActorReadWithoutRestoringTheFrameOrSettingGlobalBusy() = runBlocking {
        for (goHome in listOf(false, true)) withFixture { fixture ->
            val leftDetail = AtomicBoolean()
            val restoredFrame = AtomicBoolean()
            val observer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                fixture.onMain {
                    observer.launch {
                        fixture.model.state.collect { state ->
                            if (leftDetail.get() && (state.detailStack.isNotEmpty() || state.detailDiscovery.isNotEmpty()))
                                restoredFrame.set(true)
                        }
                    }
                    fixture.model.selectDetailActor(actorA)
                }
                await(fixture.actorAEntered)
                fixture.onMain {
                    assertFalse(fixture.model.state.value.busy)
                    if (goHome) fixture.model.openHome() else fixture.model.back()
                    leftDetail.set(true)
                    assertTrue(fixture.model.state.value.detailStack.isEmpty())
                    assertTrue(fixture.model.state.value.detailDiscovery.isEmpty())
                }
                fixture.releaseActorA.countDown()
                await(fixture.actorAExited)
                delay(250)
                fixture.onMain {
                    val state = fixture.model.state.value
                    assertTrue(state.loggedIn)
                    assertFalse(state.busy)
                    assertNull(state.error)
                    assertTrue(state.detailStack.isEmpty())
                    assertTrue(state.detailDiscovery.isEmpty())
                    if (goHome) assertNull(state.library) else assertEquals(8, state.library?.id)
                    assertFalse(restoredFrame.get())
                }
                assertEquals(listOf("/people/tmdb/1"), fixture.paths.toList())
            } finally { observer.cancel() }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(8_000) { while (!condition()) delay(10) }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Synthetic request must reach its gate", latch.await(8, TimeUnit.SECONDS))
    }

    private class Fixture(
        val model: AppViewModel,
        val actorAEntered: CountDownLatch,
        val actorAExited: CountDownLatch,
        val releaseActorA: CountDownLatch,
        val paths: CopyOnWriteArrayList<String>,
    ) {
        fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // This guard precedes preferences, Application construction, transport and ViewModel setup.
        assumeTrue("Only the isolated QA application may execute this fixture",
            instrumentation.targetContext.packageName.endsWith(".spoilerqa"))
        val application = FixtureApplication(instrumentation.targetContext.applicationContext)
        val store = ViewModelStore()
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paths = CopyOnWriteArrayList<String>()
        val unexpected = CopyOnWriteArrayList<String>()
        val transport = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw IOException("Synthetic fixture must never use DNS")
        }).addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            paths.add(path)
            var status = 200
            val body = when (path) {
                "/people/tmdb/1" -> {
                    entered.countDown()
                    try {
                        check(release.await(8, TimeUnit.SECONDS)) { "Synthetic actor gate timed out" }
                        localCredit(301)
                    } finally { exited.countDown() }
                }
                "/people/tmdb/2" -> localCredit(302)
                else -> { unexpected.add(path); status = 500; "{}" }
            }
            // Never call chain.proceed: no actual socket, DNS, account or server is reachable.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = ShyniClient("https://example.invalid", "synthetic-token", transport, "synthetic-device")
        lateinit var model: AppViewModel
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        modelClass.cast(AppViewModel(application))!!
                })[AppViewModel::class.java]
                AppViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(model, client)
                @Suppress("UNCHECKED_CAST")
                val state = AppViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<AppState>
                state.value = AppState(loggedIn = true, server = "https://example.invalid",
                    libraries = listOf(Library(8, "Synthetic View", "movie")),
                    library = Library(8, "Synthetic View", "movie"),
                    detailStack = listOf(MediaDetails(parent, castMembers = listOf(actorA, actorB))),
                    detailDiscovery = mapOf(detailDiscoveryKey(parent) to DetailDiscoveryState(parent, relatedLoaded = true)))
            }
            block(Fixture(model, entered, exited, release, paths))
            assertTrue("Every request must be served by the synthetic interceptor", unexpected.isEmpty())
        } finally {
            release.countDown()
            if (entered.count == 0L) withContext(Dispatchers.IO) { exited.await(8, TimeUnit.SECONDS) }
            instrumentation.runOnMainSync {
                store.clear()
                application.clearFixturePreferences()
            }
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }

    private fun localCredit(id: Int): String = """{"local_credits":[{"metadata_type":1,
        "library_view_id":8,"representative_metadata_item_id":$id,"view_item_id":${id + 1000},
        "title":"Synthetic actor work $id"}]}"""

    private class FixtureApplication(private val parentContext: Context) : Application() {
        private val prefix = "detail_discovery_flow_${UUID.randomUUID()}_"
        private val names = linkedSetOf<String>()
        init {
            attachBaseContext(object : ContextWrapper(parentContext) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val isolated = prefix + name
                    names += isolated
                    return parentContext.getSharedPreferences(isolated, mode)
                }
            })
        }
        fun clearFixturePreferences() { names.forEach { parentContext.deleteSharedPreferences(it) } }
    }
}
