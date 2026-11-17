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
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.ShyniClient
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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

/** Isolated preferences and a transport interceptor keep every request off real servers. */
@RunWith(AndroidJUnit4::class)
class PlaybackResolutionFlowTest {
    @Test fun oneResolutionResumesImmediatelyUsingThePreferredPhysicalPart() = withFixture(singleResolution = true) {
        main { model.playContinue(movie) }
        await { model.state.value.playback != null }
        assertNull(model.state.value.playbackResolution)
        assertEquals(90, model.state.value.playback?.partId)
        assertEquals(42_000L, model.state.value.playback?.resumeMs)
        assertEquals(listOf(90), decisions)
        assertEquals(1, versions.get())
    }

    @Test fun multipleResolutionsWaitForSelectionAndPreserveStartOver() = withFixture {
        main { model.play(movie, startOver = true) }
        awaitChoice()
        assertTrue(decisions.isEmpty())
        main { model.selectPlaybackResolution(91) }
        await { model.state.value.playback != null }
        assertEquals(91, model.state.value.playback?.partId)
        assertEquals(0L, model.state.value.playback?.resumeMs)
        assertNull(model.state.value.playbackResolution)
        assertEquals(listOf(91), decisions)
        assertEquals("Selection reuses the already authorized version rows", 1, versions.get())
    }

    @Test fun cancellationAndNavigationNeverStartAStaleSelection() = withFixture {
        main { model.playContinue(movie) }
        awaitChoice()
        main { model.dismissPlaybackResolution(); model.selectPlaybackResolution(90) }
        await { model.state.value.playbackResolution == null }
        assertNull(model.state.value.playbackResolution)
        assertTrue(decisions.isEmpty())
        main { model.playContinue(movie) }
        awaitChoice()
        main { model.openHome(); model.selectPlaybackResolution(90) }
        await { model.state.value.playbackResolution == null }
        assertNull(model.state.value.playbackResolution)
        assertNull(model.state.value.playback)
        assertTrue(decisions.isEmpty())
    }

    @Test fun unknownPartAndChangedAccountAreRejectedBeforePlaybackDecision() = withFixture {
        main { model.playContinue(movie) }
        awaitChoice()
        main { model.selectPlaybackResolution(999) }
        await { model.state.value.playbackResolution == null }
        assertNull(model.state.value.playbackResolution)
        main { model.playContinue(movie) }
        awaitChoice()
        main { client.token = "different-fixture-account"; model.selectPlaybackResolution(90) }
        await { model.state.value.playbackResolution == null }
        assertNull(model.state.value.playbackResolution)
        assertNull(model.state.value.playback)
        assertTrue(decisions.isEmpty())
    }

    @Test fun changingTheSourceScreenWithoutARequestCannotReusePendingChoice() = withFixture {
        main { model.playContinue(movie) }
        awaitChoice()
        main {
            state.value = state.value.copy(searchOpen = true, searchQuery = "다른 작품")
            model.selectPlaybackResolution(90)
        }
        await { model.state.value.playbackResolution == null }
        assertNull(model.state.value.playbackResolution)
        assertTrue(decisions.isEmpty())
    }

    private fun withFixture(singleResolution: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(singleResolution)
        try { fixture.block(); assertTrue(fixture.unexpected.isEmpty()) }
        finally { fixture.close() }
    }

    private class Fixture(singleResolution: Boolean) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val application = FixtureApplication(ApplicationProvider.getApplicationContext<Application>())
        private val store = ViewModelStore()
        val versions = AtomicInteger()
        val decisions = CopyOnWriteArrayList<Int>()
        val unexpected = CopyOnWriteArrayList<String>()
        val movie = MediaItem(23, 8, "합성 영화", "movie", viewItemId = 501, resumeMs = 42_000, durationMs = 120_000)
        private val transport = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw IOException("Fixture must never use DNS")
        }).addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            var status = 200
            val body = when (path) {
                "/playback/versions" -> {
                    versions.incrementAndGet()
                    val second = if (singleResolution) "1920x1080" else "4K"
                    """{"options":[{"metadata_item_id":23,"part_id":90,"resolution":"1080p"},
                        {"metadata_item_id":23,"part_id":91,"resolution":"$second"}]}"""
                }
                "/playback/decision" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val part = JSONObject(buffer.readUtf8()).getInt("part_id")
                    decisions += part
                    """{"selected":{"part_id":$part,"duration_ms":120000},"library_view_id":8,
                        "view_item_id":501,"decision":"direct_play"}"""
                }
                "/stream-ticket/90", "/stream-ticket/91" -> """{"ticket":"synthetic-ticket"}"""
                "/playback/progress" -> """{"items":[{"metadata_item_id":23,"position_ms":42000,"duration_ms":120000}]}"""
                else -> {
                    unexpected += "${request.method} $path"
                    status = 500
                    """{"detail":"unexpected fixture request"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = ShyniClient("https://example.invalid", "fixture-token", transport, "fixture-device")
        lateinit var model: AppViewModel
        lateinit var state: MutableStateFlow<AppState>

        init {
            main {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(AppViewModel(application))!!
                })[AppViewModel::class.java]
                AppViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(model, client)
                @Suppress("UNCHECKED_CAST")
                val mutable = AppViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<AppState>
                state = mutable
                state.value = AppState(loggedIn = true, server = "https://example.invalid", continueItems = listOf(movie))
            }
        }

        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
            while (!condition()) {
                model.state.value.error?.let { error("Unexpected fixture error: $it") }
                delay(10)
            }
        }
        suspend fun awaitChoice() = await { !model.state.value.busy && model.state.value.playbackResolution != null }
        fun close() {
            main { store.clear(); application.clearFixturePreferences() }
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }

    private class FixtureApplication(private val parent: Context) : Application() {
        private val prefix = "resolution_flow_${UUID.randomUUID()}_"
        private val names = linkedSetOf<String>()
        init {
            attachBaseContext(object : ContextWrapper(parent) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val isolated = prefix + name
                    names += isolated
                    return parent.getSharedPreferences(isolated, mode)
                }
            })
        }
        fun clearFixturePreferences() { names.forEach { parent.deleteSharedPreferences(it) } }
    }
}
