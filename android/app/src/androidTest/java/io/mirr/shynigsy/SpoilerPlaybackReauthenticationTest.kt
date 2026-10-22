package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.ShyniClient
import io.mirr.shynigsy.data.SpoilerAccessRegistry
import io.mirr.shynigsy.player.NativePlayerActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

/** The package guard runs before any preferences, session store, PIN or network fixture is used. */
@RunWith(AndroidJUnit4::class)
class SpoilerPlaybackReauthenticationTest {
    @Test fun pausedForegroundPlaybackRotatesGrantAndTicketThenBackgroundForgetsPin() = withFixture { fixture, client, intent ->
        ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
            val pausedPosition = pauseAtFixturePosition(scenario)
            val original = currentSpec(scenario)
            waitFor(scenario, "A paused foreground player must reconnect with a fresh ticket", 22_000) { activity ->
                val spec = field<PlaybackSpec>(activity, "spec")
                spec.progressToken == CONTEXT_2 && spec.sessionId != original.sessionId &&
                    field<Player?>(activity, "player")?.playbackState == Player.STATE_READY &&
                    field<Boolean>(activity, "prepared") && !field<Boolean>(activity, "refreshing") &&
                    field<Any?>(activity, "spoilerRecovery") == null
            }
            val rotated = currentSpec(scenario)
            scenario.onActivity { activity ->
                val player = field<Player?>(activity, "player")!!
                assertFalse("Reauthentication must preserve the user's paused intent", player.playWhenReady)
                assertTrue("Reauthentication must preserve the source-clock position", abs(player.currentPosition - pausedPosition) < 750)
                assertEquals(PART_ID, rotated.partId)
                assertEquals(VIEW_ID, rotated.libraryId)
                assertEquals(VIEW_ITEM_ID, rotated.viewItemId)
                assertEquals(original.conversionHeight, rotated.conversionHeight)
                assertTrue(rotated.sourceItem?.isSpoiler == true)
                assertFalse("A live foreground private player must stay open", activity.isFinishing)
                assertEquals(View.GONE, field<View>(activity, "errorPanel").visibility)
            }
            waitUntil("MPV must request the rotated stream with its new grant") {
                fixture.requests().any { it.path == "/stream/$PART_ID" && it.query["ticket"] == TICKET_2 &&
                    it.headers["x-shyni-spoiler"] == GRANT_2 }
            }
            assertEquals("Only one automatic PIN request is allowed", 2, fixture.unlocks.get())
            val decision = fixture.requests().single { it.path == "/playback/decision" }
            assertEquals(PART_ID, decision.body.getInt("part_id"))
            assertEquals(VIEW_ID, decision.body.getInt("library_view_id"))
            assertEquals(VIEW_ITEM_ID, decision.body.getInt("view_item_id"))
            assertEquals(GRANT_2, decision.headers["x-shyni-spoiler"])
            assertTrue("Tickets must bind the fresh playback context", fixture.requests().any {
                it.path == "/stream-ticket/$PART_ID" && it.query["playback_context_token"] == CONTEXT_2
            })
            assertFalse("PIN and grant must not enter an Activity playback payload", rotated.toJson().let {
                it.contains(FIXTURE_PIN) || it.contains(GRANT_1) || it.contains(GRANT_2)
            })
            fixture.assertNoNewRenewEndpoint()

            scenario.moveToState(Lifecycle.State.CREATED)
            waitUntil("Leaving the app must revoke the shared grant", 3_000) { !client.spoilerUnlocked }
            if (scenario.state != Lifecycle.State.DESTROYED) runCatching {
                scenario.moveToState(Lifecycle.State.RESUMED)
            }.getOrElse { if (scenario.state != Lifecycle.State.DESTROYED) throw it }
            waitUntil("A background-locked player must close", 3_000) { scenario.state == Lifecycle.State.DESTROYED }
            val before = fixture.unlocks.get()
            val rejected = runBlocking { runCatching { client.reauthenticateSpoilerPlayback(rotated, pausedPosition) }.exceptionOrNull() }
            assertTrue("Backgrounding must remove the reusable PIN", rejected is ApiException && rejected.status == 403)
            Thread.sleep(400)
            assertEquals("A wiped PIN must not be sent again after backgrounding", before, fixture.unlocks.get())
            assertFalse(client.spoilerPlaybackReauthenticationFailed)
            fixture.assertHealthy()
        }
    }

    @Test fun deniedAutomaticUnlockIsNotRetriedAndRemovesTheSensitivePlayer() = withFixture(denyReauthentication = true) { fixture, client, intent ->
        ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
            pauseAtFixturePosition(scenario)
            waitFor(scenario, "A denied PIN must leave a non-sensitive recovery screen", 22_000) { activity ->
                client.spoilerPlaybackReauthenticationFailed && !client.spoilerUnlocked &&
                    field<Player?>(activity, "player") == null && !field<Boolean>(activity, "refreshing") &&
                    field<View>(activity, "errorPanel").visibility == View.VISIBLE
            }
            scenario.onActivity { activity ->
                assertFalse("A PIN error must remain explainable in the player window", activity.isFinishing)
                assertNotEquals(View.VISIBLE, field<PlayerView>(activity, "playerView").visibility)
                assertNotEquals(View.VISIBLE, field<View>(activity, "subtitleOverlay").visibility)
                assertNotNull(field<Any?>(activity, "spoilerRecovery"))
            }
            Thread.sleep(1_500)
            assertEquals("A denied automatic PIN must be attempted exactly once", 2, fixture.unlocks.get())
            assertFalse("A denied PIN must not request a new playback decision", fixture.requests().any { it.path == "/playback/decision" })
            assertFalse("A denied PIN must not load a fresh stream", fixture.requests().any { it.query["ticket"] == TICKET_2 })
            fixture.assertNoNewRenewEndpoint()
            fixture.assertHealthy()
        }
    }

    private fun withFixture(denyReauthentication: Boolean = false, test: (FixtureServer, ShyniClient, Intent) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("This test may only touch the dedicated synthetic QA package", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before checking the native player", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue("The isolated QA manifest must permit its loopback HTTP fixture", NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val oldDevice = settings.getString("device_id", null)
        val oldSpeed = settings.getFloat("playback_speed", 1f)
        val sessions = SessionStore(context)
        val clip = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        FixtureServer(clip, denyReauthentication).use { fixture ->
            SpoilerAccessRegistry.clearAll()
            // Allow any previous test Activity's delayed background clear to finish before unlocking.
            Thread.sleep(500)
            settings.edit().putString("device_id", DEVICE_ID).putFloat("playback_speed", 1f).commit()
            val client = ShyniClient(fixture.origin, SESSION, deviceId = DEVICE_ID)
            try {
                sessions.save(fixture.origin, SESSION, "합성 QA 계정")
                runBlocking { client.unlockSpoiler(FIXTURE_PIN) }
                assertTrue(client.spoilerUnlocked)
                val item = MediaItem(ITEM_ID, VIEW_ID, "합성 비공개 영상", "movie", durationMs = 6_000,
                    viewItemId = VIEW_ITEM_ID, isSpoiler = true, playbackPartId = PART_ID)
                val spec = PlaybackSpec(item.title, fixture.origin + "/stream/$PART_ID?ticket=$TICKET_1",
                    itemId = ITEM_ID, libraryId = VIEW_ID, partId = PART_ID, viewItemId = VIEW_ITEM_ID,
                    durationMs = 6_000, sessionId = "fixture-session1", progressToken = CONTEXT_1,
                    sourceItem = item, requestedPartId = PART_ID)
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", spec.toJson())
                test(fixture, client, intent)
            } finally {
                SpoilerAccessRegistry.clearAll()
                sessions.clear(fixture.origin)
                settings.edit().apply {
                    if (oldDevice == null) remove("device_id") else putString("device_id", oldDevice)
                    putFloat("playback_speed", oldSpeed)
                }.commit()
            }
        }
    }

    private fun pauseAtFixturePosition(scenario: ActivityScenario<NativePlayerActivity>): Long {
        var sought = false
        waitFor(scenario, "The synthetic clip must prepare before the grant's renewal window", 7_000) { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val player = field<Player?>(activity, "player") ?: return@waitFor false
            player.volume = 0f
            if (player.playbackState != Player.STATE_READY || !field<Boolean>(activity, "prepared")) return@waitFor false
            player.pause()
            if (!sought) { player.seekTo(1_500); sought = true; return@waitFor false }
            !player.playWhenReady && player.currentPosition in 1_000L..2_250L
        }
        var position = 0L
        scenario.onActivity { position = field<Player?>(it, "player")!!.currentPosition }
        return position
    }

    private fun currentSpec(scenario: ActivityScenario<NativePlayerActivity>): PlaybackSpec {
        var value: PlaybackSpec? = null
        scenario.onActivity { value = field(it, "spec") }
        return value!!
    }

    private fun waitFor(scenario: ActivityScenario<NativePlayerActivity>, message: String, timeoutMs: Long, predicate: (NativePlayerActivity) -> Boolean) {
        waitUntil(message, timeoutMs) {
            assertNotEquals("The private player closed before the assertion: $message", Lifecycle.State.DESTROYED, scenario.state)
            var found = false
            scenario.onActivity { found = predicate(it) }
            found
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 3_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        assertTrue(message, predicate())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private data class Request(val method: String, val path: String, val query: Map<String, String?>,
        val headers: Map<String, String>, val body: JSONObject)

    /** A bounded HTTP fixture; it never resolves or contacts a non-loopback address. */
    private class FixtureServer(private val clip: ByteArray, private val denyReauthentication: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "spoiler-qa-http").apply { isDaemon = true } }
        private val records = Collections.synchronizedList(mutableListOf<Request>())
        private val failure = AtomicReference<Throwable?>()
        val unlocks = AtomicInteger()
        val origin: String get() = "http://127.0.0.1:${server.localPort}"
        private val listener = thread(name = "spoiler-qa-loopback", isDaemon = true) {
            while (!server.isClosed) {
                try { val socket = server.accept(); workers.execute { handle(socket) } }
                catch (_: SocketTimeoutException) { }
                catch (error: SocketException) { if (!server.isClosed) failure.compareAndSet(null, error) }
                catch (error: Throwable) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        fun requests(): List<Request> = synchronized(records) { records.toList() }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic HTTP fixture failed", it) } }
        fun assertNoNewRenewEndpoint() = assertFalse("Use the existing unlock endpoint only", requests().any { it.path.contains("renew") })

        private fun handle(socket: Socket) {
            socket.use { connection ->
                var streaming = false
                try {
                    connection.soTimeout = 3_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val first = line(input)?.split(' ') ?: return
                    check(first.size == 3)
                    val headers = mutableMapOf<String, String>()
                    var headerCount = 0
                    while (true) {
                        val next = line(input) ?: error("Truncated fixture request")
                        if (next.isEmpty()) break
                        check(++headerCount <= 64) { "Too many request headers" }
                        val index = next.indexOf(':')
                        check(index > 0)
                        headers[next.substring(0, index).lowercase()] = next.substring(index + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..16_384)
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                    val uri = Uri.parse(first[1])
                    val request = Request(first[0], uri.path.orEmpty(), uri.queryParameterNames.associateWith { uri.getQueryParameter(it) },
                        headers, if (length == 0) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8)))
                    records += request
                    when (request.path) {
                        "/spoiler/client/unlock" -> {
                            check(request.method == "POST")
                            check(request.body.optString("pin") == FIXTURE_PIN)
                            check(request.headers["x-device-id"] == DEVICE_ID)
                            val attempt = unlocks.incrementAndGet()
                            check(attempt <= 2) { "PIN must not be automatically retried" }
                            if (attempt == 2 && denyReauthentication) json(connection, 403, JSONObject().put("detail", "합성 PIN 재인증 거부"))
                            else json(connection, 200, JSONObject().put("token", if (attempt == 1) GRANT_1 else GRANT_2).put("expires_in", 15))
                        }
                        "/playback/decision" -> json(connection, 200, JSONObject().put("decision", "direct_play")
                            .put("library_view_id", VIEW_ID).put("view_item_id", VIEW_ITEM_ID).put("playback_context_token", CONTEXT_2)
                            .put("selected", JSONObject().put("part_id", PART_ID).put("metadata_item_id", ITEM_ID).put("duration_ms", 6_000)))
                        "/stream-ticket/$PART_ID" -> json(connection, 200, JSONObject().put("ticket", TICKET_2)
                            .put("library_view_id", VIEW_ID).put("view_item_id", VIEW_ITEM_ID).put("part_id", PART_ID))
                        "/subtitles/$PART_ID" -> json(connection, 200, JSONObject().put("subtitles", org.json.JSONArray()))
                        "/playback/progress" -> json(connection, 200, if (request.method == "GET") JSONObject().put("items", org.json.JSONArray()) else JSONObject().put("ok", true))
                        "/libraries/$VIEW_ID/media_info/$PART_ID" -> json(connection, 200, JSONObject())
                        "/stream/$PART_ID" -> { streaming = true; video(connection, request) }
                        else -> error("Unexpected synthetic endpoint: ${request.method} ${request.path}")
                    }
                } catch (error: SocketException) {
                    // Native seek/release may cancel an already authorized range response.
                    if (!streaming && !server.isClosed) failure.compareAndSet(null, error)
                } catch (error: Throwable) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        private fun line(input: BufferedInputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (buffer.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (buffer.size() == 0) null else error("Truncated HTTP line")
                if (value == 10) return buffer.toString("US-ASCII").trimEnd('\r')
                buffer.write(value)
            }
            error("Oversized HTTP line")
        }

        private fun json(socket: Socket, status: Int, value: JSONObject) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes); output.flush()
        }

        private fun video(socket: Socket, request: Request) {
            val ticket = request.query["ticket"]
            check(ticket == TICKET_1 || ticket == TICKET_2)
            check(request.headers["x-shyni-spoiler"] == if (ticket == TICKET_1) GRANT_1 else GRANT_2)
            val range = request.headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            if (start >= clip.size) {
                socket.getOutputStream().apply {
                    write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                return
            }
            check(start in clip.indices && end >= start)
            val count = end - start + 1
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $count\r\n${contentRange}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            if (request.method != "HEAD") output.write(clip, start, count)
            output.flush()
        }

        override fun close() {
            server.close(); listener.join(1_000)
            workers.shutdown(); workers.awaitTermination(2, TimeUnit.SECONDS); workers.shutdownNow()
        }
    }

    private companion object {
        const val ITEM_ID = 11
        const val VIEW_ID = 5
        const val VIEW_ITEM_ID = 17
        const val PART_ID = 77
        const val DEVICE_ID = "synthetic-spoiler-playback-qa"
        const val SESSION = "synthetic-spoiler-session"
        const val FIXTURE_PIN = "642801"
        const val GRANT_1 = "spc_fixture1"
        const val GRANT_2 = "spc_fixture2"
        const val TICKET_1 = "fixture-ticket1"
        const val TICKET_2 = "fixture-ticket2"
        const val CONTEXT_1 = "fixture-context1"
        const val CONTEXT_2 = "fixture-context2"
    }
}
