package io.mirr.shynigsy

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState
import io.mirr.shynigsy.data.ShyniClient
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ViewModel flow with isolated preferences and an HTTP transport that never proceeds. */
@RunWith(AndroidJUnit4::class)
class ExplicitWatchStateFlowTest {
    @Test fun acknowledgedWatchedSurvivesStaleRefreshAndVersionFailure() {
        verifyConfirmedStateSurvivesRefresh(watched = true)
    }

    @Test fun acknowledgedUnwatchedSurvivesStaleRefreshAndVersionFailure() {
        verifyConfirmedStateSurvivesRefresh(watched = false)
    }

    private fun verifyConfirmedStateSurvivesRefresh(watched: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = FixtureApplication(ApplicationProvider.getApplicationContext<Application>())
        val store = ViewModelStore()
        val versions = AtomicInteger()
        val writes = AtomicInteger()
        val details = AtomicInteger()
        val progress = AtomicInteger()
        val acknowledged = AtomicBoolean()
        val acknowledgedVisible = AtomicBoolean()
        val regressions = CopyOnWriteArrayList<MediaItem>()
        val unexpectedRequests = CopyOnWriteArrayList<String>()
        val transport = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw IOException("Fixture must never use DNS")
        })
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                var status = 200
                val body = when {
                    path == "/playback/versions" && request.method == "POST" -> {
                        if (versions.incrementAndGet() == 1) {
                            """{"options":[{"metadata_item_id":23,"part_id":90}]}"""
                        } else {
                            status = 500
                            """{"detail":"fixture version refresh failed"}"""
                        }
                    }
                    path == "/continue-watching/23/watch-state" && request.method == "PUT" -> {
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        val payload = JSONObject(buffer.readUtf8())
                        check(payload.getInt("part_id") == 90)
                        check(payload.getInt("library_view_id") == 8)
                        check(payload.getInt("view_item_id") == 501)
                        check(payload.getBoolean("watched") == watched)
                        writes.incrementAndGet()
                        acknowledged.set(true)
                        """{"ok":true,"watched":$watched}"""
                    }
                    path == "/libraries/8/movies/23" -> {
                        details.incrementAndGet()
                        """{"id":23,"item_type":"movie","title":"서버에서 갱신된 제목","view_item_id":501,
                            "completed":${!watched},"duration_ms":null}"""
                    }
                    path == "/playback/progress" -> {
                        progress.incrementAndGet()
                        """{"items":[{"metadata_item_id":23,"completed":${!watched},"position_ms":42000,
                            "duration_ms":null}]}"""
                    }
                    path == "/libraries" -> "[]"
                    path == "/continue-watching" -> """{"items":[]}"""
                    path == "/spoiler/client/status" -> """{"available":false,"unlocked":false}"""
                    else -> {
                        unexpectedRequests += "${request.method} $path"
                        status = 500
                        """{"detail":"unexpected fixture request"}"""
                    }
                }
                // An application interceptor returns every response without chain.proceed().
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
        val client = ShyniClient("https://example.invalid", "fixture-token", transport, "fixture-device")
        val movie = MediaItem(23, 8, "영화", "movie", viewItemId = 501, watched = !watched,
            resumeMs = 42_000, watchState = if (watched) MediaWatchState.IN_PROGRESS else MediaWatchState.WATCHED)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var model: AppViewModel
        var observer: Job? = null
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
                state.value = AppState(loggedIn = true, server = "https://example.invalid", items = listOf(movie),
                    searchItems = listOf(movie), recentItems = listOf(movie), detailStack = listOf(MediaDetails(movie)))
                observer = observerScope.launch {
                    model.state.collect { published ->
                        val rows = (published.items + published.searchItems + published.recentItems +
                            published.detailStack.flatMap { listOf(it.item) + it.children + it.seasons })
                            .filter { it.id == movie.id && it.libraryId == movie.libraryId &&
                                it.type == movie.type && it.viewItemId == movie.viewItemId && !it.isSpoiler }
                        val expectedState = if (watched) MediaWatchState.WATCHED else MediaWatchState.UNWATCHED
                        fun correct(row: MediaItem) = row.watched == watched && row.resumeMs == 0L && row.watchState == expectedState
                        if (acknowledged.get() && rows.isNotEmpty() && rows.all(::correct)) acknowledgedVisible.set(true)
                        if (acknowledgedVisible.get()) regressions.addAll(rows.filterNot(::correct))
                    }
                }
                model.setWatched(movie, watched)
            }

            withContext(Dispatchers.IO) {
                withTimeout(15_000) {
                    while (!acknowledgedVisible.get() || model.state.value.busy ||
                        model.state.value.error == null || versions.get() < 2) delay(10)
                }
            }
            instrumentation.runOnMainSync {
                val state = model.state.value
                val expectedState = if (watched) MediaWatchState.WATCHED else MediaWatchState.UNWATCHED
                assertTrue(state.loggedIn)
                assertFalse(state.busy)
                assertEquals("fixture version refresh failed", state.error)
                assertEquals("서버에서 갱신된 제목", state.detailStack.single().item.title)
                assertEquals(watched, state.detailStack.single().item.watched)
                assertEquals(0L, state.detailStack.single().item.resumeMs)
                assertEquals(expectedState, state.detailStack.single().item.watchState)
                assertTrue("A stale refresh publication must never reverse an acknowledged change", regressions.isEmpty())
            }
            assertEquals(1, writes.get())
            assertEquals(2, versions.get())
            assertEquals(1, details.get())
            assertEquals(1, progress.get())
            assertTrue("Every HTTP request must be handled by the fixture", unexpectedRequests.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                observer?.cancel()
                observerScope.cancel()
                store.clear()
                application.clearFixturePreferences()
            }
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }

    /** An empty namespace prevents SessionStore.loadLast() from reading a real session or key. */
    private class FixtureApplication(private val parent: Context) : Application() {
        private val prefix = "explicit_watch_flow_${UUID.randomUUID()}_"
        private val preferenceNames = linkedSetOf<String>()

        init {
            attachBaseContext(object : ContextWrapper(parent) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val isolated = prefix + name
                    preferenceNames += isolated
                    return parent.getSharedPreferences(isolated, mode)
                }
            })
        }

        fun clearFixturePreferences() {
            preferenceNames.forEach { parent.deleteSharedPreferences(it) }
        }
    }
}
