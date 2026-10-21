package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real rendered frames, not just accepted containers or a moving clock. */
@RunWith(AndroidJUnit4::class)
class ModernCodecPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun h264RendersWithAutomaticProfile() = assertVideo("gesture-av.mp4", "h264")
    @Test fun hevcMain10RendersWithAutomaticProfile() = assertVideo("modern-hevc-main10.mkv", "hevc")
    @Test fun vp9RendersWithAutomaticProfile() = assertVideo("modern-vp9.webm", "vp9")
    @Test fun av1RendersWithAutomaticProfile() = assertVideo("modern-av1.mkv", "av1")
    @Test fun av1AlsoRendersWithServerSoftwareHint() = assertVideo("modern-av1.mkv", "av1", software = true)

    private fun assertVideo(asset: String, expectedCodec: String, software: Boolean = false) {
        val keyguard = instrumentation.targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the device before testing a real video Surface", keyguard.isKeyguardLocked)
        val directory = File(instrumentation.targetContext.cacheDir, "modern-codec-fixtures").apply { mkdirs() }
        val file = File(directory, asset).also { output ->
            instrumentation.context.assets.open("media3/$asset").use { input -> output.outputStream().use(input::copyTo) }
        }
        val failure = AtomicReference<PlaybackException>()
        val firstFrame = AtomicBoolean(false)
        var player: MpvPlayer? = null
        var view: PlayerView? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root = FrameLayout(activity)
                val video = PlayerView(activity).apply { useController = false }
                root.addView(video, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                val active = MpvPlayer(activity, preferSoftwareDecode = software).apply {
                    volume = 0f
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { firstFrame.set(true) }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                    })
                }
                video.player = active
                active.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                active.prepare(); active.play()
                player = active; view = video
            }
            val active = player!!
            try {
                waitFor("$asset decoded frame and clock", failure) {
                    firstFrame.get() && active.currentPosition >= 400 &&
                        active.videoSize.width > 0 && active.videoSize.height > 0 &&
                        active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                }
                val nativeDecoder = main {
                    active.pause()
                    val codec = active.currentTracks.groups.first { it.type == C.TRACK_TYPE_VIDEO }.getTrackFormat(0).codecs
                    assertEquals("The intended codec must reach libmpv", expectedCodec, codec)
                    assertTrue("Fixture duration unavailable", active.duration >= 2_900)
                    MPVLib.getPropertyString("hwdec-current").orEmpty()
                }
                if (software) assertEquals("Server software hint was ignored", "no", nativeDecoder)
                assertRenderedColor(main { view!!.videoSurfaceView as SurfaceView })
                main { active.play() }
                waitFor("$asset EOF", failure) { active.playbackState == Player.STATE_ENDED }
                assertTrue("The decoded timeline did not complete", main { active.currentPosition >= 2_900 })
                Log.i("shyniflexModernCodec", "$expectedCodec: rendered pixels, decoder=$nativeDecoder, EOF; softwareHint=$software")
            } finally {
                main { view?.player = null; active.release() }
            }
        }
    }

    private fun assertRenderedColor(surface: SurfaceView) {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            val result = AtomicInteger(-1)
            main {
                assertTrue("Video Surface is unavailable", surface.holder.surface.isValid)
                PixelCopy.request(surface, bitmap, { status -> result.set(status); copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Video pixels were not copied", copied.await(5, TimeUnit.SECONDS))
            assertEquals("PixelCopy failed", PixelCopy.SUCCESS, result.get())
            var colors = 0
            for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 16) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 24 || Color.green(pixel) > 24 || Color.blue(pixel) > 24) colors++
            }
            assertTrue("The decoded video remained black", colors > 15)
        } finally { bitmap.recycle() }
    }

    private fun waitFor(description: String, failure: AtomicReference<PlaybackException>, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("$description: ${it.errorCodeName}", it) }
            if (main(predicate)) return
            Thread.sleep(40)
        }
        fail("Timed out waiting for $description")
    }

    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }
}
