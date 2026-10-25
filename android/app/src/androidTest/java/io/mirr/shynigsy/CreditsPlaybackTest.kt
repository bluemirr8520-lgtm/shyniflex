package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.Media3GestureView
import io.mirr.shynigsy.player.NativePlayerActivity
import org.json.JSONArray
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

/** All preferences, sessions, media and HTTP belong to the synthetic QA package. */
@RunWith(AndroidJUnit4::class)
class CreditsPlaybackTest {
    @Test fun tvIntroAndCreditsPreferTheSkipActionWithControlsHidden(): Unit =
        withTvFixture { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 1_000)
            waitFor(scenario, "The intro action must receive remote focus without opening ordinary controls") {
                it.findViewById<Button>(R.id.player_segment_skip).let { skip ->
                    skip.isShown && skip.hasFocus() && skip.text.toString() == "인트로 건너뛰기"
                } && !video(it).isControllerFullyVisible
            }
            scenario.onActivity { assertFalse(it.findViewById<View>(R.id.player_credits_next).isShown) }

            seekPaused(scenario, 2_000)
            waitFor(scenario, "Leaving the intro must remove its focused action") {
                !it.findViewById<View>(R.id.player_segment_skip).isShown
            }
            seekPaused(scenario, 3_000)
            waitFor(scenario, "Credits must focus skip rather than the available next episode") {
                it.findViewById<Button>(R.id.player_segment_skip).let { skip ->
                    skip.isShown && skip.hasFocus() && skip.text.toString() == "크레딧 건너뛰기"
                } && it.findViewById<View>(R.id.player_credits_next).isShown &&
                    !it.findViewById<View>(R.id.player_credits_next).hasFocus()
            }
            scenario.onActivity { assertFalse(video(it).isControllerFullyVisible) }
            assertEquals("Focusing an action must not start another episode", 0, fixture.count("/playback/decision"))
            fixture.assertHealthy()
        }

    @Test fun tvNextFocusSurvivesTicksAndReturnsToSkipWhenNextDisappears(): Unit =
        withTvFixture { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 3_000)
            waitFor(scenario, "The credits skip action must have initial remote focus") {
                it.findViewById<View>(R.id.player_segment_skip).hasFocus()
            }
            remoteKey(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor(scenario, "Right must reach the verified next episode action") {
                it.findViewById<View>(R.id.player_credits_next).hasFocus()
            }
            scenario.onActivity { activity ->
                repeat(3) { updateSegmentActions(activity) }
                assertTrue("Ordinary action refreshes must retain the user's next-episode focus",
                    activity.findViewById<View>(R.id.player_credits_next).hasFocus())
                setField(activity, "playbackNeighbors", neighbors(activity).copy(nextEpisode = null))
                updateSegmentActions(activity)
            }
            waitFor(scenario, "A disappearing next action must return focus to visible skip") {
                !it.findViewById<View>(R.id.player_credits_next).isShown &&
                    it.findViewById<View>(R.id.player_segment_skip).hasFocus()
            }
            assertEquals(0, fixture.count("/playback/decision"))
            fixture.assertHealthy()
        }

    @Test fun tvConfirmHeldBeforeIntroCannotActivateTheNewlyFocusedAction(): Unit =
        withTvFixture { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 500)
            waitFor(scenario, "The confirm press must begin before any skip action appears") {
                !it.findViewById<View>(R.id.player_segment_skip).isShown
            }
            val heldAt = SystemClock.uptimeMillis()
            remoteKeyEvent(scenario, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, heldAt)
            seekPaused(scenario, 1_000)
            waitFor(scenario, "The intro action must be visible while the earlier confirm key is held") {
                it.findViewById<View>(R.id.player_segment_skip).isShown
            }
            scenario.onActivity {
                assertFalse("Automatic focus must wait until an existing confirm press is released",
                    it.findViewById<View>(R.id.player_segment_skip).hasFocus())
            }
            remoteKeyEvent(scenario, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, heldAt)
            waitFor(scenario, "Releasing the old confirm press makes skip reachable without clicking it") {
                it.findViewById<View>(R.id.player_segment_skip).hasFocus()
            }
            scenario.onActivity {
                assertTrue("The old key release must not seek to the intro end", abs(player(it).currentPosition - 1_000) < 125)
                assertFalse(player(it).playWhenReady)
            }
            remoteKey(scenario, KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(scenario, "A fresh confirm press must skip to the exact intro end") {
                player(it).playbackState == Player.STATE_READY && abs(player(it).currentPosition - INTRO_END) < 125 &&
                    !player(it).playWhenReady && !it.findViewById<View>(R.id.player_segment_skip).isShown
            }
            scenario.onActivity { assertEquals(PART, spec(it).partId) }
            assertEquals(0, fixture.count("/playback/decision"))
            fixture.assertHealthy()
        }

    @Test fun tvSkipFocusCanLeaveReenterAndRecoverAfterTheMarkerEnds(): Unit =
        withTvFixture { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 3_000)
            waitFor(scenario, "Credits must initially focus skip") {
                it.findViewById<View>(R.id.player_segment_skip).hasFocus()
            }
            for (escape in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) {
                remoteKey(scenario, escape)
                waitFor(scenario, "The skip action must release focus to ordinary playback controls") {
                    video(it).isControllerFullyVisible && playPause(it).hasFocus()
                }
                remoteKey(scenario, KeyEvent.KEYCODE_DPAD_UP)
                waitFor(scenario, "Up from play/pause must re-enter visible skip") {
                    it.findViewById<View>(R.id.player_segment_skip).hasFocus()
                }
            }
            remoteKey(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor(scenario, "Right from skip must focus next") {
                it.findViewById<View>(R.id.player_credits_next).hasFocus()
            }
            remoteKey(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor(scenario, "Right from the final action must escape to ordinary controls") { playPause(it).hasFocus() }
            remoteKey(scenario, KeyEvent.KEYCODE_DPAD_UP)
            waitFor(scenario, "The remote must be able to re-enter skip again") {
                it.findViewById<View>(R.id.player_segment_skip).hasFocus()
            }
            seekPaused(scenario, 4_900)
            waitFor(scenario, "Ending the marker must not strand focus on a hidden action") {
                !it.findViewById<View>(R.id.player_segment_skip).isShown &&
                    !it.findViewById<View>(R.id.player_credits_next).isShown &&
                    !it.findViewById<View>(R.id.player_segment_skip).hasFocus() &&
                    !it.findViewById<View>(R.id.player_credits_next).hasFocus() && video(it).hasFocus()
            }
            scenario.onActivity { video(it).hideController() }
            remoteKey(scenario, KeyEvent.KEYCODE_DPAD_UP)
            waitFor(scenario, "Ordinary remote controls remain usable after the marker") { playPause(it).hasFocus() }
            assertEquals(0, fixture.count("/playback/decision"))
            fixture.assertHealthy()
        }

    @Test fun creditsActionsFollowSourceClockSeekBackAndLockWithControlsHidden() =
        withFixture { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 2_150)
            waitFor(scenario, "Actions must be absent before the 2250 ms credit boundary") {
                !it.findViewById<View>(R.id.player_segment_skip).isShown &&
                    !it.findViewById<View>(R.id.player_credits_next).isShown
            }
            scenario.onActivity { player(it).play() }
            waitFor(scenario, "Playing across the source boundary reveals both actions without a seek") {
                player(it).currentPosition >= CREDITS_START &&
                it.findViewById<View>(R.id.player_segment_skip).isShown &&
                    it.findViewById<View>(R.id.player_credits_next).isShown
            }
            scenario.onActivity { activity ->
                player(activity).pause()
                assertFalse("Credits actions must not require the ordinary controls", video(activity).isControllerFullyVisible)
                assertEquals(ALTERNATE_PART, neighbors(activity).next?.navigationPartId)
                assertEquals(NEXT_PART, neighbors(activity).nextEpisode?.navigationPartId)
            }
            seekPaused(scenario, 2_150)
            waitFor(scenario, "Seeking back out of credits removes both actions") {
                !it.findViewById<View>(R.id.player_segment_skip).isShown &&
                    !it.findViewById<View>(R.id.player_credits_next).isShown
            }
            seekPaused(scenario, 2_450)
            waitFor(scenario, "The current credit interval can be re-entered") {
                it.findViewById<View>(R.id.player_credits_next).isShown
            }
            scenario.onActivity { video(it).showController() }
            waitFor(scenario, "The real touch lock control must be available") {
                it.findViewById<View>(R.id.player_lock).isShown
            }
            tap(scenario, R.id.player_lock)
            waitFor(scenario, "A locked screen must hide both credit actions") {
                field<Media3GestureView>(it, "gestures").locked &&
                    !it.findViewById<View>(R.id.player_segment_skip).isShown &&
                    !it.findViewById<View>(R.id.player_credits_next).isShown
            }
            // Unlock through the same production button, then return to hidden controls.
            scenario.onActivity { it.findViewById<Button>(R.id.player_lock).performClick(); video(it).hideController() }
            seekPaused(scenario, 4_900)
            waitFor(scenario, "The exclusive 4750 ms credit end removes both actions") {
                !it.findViewById<View>(R.id.player_segment_skip).isShown &&
                    !it.findViewById<View>(R.id.player_credits_next).isShown
            }
            assertEquals("Visibility changes must not start another episode", 0, fixture.count("/playback/decision"))
            fixture.assertHealthy()
        }

    @Test fun sameEpisodeAlternateFileDoesNotBecomeNextEpisodeAndSkipKeepsExactEnd() =
        withFixture(includeNextEpisode = false) { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 3_000)
            waitFor(scenario, "Credits are skippable even without a next episode") {
                it.findViewById<View>(R.id.player_segment_skip).isShown
            }
            scenario.onActivity { activity ->
                assertEquals("The fixture must contain a real generic next file", ALTERNATE_PART,
                    neighbors(activity).next?.navigationPartId)
                assertNull("A different encoding of the same episode is not a next episode", neighbors(activity).nextEpisode)
                assertFalse(activity.findViewById<View>(R.id.player_credits_next).isShown)
                assertFalse(video(activity).isControllerFullyVisible)
            }
            tap(scenario, R.id.player_segment_skip)
            waitFor(scenario, "Credit skip must use 4750 ms, not a rounded second or media duration") { activity ->
                val player = player(activity)
                player.playbackState == Player.STATE_READY && abs(player.currentPosition - CREDITS_END) < 125 &&
                    !player.playWhenReady && !activity.findViewById<View>(R.id.player_segment_skip).isShown
            }
            scenario.onActivity {
                assertFalse("A paused credit skip must keep the playback window", it.isFinishing)
                assertEquals(PART, spec(it).partId)
            }
            assertEquals(0, fixture.count("/playback/decision"))
            fixture.assertHealthy()
        }

    @Test fun nextEpisodeActionUsesVerifiedEpisodeInsteadOfGenericPartAndStartsPlayingAtZero() =
        withFixture { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 3_000)
            waitFor(scenario, "Verified next episode action appears with ordinary controls hidden") {
                it.findViewById<View>(R.id.player_credits_next).isShown
            }
            scenario.onActivity {
                assertFalse(player(it).playWhenReady)
                assertFalse(video(it).isControllerFullyVisible)
                assertEquals(ALTERNATE_PART, neighbors(it).next?.navigationPartId)
                assertEquals(NEXT_PART, neighbors(it).nextEpisode?.navigationPartId)
            }
            tap(scenario, R.id.player_credits_next)
            waitFor(scenario, "The production switch path must prepare and play the verified next episode", 12_000) {
                spec(it).partId == NEXT_PART && player(it).playbackState == Player.STATE_READY &&
                    player(it).playWhenReady && player(it).isPlaying
            }
            scenario.onActivity {
                assertEquals(NEXT_ITEM, spec(it).itemId)
                assertEquals(0L, spec(it).resumeMs)
                assertTrue("The new source must begin near zero", player(it).currentPosition < 2_000)
                assertFalse(it.isFinishing)
            }
            val decision = fixture.requests().single { it.path == "/playback/decision" }
            assertEquals(NEXT_PART, decision.body.getInt("part_id"))
            assertEquals(VIEW, decision.body.getInt("library_view_id"))
            assertEquals(VIEW_ITEM, decision.body.getInt("view_item_id"))
            assertTrue("The next stream must be requested with its fresh ticket", fixture.requests().any {
                it.path == "/stream/$NEXT_PART" && it.uri.getQueryParameter("ticket") == NEXT_TICKET
            })
            assertFalse("The generic alternate file must never be loaded", fixture.requests().any { it.path == "/stream/$ALTERNATE_PART" })
            fixture.assertHealthy()
        }

    @Test fun deniedNextEpisodeRestoresTheOriginalPausedPlayback() =
        withFixture(denyNextDecision = true) { fixture, scenario ->
            preparePaused(scenario)
            seekPaused(scenario, 3_000)
            var original: Player? = null
            scenario.onActivity { original = player(it) }
            waitFor(scenario, "The credit next action must be present before a denied attempt") {
                it.findViewById<View>(R.id.player_credits_next).isShown
            }
            tap(scenario, R.id.player_credits_next)
            waitUntil("A real playback decision must reach the denying fixture") { fixture.count("/playback/decision") == 1 }
            waitFor(scenario, "A failed explicit next action must preserve the old pause and source") {
                !field<Boolean>(it, "navigationLoading") && !field<Boolean>(it, "refreshing") &&
                    player(it) === original && spec(it).partId == PART && !player(it).playWhenReady
            }
            scenario.onActivity {
                assertTrue(abs(player(it).currentPosition - 3_000) < 200)
                assertFalse(it.isFinishing)
            }
            assertFalse(fixture.requests().any { it.path == "/stream/$NEXT_PART" })
            fixture.assertHealthy()
        }

    private fun withFixture(includeNextEpisode: Boolean = true, denyNextDecision: Boolean = false,
        test: (Fixture, ActivityScenario<NativePlayerActivity>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // This must precede EVERY preference, session, asset or socket access.
        assumeTrue("Only the isolated synthetic QA package may run credits tests", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before testing native playback", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val oldDevice = settings.getString("device_id", null)
        val oldSpeed = settings.getFloat("playback_speed", 1f)
        val store = SessionStore(context)
        val clip = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        Fixture(clip, includeNextEpisode, denyNextDecision).use { fixture ->
            try {
                settings.edit().putString("device_id", DEVICE).putFloat("playback_speed", 1f).commit()
                store.save(fixture.origin, SESSION, "합성 크레딧 검사")
                val item = MediaItem(ITEM, VIEW, "합성 현재 회차", "episode", durationMs = 6_000,
                    seasonNumber = 1, episodeNumber = 1, viewItemId = VIEW_ITEM, showId = SHOW,
                    showTitle = "합성 시리즈", playbackPartId = PART)
                val initial = PlaybackSpec(item.title, "${fixture.origin}/stream/$PART?ticket=$TICKET",
                    itemId = ITEM, libraryId = VIEW, partId = PART, viewItemId = VIEW_ITEM,
                    resumeMs = 500, durationMs = 6_000, sessionId = "synthetic-credits-session",
                    progressToken = CONTEXT, sourceItem = item)
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", initial.toJson())
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario -> test(fixture, scenario) }
            } finally {
                store.clear(fixture.origin)
                settings.edit().apply {
                    if (oldDevice == null) remove("device_id") else putString("device_id", oldDevice)
                    putFloat("playback_speed", oldSpeed)
                }.commit()
            }
        }
    }

    private fun withTvFixture(test: (Fixture, ActivityScenario<NativePlayerActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Guard before delegating to any preferences, sessions, assets or sockets.
        assumeTrue("Only the isolated synthetic QA package may run TV focus tests", context.packageName.endsWith(".spoilerqa"))
        assumeTrue("Remote window-focus tests require an Android TV device", context.isTelevision())
        withFixture(test = test)
    }

    private fun remoteKey(scenario: ActivityScenario<NativePlayerActivity>, keyCode: Int) {
        val downAt = SystemClock.uptimeMillis()
        remoteKeyEvent(scenario, keyCode, KeyEvent.ACTION_DOWN, downAt)
        remoteKeyEvent(scenario, keyCode, KeyEvent.ACTION_UP, downAt)
    }

    private fun remoteKeyEvent(scenario: ActivityScenario<NativePlayerActivity>, keyCode: Int, action: Int, downAt: Long) {
        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(downAt, SystemClock.uptimeMillis(), action, keyCode, 0)) }
    }

    private fun playPause(activity: NativePlayerActivity): View =
        activity.findViewById(androidx.media3.ui.R.id.exo_play_pause)

    private fun updateSegmentActions(activity: NativePlayerActivity) {
        NativePlayerActivity::class.java.getDeclaredMethod("updateSegmentSkipButton", java.lang.Long::class.java)
            .apply { isAccessible = true }.invoke(activity, null)
    }

    private fun setField(activity: NativePlayerActivity, name: String, value: Any?) {
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun preparePaused(scenario: ActivityScenario<NativePlayerActivity>) {
        waitFor(scenario, "The real local stream and optional episode metadata must prepare", 15_000) { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val active = player(activity)
            active.volume = 0f
            active.pause()
            active.playbackState == Player.STATE_READY && field<Boolean>(activity, "prepared") &&
                spec(activity).markers.any { it.type == "credits" && it.startMs == CREDITS_START && it.endMs == CREDITS_END } &&
                field<PlaybackNeighbors?>(activity, "playbackNeighbors")?.next?.navigationPartId == ALTERNATE_PART
        }
        scenario.onActivity { video(it).hideController() }
    }

    private fun seekPaused(scenario: ActivityScenario<NativePlayerActivity>, position: Long) {
        scenario.onActivity { player(it).pause(); player(it).seekTo(position); video(it).hideController() }
        waitFor(scenario, "Source-clock seek must settle before checking credit actions") {
            player(it).playbackState == Player.STATE_READY && !player(it).playWhenReady && abs(player(it).currentPosition - position) < 100
        }
    }

    private fun tap(scenario: ActivityScenario<NativePlayerActivity>, id: Int) {
        var target: Triple<View, Float, Float>? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val button = activity.findViewById<View>(id)
            assertTrue("The action must be visible and enabled before a real touch", button.isShown && button.isEnabled)
            val childLocation = IntArray(2).also(button::getLocationOnScreen)
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            target = Triple(root, childLocation[0] - rootLocation[0] + button.width / 2f,
                childLocation[1] - rootLocation[1] + button.height / 2f)
        }
        val (root, x, y) = requireNotNull(target)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).also {
                    try { root.dispatchTouchEvent(it) } finally { it.recycle() }
                }
            }
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(25)
        }
    }

    private fun waitFor(scenario: ActivityScenario<NativePlayerActivity>, message: String, timeoutMs: Long = 5_000,
        predicate: (NativePlayerActivity) -> Boolean) = waitUntil(message, timeoutMs) {
        assertNotEquals("The player must remain open during this assertion", Lifecycle.State.DESTROYED, scenario.state)
        var ready = false
        scenario.onActivity { ready = predicate(it) }
        ready
    }

    private fun waitUntil(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            Thread.sleep(40)
        }
        assertTrue(message, predicate())
    }

    private fun player(activity: NativePlayerActivity): Player = field(activity, "player")
    private fun spec(activity: NativePlayerActivity): PlaybackSpec = field(activity, "spec")
    private fun neighbors(activity: NativePlayerActivity): PlaybackNeighbors = field(activity, "playbackNeighbors")
    private fun video(activity: NativePlayerActivity): PlayerView = field(activity, "playerView")

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private data class Request(val method: String, val path: String, val uri: Uri, val body: JSONObject)

    /** Exact synthetic API, no proxy, redirection, DNS or external destination. */
    private class Fixture(private val clip: ByteArray, private val includeNext: Boolean,
        private val denyNext: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newFixedThreadPool(6) { task -> Thread(task, "credits-qa-http").apply { isDaemon = true } }
        private val records = Collections.synchronizedList(mutableListOf<Request>())
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val failure = AtomicReference<Throwable?>()
        val origin get() = "http://127.0.0.1:${server.localPort}"
        private val listener = thread(name = "credits-qa-loopback", isDaemon = true) {
            while (!server.isClosed) {
                try { val socket = server.accept(); sockets += socket; workers.execute { handle(socket) } }
                catch (_: SocketTimeoutException) { }
                catch (error: SocketException) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        fun requests(): List<Request> = synchronized(records) { records.toList() }
        fun count(path: String) = requests().count { it.path == path }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic credits fixture failed", it) } }

        private fun handle(socket: Socket) {
            var streaming = false
            try {
                socket.use { connection ->
                    connection.soTimeout = 3_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val first = line(input)?.split(' ') ?: return
                    check(first.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val row = line(input) ?: error("Truncated synthetic header")
                        if (row.isEmpty()) break
                        check(headers.size < 64)
                        val split = row.indexOf(':'); check(split > 0)
                        headers[row.substring(0, split).lowercase()] = row.substring(split + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..16_384)
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) { val count = input.read(bytes, read, length - read); check(count > 0); read += count }
                    val uri = Uri.parse(first[1])
                    val request = Request(first[0], uri.path.orEmpty(), uri,
                        if (length == 0) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8)))
                    records += request
                    if (request.path in setOf("/stream/$PART", "/stream/$NEXT_PART")) {
                        streaming = true
                        check(uri.getQueryParameter("ticket") == if (request.path == "/stream/$PART") TICKET else NEXT_TICKET)
                        video(connection, request.method, headers["range"])
                        return
                    }
                    check(headers["authorization"] == "Bearer $SESSION")
                    check(headers["x-device-id"] == DEVICE)
                    when (request.path) {
                        "/libraries/$VIEW/media_info/$PART", "/libraries/$VIEW/media_info/item/$ITEM" ->
                            json(connection, info(ITEM, PART, true).put("markers", JSONArray().put(JSONObject()
                                .put("segment_type", "intro").put("start_ms", INTRO_START).put("end_ms", INTRO_END)).put(JSONObject()
                                .put("segment_type", "credits").put("start_ms", CREDITS_START).put("end_ms", CREDITS_END))))
                        "/libraries/$VIEW/media_info/$NEXT_PART", "/libraries/$VIEW/media_info/item/$NEXT_ITEM" ->
                            json(connection, info(NEXT_ITEM, NEXT_PART, false))
                        "/libraries/$VIEW/shows/$SHOW/episodes" -> json(connection, JSONArray().put(episode(ITEM, 1)).apply {
                            if (includeNext) put(episode(NEXT_ITEM, 2))
                        })
                        "/playback/decision" -> {
                            check(includeNext)
                            check(request.body.getInt("part_id") == NEXT_PART)
                            check(request.body.getInt("library_view_id") == VIEW)
                            check(request.body.getInt("view_item_id") == VIEW_ITEM)
                            if (denyNext) json(connection, JSONObject().put("detail", "합성 다음 회차 권한 거부"), 403)
                            else json(connection, JSONObject().put("decision", "direct_play")
                                .put("library_view_id", VIEW).put("view_item_id", VIEW_ITEM).put("playback_context_token", NEXT_CONTEXT)
                                .put("selected", JSONObject().put("part_id", NEXT_PART).put("metadata_item_id", NEXT_ITEM).put("duration_ms", 6_000)))
                        }
                        "/stream-ticket/$NEXT_PART" -> {
                            check(uri.getQueryParameter("playback_context_token") == NEXT_CONTEXT)
                            json(connection, JSONObject().put("ticket", NEXT_TICKET).put("library_view_id", VIEW)
                                .put("view_item_id", VIEW_ITEM).put("part_id", NEXT_PART))
                        }
                        "/subtitles/$NEXT_PART" -> json(connection, JSONObject().put("subtitles", JSONArray()))
                        "/playback/progress" -> json(connection, if (request.method == "GET")
                            JSONObject().put("items", JSONArray()) else JSONObject().put("ok", true))
                        else -> error("Unexpected synthetic endpoint")
                    }
                }
            } catch (error: SocketException) {
                if (!streaming && !server.isClosed) failure.compareAndSet(null, error)
            } catch (error: Throwable) {
                if (!server.isClosed) failure.compareAndSet(null, error)
            } finally { sockets -= socket }
        }

        private fun episode(item: Int, number: Int) = JSONObject().put("id", item).put("view_item_id", VIEW_ITEM)
            .put("title", "합성 회차 $number").put("season_number", 1).put("episode_number", number).put("show_id", SHOW)

        private fun info(item: Int, part: Int, alternate: Boolean): JSONObject = JSONObject()
            .put("item", JSONObject().put("id", item))
            .put("parts", JSONArray().put(physical(item, part, 0)).apply {
                if (alternate) put(physical(item, ALTERNATE_PART, 1))
            })

        private fun physical(item: Int, part: Int, order: Int) = JSONObject().put("id", part)
            .put("metadata_item_id", item).put("part_index", order).put("file_path", "/synthetic/credits-series/$part.mp4")

        private fun line(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else error("Truncated synthetic request")
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
            error("Oversized synthetic request")
        }

        private fun json(socket: Socket, value: Any, status: Int = 200) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }

        private fun video(socket: Socket, method: String, rangeHeader: String?) {
            val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            socket.getOutputStream().apply {
                if (start >= clip.size) {
                    write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                } else {
                    check(start >= 0 && end >= start)
                    val partial = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
                    write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${partial}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") write(clip, start, end - start + 1)
                }
                flush()
            }
        }

        override fun close() {
            server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            listener.join(1_000)
            workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ITEM = 11
        const val NEXT_ITEM = 12
        const val VIEW = 5
        const val VIEW_ITEM = 17
        const val SHOW = 21
        const val PART = 77
        const val NEXT_PART = 78
        const val ALTERNATE_PART = 79
        const val CREDITS_START = 2_250L
        const val CREDITS_END = 4_750L
        const val INTRO_START = 750L
        const val INTRO_END = 1_750L
        const val DEVICE = "synthetic-credits-device"
        const val SESSION = "synthetic-credits-account-session"
        const val TICKET = "synthetic-credits-original-ticket"
        const val NEXT_TICKET = "synthetic-credits-next-ticket"
        const val CONTEXT = "synthetic-credits-original-context"
        const val NEXT_CONTEXT = "synthetic-credits-next-context"
    }
}
