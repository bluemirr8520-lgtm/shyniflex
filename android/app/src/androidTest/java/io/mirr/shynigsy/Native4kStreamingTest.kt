package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.mpv.MpvPlayer
import `is`.xyz.mpv.MPVLib
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Synthetic loopback/decoder benchmarks: no MainActivity, account, PIN or SessionStore. */
@RunWith(AndroidJUnit4::class)
class Native4kStreamingTest {
    @Test fun fourKHevcMain10RendersItsFirstFrameOverHttp() = benchmark(stall = false)

    @Test fun fourKHevcRecoversAfterAThreeSecondNetworkStall() = benchmark(stall = true)

    @Test fun slowServerKeepsControlsResponsiveAndRecoversAfterAReadGap() = slowServerCase(cancelWhileWaiting = false)

    @Test fun unresponsiveServerCanBeStoppedAndReleasedWithoutBlockingPlaybackControls() = slowServerCase(cancelWhileWaiting = true)

    @Test fun nativeStringTrackSnapshotsPreserveAudioVideoAndGlobalStreamIdentity() {
        val context = instrumentation.targetContext
        requireSyntheticEnvironment(context)
        val file = File(context.cacheDir, "track-observation-fixture.mp4")
        instrumentation.context.assets.open("media3/gesture-av.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val observation = TrackObservation()
        MPVLib.addObserver(observation)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                var player: MpvPlayer? = null
                var view: PlayerView? = null
                try {
                    scenario.onActivity { activity ->
                        val video = PlayerView(activity).apply { useController = false }
                        activity.setContentView(video)
                        val active = MpvPlayer(activity).apply {
                            volume = 0f; setAudioAttributes(AudioAttributes.DEFAULT, false)
                        }
                        player = active; view = video; video.player = active
                        active.setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); active.prepare()
                    }
                    val active = player!!
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var received = false
                    while (SystemClock.elapsedRealtime() < deadline && !received) {
                        received = observation.sawAudioAndVideo.get() && main {
                            active.playbackState == Player.STATE_READY &&
                                active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } &&
                                active.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                        }
                        if (!received) Thread.sleep(50)
                    }
                    assertTrue("The bundled JNI must deliver a real track-list STRING snapshot with audio and video", received)
                    assertTrue(observation.selectedAudioStreamIndex.get() >= 0)
                    assertEquals(observation.selectedAudioStreamIndex.get(), main { active.selectedAudioStreamIndex() })
                    assertNull(main { active.playerError })
                } finally { main { view?.player = null; player?.release() } }
            }
        } finally { MPVLib.removeObserver(observation); file.delete() }
    }

    private fun slowServerCase(cancelWhileWaiting: Boolean) {
        val context = instrumentation.targetContext
        requireSyntheticEnvironment(context)
        val clip = instrumentation.context.assets.open("media3/$FIXTURE").use { it.readBytes() }
        val timings = Timings()
        StreamingFixture(clip, hold = !cancelWhileWaiting, holdHeaders = true).use { server ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                var player: MpvPlayer? = null
                var view: PlayerView? = null
                var button: Button? = null
                val clicks = AtomicInteger()
                val heartbeat = MainHeartbeat()
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val root = FrameLayout(activity)
                        val video = PlayerView(activity).apply { useController = false }
                        root.addView(video, FrameLayout.LayoutParams(-1, -1))
                        val active = MpvPlayer(activity).apply {
                            volume = 0f; setAudioAttributes(AudioAttributes.DEFAULT, false)
                            addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() {
                                    timings.firstFrameAt.compareAndSet(0, SystemClock.elapsedRealtime())
                                    server.firstFrame.countDown()
                                }
                                override fun onPlayerError(error: PlaybackException) { timings.error.set(error.errorCodeName) }
                            })
                        }
                        val control = Button(activity).apply {
                            text = "재생 / 일시정지"
                            setOnClickListener {
                                val count = clicks.incrementAndGet()
                                active.playWhenReady = !active.playWhenReady
                                active.setPlaybackSpeed(if (count % 2 == 0) 1f else 1.25f)
                            }
                        }
                        root.addView(control, FrameLayout.LayoutParams(-2, 48))
                        activity.setContentView(root)
                        player = active; view = video; button = control; video.player = active
                        active.setMediaItem(MediaItem.fromUri(server.url)); active.prepare(); active.play()
                        heartbeat.start()
                    }
                    val active = player!!
                    assertTrue("The fixture must withhold real HTTP response headers", server.headersBlocked.await(5, TimeUnit.SECONDS))
                    repeat(3) {
                        val began = SystemClock.elapsedRealtime()
                        main { assertTrue(button!!.performClick()) }
                        assertTrue("Playback controls must not wait for delayed server headers", SystemClock.elapsedRealtime() - began < 750)
                        Thread.sleep(100)
                    }
                    assertEquals(3, clicks.get())
                    main {
                        assertFalse(active.playWhenReady)
                        assertEquals(1.25f, active.playbackParameters.speed, 0f)
                        assertEquals(Player.STATE_BUFFERING, active.playbackState)
                        assertFalse(active.isPlaying)
                    }
                    if (cancelWhileWaiting) {
                        // network-timeout is not a whole-prepare deadline: the pinned
                        // curl backend has its own connect timeout, not a header deadline.
                        // Cancellation must work without receiving a response first.
                        Thread.sleep(1_000)
                        assertTrue(server.headersAreWithheld)
                        assertEquals(0L, server.payloadBytes.get())
                        val stopAt = SystemClock.elapsedRealtime()
                        main {
                            active.stop()
                            assertEquals(Player.STATE_IDLE, active.playbackState)
                            assertFalse(active.isPlaying)
                            assertFalse(active.isLoading)
                        }
                        assertTrue("Stop must not wait for an unanswered server", SystemClock.elapsedRealtime() - stopAt < 750)
                        assertTrue("Stopping must cancel the actual HTTP request while its headers remain withheld",
                            server.cancelledBeforeHeaders.await(1, TimeUnit.SECONDS))
                        val releaseAt = SystemClock.elapsedRealtime()
                        main { view?.player = null; active.release(); player = null }
                        assertTrue("Release must not wait for an unanswered server", SystemClock.elapsedRealtime() - releaseAt < 750)
                        assertTrue("Cancellation must finish before the fixture releases any response", server.headersAreWithheld)
                        assertEquals(0L, server.payloadBytes.get())
                        assertEquals(0L, timings.firstFrameAt.get())
                    } else {
                        // The response remains unavailable well beyond several UI frames.
                        Thread.sleep(1_000)
                        main { active.setPlaybackSpeed(1f); active.play() }
                        server.releaseHeaders()
                        await("Playback must start after a delayed response", 15_000, server, timings) { timings.firstFrameAt.get() > 0 }
                        verifyRecovery(active, server, timings) {
                            val began = SystemClock.elapsedRealtime()
                            assertTrue(button!!.performClick()); assertTrue(button!!.performClick())
                            assertTrue("Pause/resume must stay responsive during a body stall", SystemClock.elapsedRealtime() - began < 750)
                        }
                        assertNull(main { active.playerError })
                    }
                    main { heartbeat.stop() }
                    assertTrue("A delayed server must not starve the main-thread heartbeat", heartbeat.maxDelayMs.get() < 750)
                    assertTrue("The heartbeat must actually run while waiting for the server", heartbeat.ticks.get() >= 5)
                    server.assertHealthy()
                } finally {
                    server.releaseHeaders()
                    main { heartbeat.stop(); view?.player = null; player?.release() }
                }
            }
        }
    }

    @Test fun fullSourceDurationSurvivesAShorterNativeTimelineUntilRealEof() {
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated synthetic QA app may run this regression", context.packageName.endsWith(".spoilerqa"))
        assertFalse((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val clip = instrumentation.context.assets.open("media3/$FIXTURE").use { it.readBytes() }
        StreamingFixture(clip, hold = false).use { server ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                var player: MpvPlayer? = null
                var view: PlayerView? = null
                val timings = Timings()
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val video = PlayerView(activity).apply { useController = false }
                        activity.setContentView(video)
                        val active = MpvPlayer(activity, sourceDurationMs = 120_000).apply {
                            volume = 0f
                            setAudioAttributes(AudioAttributes.DEFAULT, false)
                            addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) { timings.error.set(error.errorCodeName) }
                            })
                        }
                        player = active; view = video; video.player = active
                        active.setMediaItem(MediaItem.fromUri(server.url)); active.prepare()
                    }
                    val active = player!!
                    await("A paused source must prepare without replacing its full-source duration", 15_000, server, timings) {
                        main { active.playbackState == Player.STATE_READY && active.videoSize.width == 3840 }
                    }
                    main {
                        assertEquals(120_000L, active.duration)
                        assertTrue("The native fixture must really be shorter than the supplied full source", nativeNumber("duration")!! in 11.0..13.0)
                        assertFalse(active.playWhenReady)
                        assertFalse(active.isPlaying)
                        active.seekTo(6_000)
                    }
                    await("A paused midpoint seek must keep the source clock and full duration", 8_000, server, timings) {
                        main { active.playbackState == Player.STATE_READY && kotlin.math.abs(active.currentPosition - 6_000) < 500 }
                    }
                    main {
                        assertEquals(120_000L, active.duration)
                        assertFalse(active.isPlaying)
                        assertTrue("Half of a short native timeline is not 95 percent of the full source", active.currentPosition < active.duration * 95 / 100)
                        active.play()
                    }
                    await("Actual playback must advance after the midpoint seek", 5_000, server, timings) {
                        main { active.isPlaying && active.currentPosition >= 6_500 }
                    }
                    main {
                        assertEquals(120_000L, active.duration)
                        assertTrue(active.currentPosition < active.duration * 95 / 100)
                        active.pause(); active.seekTo(11_000)
                    }
                    await("A paused near-end seek must not be confused with source completion", 8_000, server, timings) {
                        main { active.playbackState == Player.STATE_READY && kotlin.math.abs(active.currentPosition - 11_000) < 500 }
                    }
                    main {
                        assertFalse(active.isPlaying)
                        assertEquals(120_000L, active.duration)
                        assertTrue(active.currentPosition < active.duration * 95 / 100)
                        active.play()
                    }
                    await("Real native EOF must end playback despite a longer full-source duration", 8_000, server, timings) {
                        main { active.playbackState == Player.STATE_ENDED }
                    }
                    main {
                        assertEquals(120_000L, active.duration)
                        assertEquals(true, MPVLib.getPropertyBoolean("eof-reached"))
                        assertNull(active.playerError)
                    }
                    server.assertHealthy()
                } finally { main { view?.player = null; player?.release() } }
            }
        }
    }

    @Test fun delayedResumeWaitsForNativeRestartBeforePlayingOrAdvancingItsClock() {
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated synthetic QA app may run this regression", context.packageName.endsWith(".spoilerqa"))
        assertFalse((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val clip = instrumentation.context.assets.open("media3/$FIXTURE").use { it.readBytes() }
        for (paused in listOf(false, true)) {
            StreamingFixture(clip, hold = false, startupHold = true).use { server ->
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    var player: MpvPlayer? = null
                    var view: PlayerView? = null
                    val timings = Timings()
                    val released = AtomicBoolean()
                    val prematurelyPlaying = AtomicBoolean()
                    try {
                        scenario.onActivity { activity ->
                            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            val video = PlayerView(activity).apply { useController = false }
                            activity.setContentView(video)
                            val active = MpvPlayer(activity).apply {
                                volume = 0f
                                setAudioAttributes(AudioAttributes.DEFAULT, false)
                                addListener(object : Player.Listener {
                                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                                        if (isPlaying && !released.get()) prematurelyPlaying.set(true)
                                    }
                                    override fun onRenderedFirstFrame() { timings.firstFrameAt.compareAndSet(0, SystemClock.elapsedRealtime()) }
                                    override fun onPlayerError(error: PlaybackException) { timings.error.set(error.errorCodeName) }
                                })
                            }
                            player = active; view = video; video.player = active
                            active.setMediaItem(MediaItem.fromUri(server.url), 8_000)
                            active.prepare()
                            if (!paused) active.play()
                        }
                        val active = player!!
                        await("Metadata must load while the requested resume frame is still withheld", 10_000, server, timings) {
                            server.startupBlocked.count == 0L && main {
                                active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                            }
                        }
                        val waitingPosition = main {
                            assertEquals(Player.STATE_BUFFERING, active.playbackState)
                            assertFalse("A pending native restart must not count as watched playback", active.isPlaying)
                            active.currentPosition
                        }
                        Thread.sleep(1_100)
                        main {
                            assertEquals(Player.STATE_BUFFERING, active.playbackState)
                            assertFalse(active.isPlaying)
                            assertTrue("The pending resume clock must not include loading time", kotlin.math.abs(active.currentPosition - waitingPosition) < 150)
                        }
                        assertFalse("No transient READY state may start the watch-time clock before native restart", prematurelyPlaying.get())
                        assertEquals(0L, timings.firstFrameAt.get())
                        released.set(true); server.releaseStartup()
                        await("Native restart must make both playing and paused preparation ready", 15_000, server, timings) {
                            timings.firstFrameAt.get() > 0 && main { active.playbackState == Player.STATE_READY }
                        }
                        if (paused) {
                            val readyPosition = main { assertFalse(active.isPlaying); assertFalse(active.playWhenReady); active.currentPosition }
                            Thread.sleep(400)
                            assertTrue(main { kotlin.math.abs(active.currentPosition - readyPosition) < 150 })
                        } else {
                            await("The source clock must advance after restart, without adding loading time", 3_000, server, timings) {
                                main { active.isPlaying && active.currentPosition >= waitingPosition + 250 && active.currentPosition < 10_500 }
                            }
                        }
                        server.assertHealthy()
                    } finally {
                        server.releaseStartup()
                        main { view?.player = null; player?.release() }
                    }
                }
            }
        }
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun benchmark(stall: Boolean) {
        val context = instrumentation.targetContext
        // This is deliberately the first operation before assets, sockets or Activity launch.
        assumeTrue("Only the isolated synthetic QA app may run this benchmark", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device to test a real video Surface", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue("The isolated QA app must permit loopback HTTP", NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val clip = instrumentation.context.assets.open("media3/$FIXTURE").use { it.readBytes() }
        assertEquals("Use the verified original synthetic 4K fixture", FIXTURE_SHA256, MessageDigest.getInstance("SHA-256").digest(clip).joinToString("") { "%02x".format(it.toInt() and 255) })
        val timings = Timings()
        StreamingFixture(clip, stall).use { server ->
            // ui-test-manifest already provides this empty host in old and new debug APKs.
            // It avoids starting the real login/navigation ViewModel entirely.
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                var player: MpvPlayer? = null
                var view: PlayerView? = null
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val root = FrameLayout(activity)
                        val video = PlayerView(activity).apply { useController = false }
                        root.addView(video, FrameLayout.LayoutParams(-1, -1))
                        activity.setContentView(root)
                        timings.startedAt = SystemClock.elapsedRealtime()
                        val active = MpvPlayer(activity, headers = emptyMap()).apply {
                            volume = 0f
                            setAudioAttributes(AudioAttributes.DEFAULT, false)
                            addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() {
                                    if (timings.firstFrameAt.compareAndSet(0, SystemClock.elapsedRealtime())) server.firstFrame.countDown()
                                }
                                override fun onPlaybackStateChanged(playbackState: Int) { timings.state(playbackState) }
                                override fun onPlayerError(error: PlaybackException) { timings.error.set(error.errorCodeName) }
                            })
                        }
                        player = active; view = video; video.player = active
                        timings.prepareAt = SystemClock.elapsedRealtime()
                        active.setMediaItem(MediaItem.fromUri(server.url))
                        active.prepare(); active.play()
                    }
                    val active = player!!
                    await("A real 4K first frame must render within a conservative 15 seconds", 15_000, server, timings) {
                        timings.firstFrameAt.get() > 0 && main { active.videoSize.width == 3840 && active.videoSize.height == 2160 }
                    }
                    assertTrue("First frame exceeded the conservative limit", timings.firstFrameAt.get() - timings.startedAt < 15_000)
                    main {
                        assertTrue("The selected native stream must be video", active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
                        timings.codec = active.currentTracks.groups.first { it.type == C.TRACK_TYPE_VIDEO }.getTrackFormat(0).codecs.orEmpty()
                        assertEquals("The actual encoded codec must reach libmpv", "hevc", timings.codec)
                        timings.hardwareDecoder = MPVLib.getPropertyString("hwdec-current").orEmpty()
                        assertTrue("Record the actual decoder, including a possible software fallback", timings.hardwareDecoder.isNotBlank())
                        assertTrue("The whole synthetic timeline must be available", active.duration in 11_000L..13_000L)
                    }
                    assertColoredFrame(main { view!!.videoSurfaceView as SurfaceView })
                    if (stall) verifyRecovery(active, server, timings)
                    else main { active.pause() }
                    server.assertHealthy()
                } finally {
                    timings.finishedAt = SystemClock.elapsedRealtime()
                    main {
                        player?.let { active ->
                            timings.finalPositionMs = active.currentPosition
                            timings.durationMs = active.duration.takeIf { it != C.TIME_UNSET } ?: 0
                            timings.frameDropCount = nativeNumber("frame-drop-count")
                            timings.decoderFrameDropCount = nativeNumber("decoder-frame-drop-count")
                            timings.demuxerCacheDurationSeconds = nativeNumber("demuxer-cache-duration")
                            view?.player = null
                            active.release()
                        }
                    }
                    saveResult(context, stall, timings, server, clip.size)
                }
            }
        }
    }

    private fun verifyRecovery(player: MpvPlayer, server: StreamingFixture, timings: Timings,
        onBuffering: (() -> Unit)? = null) {
        var previousPosition = main { player.currentPosition }
        var stalledPosition = -1L
        var sawBuffering = false
        await("A bounded network gap must resume playback without a new error or rewind", 18_000, server, timings) {
            main {
                val now = SystemClock.elapsedRealtime()
                val position = player.currentPosition
                timings.maxBackwardJumpMs = maxOf(timings.maxBackwardJumpMs, previousPosition - position)
                previousPosition = position
                if (server.stallStartedAt.get() > 0 && player.playbackState == Player.STATE_BUFFERING) {
                    if (!sawBuffering) onBuffering?.invoke()
                    sawBuffering = true
                    if (stalledPosition < 0) stalledPosition = position
                }
                val resumed = server.stallFinishedAt.get() > 0 && sawBuffering &&
                    player.playbackState == Player.STATE_READY && player.isPlaying && position >= stalledPosition + 750
                if (resumed) {
                    timings.recoveredAt = now
                    timings.stalledPositionMs = stalledPosition
                    timings.recoveredPositionMs = position
                }
                resumed
            }
        }
        assertTrue("The server must really withhold body bytes for three seconds", server.stallFinishedAt.get() - server.stallStartedAt.get() >= 3_000)
        assertTrue("The fixture must exhaust its short initial reservoir", sawBuffering)
        assertTrue("Recovery must not reset the source clock", timings.maxBackwardJumpMs < 1_000)
        assertNull("A transient read gap must not become a playback error", timings.error.get())
        main { player.pause() }
    }

    private fun await(message: String, timeoutMs: Long, server: StreamingFixture, timings: Timings, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            server.assertHealthy()
            timings.error.get()?.let { fail("$message: $it") }
            if (predicate()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun assertColoredFrame(surface: SurfaceView) {
        val bitmap = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            val status = AtomicLong(-1)
            main {
                assertTrue("The native video Surface must be valid", surface.holder.surface.isValid)
                PixelCopy.request(surface, bitmap, { result -> status.set(result.toLong()); copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("The decoded pixels must reach the Surface", copied.await(3, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS.toLong(), status.get())
            var colored = 0
            for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 16) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 30) colored++
            }
            assertTrue("A READY state alone must not pass a black frame", colored > 15)
        } finally { bitmap.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun saveResult(context: Context, stall: Boolean, timings: Timings, server: StreamingFixture, fixtureBytes: Int) {
        val requestedLabel = InstrumentationRegistry.getArguments().getString("benchmarkLabel").orEmpty()
        val label = requestedLabel.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}").matches(it) } ?: "unlabeled"
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val result = JSONObject().put("case", if (stall) "three-second-stall" else "first-frame")
            .put("benchmarkLabel", label)
            .put("applicationVersion", info.versionName).put("applicationVersionCode", versionCode)
            .put("sdk", Build.VERSION.SDK_INT).put("fixture", FIXTURE).put("fixtureSha256", FIXTURE_SHA256)
            .put("fixtureBytes", fixtureBytes).put("sourceWidth", 3840).put("sourceHeight", 2160)
            .put("codec", timings.codec).put("hardwareDecoder", timings.hardwareDecoder)
            .put("creationMs", (timings.prepareAt - timings.startedAt).coerceAtLeast(0))
            .put("firstFrameMs", timings.firstFrameAt.get().takeIf { it > 0 }?.minus(timings.startedAt))
            .put("prepareToFirstFrameMs", timings.firstFrameAt.get().takeIf { it > 0 }?.minus(timings.prepareAt))
            .put("readyMs", timings.readyAt.get().takeIf { it > 0 }?.minus(timings.startedAt))
            .put("firstHttpGetMs", server.firstGetAt.get().takeIf { it > 0 }?.minus(timings.startedAt))
            .put("httpRequests", server.requestSummary()).put("payloadBytesWritten", server.payloadBytes.get())
            .put("rebufferCount", timings.rebufferCount).put("rebufferMs", timings.rebufferDuration())
            .put("networkHoldMs", if (server.stallFinishedAt.get() > 0) server.stallFinishedAt.get() - server.stallStartedAt.get() else 0)
            .put("recoveryAfterServerResumedMs", timings.recoveredAt.takeIf { it > 0 }?.minus(server.stallFinishedAt.get()))
            .put("stalledPositionMs", timings.stalledPositionMs).put("recoveredPositionMs", timings.recoveredPositionMs)
            .put("maxBackwardJumpMs", timings.maxBackwardJumpMs).put("finalPositionMs", timings.finalPositionMs)
            .put("durationMs", timings.durationMs).put("playbackError", timings.error.get())
            .put("frameDropCount", timings.frameDropCount ?: JSONObject.NULL)
            .put("decoderFrameDropCount", timings.decoderFrameDropCount ?: JSONObject.NULL)
            .put("demuxerCacheDurationSeconds", timings.demuxerCacheDurationSeconds ?: JSONObject.NULL)
            .put("scope", "Original synthetic loopback media; excludes Shyni API, WAN/server throughput, HDR and DRM")
        val directory = File(context.cacheDir, "4k-benchmark").apply { mkdirs() }
        File(directory, "$label-${if (stall) "three-second-stall" else "first-frame"}.json").writeText(result.toString(2))
        Log.i("shyniflex4kBenchmark", result.toString())
    }

    private class Timings {
        var startedAt = 0L
        var prepareAt = 0L
        var finishedAt = 0L
        val firstFrameAt = AtomicLong()
        val readyAt = AtomicLong()
        val error = AtomicReference<String?>()
        var codec = ""
        var hardwareDecoder = ""
        var rebufferCount = 0
        private var bufferStartedAt = 0L
        private var bufferedForMs = 0L
        var recoveredAt = 0L
        var stalledPositionMs = -1L
        var recoveredPositionMs = -1L
        var maxBackwardJumpMs = 0L
        var finalPositionMs = 0L
        var durationMs = 0L
        var frameDropCount: Double? = null
        var decoderFrameDropCount: Double? = null
        var demuxerCacheDurationSeconds: Double? = null
        @Synchronized fun state(value: Int) {
            val now = SystemClock.elapsedRealtime()
            if (value == Player.STATE_READY) readyAt.compareAndSet(0, now)
            if (value == Player.STATE_BUFFERING && firstFrameAt.get() > 0 && bufferStartedAt == 0L) {
                rebufferCount++; bufferStartedAt = now
            } else if (value != Player.STATE_BUFFERING && bufferStartedAt > 0) {
                bufferedForMs += now - bufferStartedAt; bufferStartedAt = 0
            }
        }
        @Synchronized fun rebufferDuration(): Long = bufferedForMs + if (bufferStartedAt > 0) finishedAt - bufferStartedAt else 0
    }

    /** Every range shares one gate so opening a replacement connection cannot bypass the gap. */
    private class StreamingFixture(private val clip: ByteArray, private val hold: Boolean,
        private val startupHold: Boolean = false, private val holdHeaders: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val executor = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "4k-qa-http").apply { isDaemon = true } }
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        private val requests = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val prefixBytes = clip.size / 6 // Approximately two source seconds, including the fast-start index.
        val firstFrame = CountDownLatch(1)
        val firstGetAt = AtomicLong()
        val payloadBytes = AtomicLong()
        val stallStartedAt = AtomicLong()
        val stallFinishedAt = AtomicLong()
        val startupBlocked = CountDownLatch(1)
        private val startupGate = CountDownLatch(if (startupHold) 1 else 0)
        val headersBlocked = CountDownLatch(1)
        val cancelledBeforeHeaders = CountDownLatch(1)
        private val headerGate = CountDownLatch(if (holdHeaders) 1 else 0)
        val headersAreWithheld: Boolean get() = holdHeaders && headerGate.count > 0
        val url: String get() = "http://127.0.0.1:${server.localPort}/fixture.mp4"
        private val listener = thread(name = "4k-qa-loopback", isDaemon = true) {
            while (!closed.get()) {
                try { val socket = server.accept(); executor.execute { respond(socket) } }
                catch (_: SocketTimeoutException) { }
                catch (error: Throwable) { if (!closed.get()) failure.compareAndSet(null, error) }
            }
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic 4K HTTP fixture failed", it) } }
        fun requestSummary(): JSONArray = synchronized(requests) { JSONArray(requests.toList()) }
        fun releaseStartup() = startupGate.countDown()
        fun releaseHeaders() = headerGate.countDown()

        private fun respond(socket: Socket) = socket.use { connection ->
            try {
                connection.soTimeout = 3_000
                val input = BufferedInputStream(connection.getInputStream())
                val request = line(input)?.split(' ') ?: return@use
                check(request.size == 3 && request[0] in setOf("GET", "HEAD") && Uri.parse(request[1]).path == "/fixture.mp4")
                val headers = mutableMapOf<String, String>()
                var count = 0
                while (true) {
                    val header = line(input) ?: error("Incomplete HTTP request")
                    if (header.isEmpty()) break
                    check(++count <= 64)
                    val colon = header.indexOf(':'); check(colon > 0)
                    headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                }
                if (request[0] == "GET") firstGetAt.compareAndSet(0, SystemClock.elapsedRealtime())
                val range = headers["range"]?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it) ?: error("Unsupported synthetic Range") }
                val suffix = range != null && range.groupValues[1].isEmpty()
                val start = if (suffix) (clip.size - range!!.groupValues[2].toInt()).coerceAtLeast(0) else range?.groupValues?.get(1)?.toInt() ?: 0
                val end = if (suffix) clip.lastIndex else range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
                val valid = start in clip.indices && end >= start
                val status = if (!valid) 416 else if (range != null) 206 else 200
                val record = JSONObject().put("method", request[0]).put("range", headers["range"]).put("status", status)
                    .put("elapsedAtRequestMs", SystemClock.elapsedRealtime())
                requests += record
                if (holdHeaders) {
                    headersBlocked.countDown()
                    connection.soTimeout = 50
                    val deadline = SystemClock.elapsedRealtime() + 60_000
                    while (!closed.get() && !headerGate.await(25, TimeUnit.MILLISECONDS)) {
                        check(SystemClock.elapsedRealtime() < deadline) { "The slow-header fixture was not released" }
                        // A GET has no request body. EOF proves native cancellation
                        // before this fixture has made any response bytes available.
                        try {
                            if (input.read() < 0) { cancelledBeforeHeaders.countDown(); return@use }
                            error("Unexpected data after the synthetic GET headers")
                        } catch (_: SocketTimeoutException) { }
                        catch (_: SocketException) { cancelledBeforeHeaders.countDown(); return@use }
                    }
                    if (closed.get()) return@use
                }
                val output = connection.getOutputStream()
                if (!valid) {
                    output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
                    return@use
                }
                val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
                output.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Partial Content"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                if (request[0] == "HEAD") return@use
                var position = start
                while (position <= end && !closed.get()) {
                    if (startupHold && position >= prefixBytes) startupGate()
                    else if (hold && position >= prefixBytes) gate()
                    val chunk = minOf(32_768, end - position + 1,
                        if ((hold || startupHold) && position < prefixBytes) prefixBytes - position else Int.MAX_VALUE)
                    output.write(clip, position, chunk); output.flush()
                    payloadBytes.addAndGet(chunk.toLong()); position += chunk
                }
            } catch (_: SocketException) {
                // Pause/release and FFmpeg probe replacement can cancel an HTTP range.
            } catch (error: Throwable) { if (!closed.get()) failure.compareAndSet(null, error) }
        }

        private fun startupGate() {
            startupBlocked.countDown()
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!closed.get() && !startupGate.await(50, TimeUnit.MILLISECONDS))
                check(SystemClock.elapsedRealtime() < deadline) { "The startup regression did not release its fixture" }
        }

        private fun gate() {
            if (stallFinishedAt.get() > 0 || closed.get()) return
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!firstFrame.await(50, TimeUnit.MILLISECONDS)) {
                if (closed.get()) return
                check(SystemClock.elapsedRealtime() < deadline) { "The fast-start prefix could not produce a first frame" }
            }
            stallStartedAt.compareAndSet(0, SystemClock.elapsedRealtime())
            val end = stallStartedAt.get() + 3_000
            while (!closed.get() && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
            if (!closed.get()) stallFinishedAt.compareAndSet(0, SystemClock.elapsedRealtime())
        }

        private fun line(input: BufferedInputStream): String? {
            val data = ByteArrayOutputStream()
            while (data.size() < 8_192) {
                val next = input.read()
                if (next < 0) return if (data.size() == 0) null else error("Incomplete HTTP line")
                if (next == 10) return data.toString("US-ASCII").trimEnd('\r')
                data.write(next)
            }
            error("HTTP fixture line too long")
        }

        override fun close() {
            closed.set(true); firstFrame.countDown(); releaseStartup(); releaseHeaders(); server.close(); listener.join(1_000)
            executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun nativeNumber(name: String): Double? =
        runCatching { MPVLib.getPropertyDouble(name) }.getOrNull()?.takeIf { it.isFinite() }

    private fun requireSyntheticEnvironment(context: Context) {
        assumeTrue("Only the isolated synthetic QA app may run this test", context.packageName.endsWith(".spoilerqa"))
        assertFalse((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
    }

    private class MainHeartbeat : Runnable {
        private val handler = Handler(Looper.getMainLooper())
        private var dueAt = 0L
        val maxDelayMs = AtomicLong()
        val ticks = AtomicInteger()
        fun start() { dueAt = SystemClock.elapsedRealtime() + 32; handler.postDelayed(this, 32) }
        fun stop() = handler.removeCallbacks(this)
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            maxDelayMs.set(maxOf(maxDelayMs.get(), (now - dueAt).coerceAtLeast(0)))
            ticks.incrementAndGet(); dueAt = now + 32; handler.postDelayed(this, 32)
        }
    }

    private class TrackObservation : MPVLib.EventObserver {
        val sawAudioAndVideo = AtomicBoolean()
        val selectedAudioStreamIndex = AtomicInteger(-1)
        override fun eventProperty(property: String, value: String) {
            if (property != "track-list") return
            val rows = runCatching { JSONArray(value) }.getOrNull() ?: return
            var audio = false; var video = false
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (row.optString("type") == "video") video = true
                if (row.optString("type") == "audio") {
                    audio = true
                    if (row.optBoolean("selected") && row.has("ff-index")) selectedAudioStreamIndex.set(row.optInt("ff-index", -1))
                }
            }
            if (audio && video) sawAudioAndVideo.set(true)
        }
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Double) {}
        override fun eventProperty(property: String, value: Boolean) {}
        override fun event(eventId: Int) {}
    }

    private companion object {
        const val FIXTURE = "synthetic-4k-hevc-main10.mp4"
        const val FIXTURE_SHA256 = "cc4ed6708e2c993cc95f67a76d28a2f7387b3eeb7c45cebf1b429dd7327dbb07"
    }
}
