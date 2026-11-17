package io.mirr.shynigsy

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.ShyniClient
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import io.mirr.shynigsy.player.PlaybackCompletionSignals.LocalCompletionStatus
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real decoder EOF plus delayed HTTP writes, entirely within the isolated QA app and loopback. */
@RunWith(AndroidJUnit4::class)
class PlaybackCompletionDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun naturalEofDisplaysWatchedBeforeDelayedServerAcknowledgement() = verifyEof(rejectWrite = false)

    @Test fun rejectedCompletionRollsBackOnlyTheTemporaryDisplay() = verifyEof(rejectWrite = true)

    private fun verifyEof(rejectWrite: Boolean) {
        val context = instrumentation.targetContext
        assertEquals("Completion fixtures must never run in the account application", "io.mirr.shyniflex.spoilerqa", context.packageName)
        assertFalse("Unlock the phone before validating natural playback EOF", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val previous = setOf("playback_engine", "playback_speed", "continuous_playback", "picture_settings_v1")
            .associateWith { settings.all[it] }
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val previousServer = encrypted.getString("last_server", null)
        val sessions = SessionStore(context)
        val movie = MediaItem(ITEM, LIBRARY, "합성 종료 표시 검사", "movie", durationMs = 6_000,
            viewItemId = VIEW_ITEM, resumeMs = 1_000, playbackPartId = PART, watchState = MediaWatchState.IN_PROGRESS)
        val fixtureApplication = FixtureApplication(context)
        val modelStore = ViewModelStore()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val video = File(context.cacheDir, "completion-display-${UUID.randomUUID()}.mp4")
        lateinit var model: AppViewModel
        lateinit var raw: MutableStateFlow<AppState>
        val naturalEof = AtomicBoolean()
        val completedActivity = AtomicReference<NativePlayerActivity?>()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity === completedActivity.get() && stage == Stage.DESTROYED) {
                naturalEof.set(field<Boolean>(activity as NativePlayerActivity, "completed"))
            }
        }
        CompletionFixture(rejectWrite).use { fixture ->
            val scope = requireNotNull(PlaybackCompletionSignals.scope(fixture.origin, TOKEN))
            try {
                instrumentation.context.assets.open("media3/gesture-av.mp4").use { input ->
                    video.outputStream().use { output -> input.copyTo(output) }
                }
                settings.edit().putString("playback_engine", "mpv").putFloat("playback_speed", 1f)
                    .putBoolean("continuous_playback", false).remove("picture_settings_v1").commit()
                sessions.save(fixture.origin, TOKEN, "합성 종료 검사")
                instrumentation.runOnMainSync {
                    model = ViewModelProvider(modelStore, object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(AppViewModel(fixtureApplication))!!
                    })[AppViewModel::class.java]
                    AppViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }
                        .set(model, ShyniClient(fixture.origin, TOKEN, deviceId = "completion-fixture"))
                    @Suppress("UNCHECKED_CAST")
                    raw = AppViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                        .get(model) as MutableStateFlow<AppState>
                    raw.value = AppState(loggedIn = true, server = fixture.origin, items = listOf(movie),
                        recentItems = listOf(movie), searchItems = listOf(movie), continueItems = listOf(movie),
                        detailStack = listOf(MediaDetails(movie)))
                    observers.launch { model.state.collect { } }
                    // Use the same confirmed-event consumer as MainActivity; no fake ACK is injected.
                    observers.launch { PlaybackCompletionSignals.confirmations.collect { model.acceptPlaybackConfirmations(it) } }
                    observers.launch { PlaybackCompletionSignals.presentation.collect { model.acceptPlaybackCompletionFailures(it) } }
                }
                waitUntil("The isolated catalog must publish its initial unwatched item", 3_000) {
                    model.state.value.items.singleOrNull()?.watchState == MediaWatchState.IN_PROGRESS
                }
                val spec = PlaybackSpec(movie.title, Uri.fromFile(video).toString(), itemId = ITEM, libraryId = LIBRARY,
                    partId = PART, viewItemId = VIEW_ITEM, durationMs = 6_000, sourceItem = movie.copy(resumeMs = 0),
                    sessionId = "completion-fixture-session", progressToken = "completion-fixture-context")
                lifecycle.addLifecycleCallback(callback)
                ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", spec.toJson())).use { scenario ->
                    scenario.onActivity {
                        completedActivity.set(it)
                        it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    waitUntil("A real MPV frame must render before natural EOF", 10_000) {
                        var visible = false
                        if (scenario.state != Lifecycle.State.DESTROYED) scenario.onActivity { activity ->
                            val active = field<Player?>(activity, "player")
                            active?.volume = 0f
                            active?.playerError?.let { throw AssertionError("Synthetic decode failed", it) }
                            visible = active != null && active.playbackState == Player.STATE_READY && active.isPlaying &&
                                active.videoSize.width > 0 && field<Boolean>(activity, "playbackFirstFrameReported")
                        }
                        visible
                    }
                    waitUntil("Natural EOF must close playback while completion HTTP remains blocked", 12_000) { naturalEof.get() }
                    waitUntil("EOF must immediately display watched without a server ACK", 1_500) {
                        PlaybackCompletionSignals.presentation.value.any { it.scope == scope && it.status == LocalCompletionStatus.PENDING } &&
                            model.state.value.items.singleOrNull()?.watched == true
                    }
                    assertTrue("A real completion write must reach the loopback server", fixture.completionRequested.await(2, TimeUnit.SECONDS))

                    // Longer than the per-attempt request timeout: the display must survive a retry,
                    // while only raw, unconfirmed data remains eligible for snapshot persistence.
                    val blockedUntil = SystemClock.elapsedRealtime() + 5_200
                    do {
                        assertEquals("The server must not acknowledge before the fixture releases it", 0, fixture.acceptedWrites.get())
                        assertWatched(model.state.value)
                        assertOriginal(raw.value)
                        Thread.sleep(100)
                    } while (SystemClock.elapsedRealtime() < blockedUntil)
                    fixture.release.countDown()

                    if (rejectWrite) {
                        waitUntil("Rejected completion must remove the temporary watched display", 12_000) {
                            fixture.rejectedWrites.get() > 0 && model.state.value.items.singleOrNull()?.watched == false &&
                                model.state.value.continueItems.any { it.id == ITEM } &&
                                model.state.value.error?.contains("저장하지 못했습니다") == true
                        }
                        assertOriginal(model.state.value)
                        assertOriginal(raw.value)
                        assertFalse("Rejected writes must not create confirmed server records",
                            PlaybackCompletionSignals.confirmations.value.any { it.scope == scope })
                        assertEquals(0, fixture.acceptedWrites.get())
                        assertTrue("The returned catalog must explain failed persistence", model.state.value.error?.contains("저장하지 못했습니다") == true)
                    } else {
                        waitUntil("Only the successful watched ACK may update the raw catalog", 12_000) {
                            fixture.acceptedWrites.get() > 0 && raw.value.items.singleOrNull()?.watched == true
                        }
                        assertWatched(model.state.value)
                        assertWatched(raw.value)
                        assertTrue(PlaybackCompletionSignals.confirmations.value.any { it.scope == scope })
                    }
                    waitUntil("The independent writer must finish draining after Activity destruction", 12_000) {
                        field<CoroutineScope>(requireNotNull(completedActivity.get()), "progressScope").coroutineContext[Job]?.isActive == false
                    }
                }
                fixture.assertHealthy()
            } finally {
                fixture.release.countDown()
                lifecycle.removeLifecycleCallback(callback)
                PlaybackCompletionSignals.discardLocals(PlaybackCompletionSignals.presentation.value.filter { it.scope == scope }.map { it.sequence })
                instrumentation.runOnMainSync { observers.cancel(); modelStore.clear() }
                sessions.clear(fixture.origin)
                encrypted.edit().apply {
                    if (previousServer == null) remove("last_server") else putString("last_server", previousServer)
                }.commit()
                settings.edit().apply {
                    for ((key, value) in previous) when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }.commit()
                video.delete()
                fixtureApplication.clear()
            }
        }
    }

    private fun assertWatched(state: AppState) {
        val rows = state.items + state.recentItems + state.searchItems + state.detailStack.map { it.item }
        assertEquals(4, rows.size)
        assertTrue("All visible copies need the immediate watched badge", rows.all {
            it.watched && it.watchState == MediaWatchState.WATCHED && it.resumeMs == 0L
        })
        assertTrue("A completed item must immediately leave continue watching", state.continueItems.isEmpty())
    }

    private fun assertOriginal(state: AppState) {
        val rows = state.items + state.recentItems + state.searchItems + state.detailStack.map { it.item }
        assertEquals(4, rows.size)
        assertTrue("Pending/failed completion must not overwrite stored state", rows.all {
            !it.watched && it.watchState == MediaWatchState.IN_PROGRESS && it.resumeMs == 1_000L
        })
        assertEquals(listOf(ITEM), state.continueItems.map { it.id })
    }

    private fun waitUntil(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail(message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private class FixtureApplication(private val parent: Context) : Application() {
        private val prefix = "completion_display_${UUID.randomUUID()}_"
        private val names = Collections.synchronizedSet(mutableSetOf<String>())
        private val storage = File(parent.cacheDir, prefix).apply { mkdirs() }
        init {
            attachBaseContext(object : ContextWrapper(parent) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val isolated = prefix + name
                    names += isolated
                    return parent.getSharedPreferences(isolated, mode)
                }
            })
        }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = storage
        fun clear() {
            synchronized(names) { names.toList() }.forEach { parent.deleteSharedPreferences(it) }
            storage.deleteRecursively()
        }
    }

    private class CompletionFixture(private val rejectWrite: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val workers = Executors.newFixedThreadPool(6) { task -> Thread(task, "completion-qa-http").apply { isDaemon = true } }
        private val failure = AtomicReference<Throwable?>()
        val release = CountDownLatch(1)
        val completionRequested = CountDownLatch(1)
        val acceptedWrites = AtomicInteger()
        val rejectedWrites = AtomicInteger()
        val origin get() = "http://127.0.0.1:${server.localPort}"
        private val listener = thread(name = "completion-qa-loopback", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept(); sockets += socket
                    workers.execute { respond(socket) }
                } catch (_: SocketTimeoutException) { }
                catch (error: SocketException) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Completion fixture failed", it) } }

        private fun respond(socket: Socket) {
            var delayed = false
            try {
                socket.use { connection ->
                    connection.soTimeout = 3_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val request = line(input)?.split(' ') ?: return
                    check(request.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val row = line(input) ?: error("Incomplete fixture headers")
                        if (row.isEmpty()) break
                        check(headers.size < 64)
                        val separator = row.indexOf(':'); check(separator > 0)
                        headers[row.substring(0, separator).lowercase()] = row.substring(separator + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..65_536)
                    val payload = ByteArray(length) { input.read().also { check(it >= 0) }.toByte() }
                    val body = if (length == 0) JSONObject() else JSONObject(payload.toString(Charsets.UTF_8))
                    val path = Uri.parse(request[1]).path
                    val watchWrite = path == "/continue-watching/$ITEM/watch-state" && request[0] == "PUT"
                    delayed = watchWrite || (path == "/playback/progress" && request[0] == "POST" &&
                        (body.optBoolean("completion_eligible") || body.optString("state") == "completed_candidate"))
                    if (delayed) {
                        completionRequested.countDown()
                        check(release.await(20, TimeUnit.SECONDS)) { "Test did not release completion response" }
                        if (rejectWrite) {
                            rejectedWrites.incrementAndGet()
                            json(connection, JSONObject().put("detail", "fixture completion rejected"), "400 Bad Request")
                            return
                        }
                    }
                    when (path) {
                        "/playback/progress" -> json(connection, JSONObject().put("ok", true))
                        "/continue-watching/$ITEM/watch-state" -> {
                            check(body.getBoolean("watched") && body.getInt("part_id") == PART)
                            check(body.getInt("library_view_id") == LIBRARY && body.getInt("view_item_id") == VIEW_ITEM)
                            json(connection, JSONObject().put("ok", true).put("watched", true))
                            acceptedWrites.incrementAndGet()
                        }
                        "/libraries/$LIBRARY/media_info/$PART" -> json(connection, JSONObject()
                            .put("item", JSONObject().put("id", ITEM)).put("markers", JSONArray())
                            .put("parts", JSONArray().put(JSONObject().put("id", PART).put("metadata_item_id", ITEM)
                                .put("file_path", "/synthetic/completion.mp4").put("container", "mp4")
                                .put("video_codec", "h264").put("width", 160).put("height", 90)
                                .put("audio_codec", "aac").put("subtitles", JSONArray()))))
                        "/subtitles/$PART", "/libraries/$LIBRARY/movies" -> json(connection, JSONArray())
                        else -> error("Unexpected fixture endpoint: ${request[0]} $path")
                    }
                }
            } catch (error: IOException) {
                // A held completion response intentionally exceeds one request attempt; the
                // client may close that socket before retrying the same immutable snapshot.
                if (!delayed && !server.isClosed) failure.compareAndSet(null, error)
            } catch (error: Throwable) { if (!server.isClosed) failure.compareAndSet(null, error) }
            finally { sockets -= socket }
        }

        private fun json(socket: Socket, data: Any, status: String = "200 OK") {
            val bytes = data.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }

        private fun line(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else error("Incomplete fixture request")
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
            error("Oversized fixture request")
        }

        override fun close() {
            release.countDown()
            server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            listener.join(1_000); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ITEM = 811
        const val LIBRARY = 805
        const val VIEW_ITEM = 817
        const val PART = 877
        const val TOKEN = "completion-synthetic-token"
    }
}
