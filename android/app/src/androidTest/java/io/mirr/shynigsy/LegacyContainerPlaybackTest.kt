package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.mpv.MpvPlayer
import `is`.xyz.mpv.MPVLib
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local synthetic originals exercise demux, software decode, audio selection, seek, and EOF. */
@RunWith(AndroidJUnit4::class)
class LegacyContainerPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun wmv1AndWma2RenderSeekAndFinish() = assertPlayback("legacy-wmv1.wmv", "wmv1", "wmav2")
    @Test fun wmv2AndWma2RenderSeekAndFinish() = assertPlayback("legacy-wmv2.wmv", "wmv2", "wmav2")
    @Test fun aviMpeg4AndMp3RenderSeekAndFinish() = assertPlayback("legacy-mpeg4.avi", "mpeg4", "mp3")
    @Test fun mpegTsH264AndAacRenderSeekAndFinish() = assertPlayback("legacy-h264.ts", "h264", "aac")

    private fun assertPlayback(asset: String, videoCodec: String, audioCodec: String) {
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated QA application may run the codec fixture",
            context.packageName == "io.mirr.shyniflex.spoilerqa")
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the device before testing actual rendered video", keyguard.isKeyguardLocked)
        val directory = File(context.cacheDir, "legacy-codec-fixtures").apply { mkdirs() }
        val file = File(directory, asset).also { output ->
            instrumentation.context.assets.open("media3/$asset").use { input ->
                output.outputStream().use(input::copyTo)
            }
        }
        val firstFrame = AtomicBoolean()
        val failure = AtomicReference<PlaybackException>()
        var player: MpvPlayer? = null
        var view: PlayerView? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val video = PlayerView(activity).apply { useController = false }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(video, FrameLayout.LayoutParams(-1, -1))
                })
                val active = MpvPlayer(activity).apply {
                    volume = 0f
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { firstFrame.set(true) }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                    })
                }
                video.player = active
                active.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                active.prepare()
                active.play()
                player = active
                view = video
            }
            val active = player!!
            try {
                waitFor("$asset actual video and audio decoding", failure) {
                    firstFrame.get() && active.currentPosition >= 350 && active.isPlaying &&
                        active.videoSize.width > 0 && active.videoSize.height > 0 &&
                        active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } &&
                        active.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } &&
                        (MPVLib.getPropertyInt("audio-params/samplerate") ?: 0) > 0
                }
                main {
                    active.pause()
                    val groups = active.currentTracks.groups
                    assertEquals(videoCodec, groups.single { it.type == C.TRACK_TYPE_VIDEO }.getTrackFormat(0).codecs)
                    assertEquals(audioCodec, groups.single { it.type == C.TRACK_TYPE_AUDIO }.getTrackFormat(0).codecs)
                    assertTrue("The original four-second timeline must be available", active.duration in 3_700L..5_000L)
                    assertEquals("Audio must retain its original global stream index", 1, active.selectedAudioStreamIndex())
                }
                assertVideoPixels(main { view!!.videoSurfaceView as SurfaceView })
                main {
                    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                }
                waitFor("$asset audio disable", failure) { active.selectedAudioStreamIndex() == null }
                main {
                    val audio = active.currentTracks.groups.single { it.type == C.TRACK_TYPE_AUDIO }
                    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(audio.mediaTrackGroup, 0)).build()
                    active.seekTo(1_400)
                    active.play()
                }
                waitFor("$asset seek resumes the intended audio and video", failure) {
                    active.playbackState == Player.STATE_READY && active.isPlaying &&
                        active.currentPosition in 1_650L..3_700L && active.selectedAudioStreamIndex() == 1 &&
                        MPVLib.getPropertyBoolean("seeking") != true &&
                        (MPVLib.getPropertyInt("audio-params/samplerate") ?: 0) > 0
                }
                main { active.pause() }
                assertVideoPixels(main { view!!.videoSurfaceView as SurfaceView })
                main { active.play() }
                waitFor("$asset natural EOF", failure) { active.playbackState == Player.STATE_ENDED }
                assertTrue("The decoded original timeline must finish", main { active.currentPosition >= 3_700 })
            } finally {
                main { view?.player = null; active.release() }
                file.delete()
            }
        }
    }

    private fun assertVideoPixels(surface: SurfaceView) {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        try {
            val ready = CountDownLatch(1)
            val status = AtomicInteger(-1)
            main {
                assertTrue("The video Surface must be valid", surface.holder.surface.isValid)
                PixelCopy.request(surface, bitmap, { result -> status.set(result); ready.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Rendered pixels must arrive", ready.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status.get())
            var brightSamples = 0
            for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 16) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 24 || Color.green(pixel) > 24 || Color.blue(pixel) > 24) brightSamples++
            }
            assertTrue("Actual video pixels must be visible, not a black Surface", brightSamples > 15)
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
