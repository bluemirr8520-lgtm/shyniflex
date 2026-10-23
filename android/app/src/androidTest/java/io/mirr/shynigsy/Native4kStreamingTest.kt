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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Synthetic loopback/decoder benchmarks: no MainActivity, account, PIN or SessionStore. */
@RunWith(AndroidJUnit4::class)
class Native4kStreamingTest {
    @Test fun fourKHevcMain10RendersItsFirstFrameOverHttp() = benchmark(stall = false)

    @Test fun fourKHevcRecoversAfterAThreeSecondNetworkStall() = benchmark(stall = true)

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

    private fun verifyRecovery(player: MpvPlayer, server: StreamingFixture, timings: Timings) {
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
        private val startupHold: Boolean = false) : AutoCloseable {
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
            closed.set(true); firstFrame.countDown(); releaseStartup(); server.close(); listener.join(1_000)
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

    private companion object {
        const val FIXTURE = "synthetic-4k-hevc-main10.mp4"
        const val FIXTURE_SHA256 = "cc4ed6708e2c993cc95f67a76d28a2f7387b3eeb7c45cebf1b429dd7327dbb07"
    }
}
