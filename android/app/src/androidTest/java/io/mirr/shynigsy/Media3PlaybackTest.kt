package io.mirr.shynigsy

import android.net.Uri
import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.Media3GestureView
import io.mirr.shynigsy.player.mpv.MpvPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real, muted decode tests. Synthetic fixtures belong only to the test APK. */
@RunWith(AndroidJUnit4::class)
class Media3PlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun mpvDecodesAac() = assertMpvDecode("tone-aac.m4a")
    @Test fun mpvDecodesAc3() = assertMpvDecode("tone-ac3.ac3")
    @Test fun mpvDecodesEac3() = assertMpvDecode("tone-eac3.eac3")
    @Test fun mpvDecodesDts() = assertMpvDecode("tone-dts.mka")
    @Test fun mpvDecodesFlac() = assertMpvDecode("tone-flac.flac")
    @Test fun mpvDecodesOpus() = assertMpvDecode("tone-opus.ogg")
    @Test fun mpvDecodesTrueHd() = assertMpvDecode("tone-truehd.mka")

    private fun assertMpvDecode(asset: String) {
        val file = fixture(asset)
        val ended = CountDownLatch(1)
        val error = AtomicReference<PlaybackException>()
        val audioObserved = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val furthestPosition = AtomicLong(0)
        val player = main {
            newPlayer().apply {
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }) audioObserved.set(true)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) { completed.set(true); ended.countDown() }
                    }
                    override fun onPlayerError(failure: PlaybackException) {
                        error.set(failure); ended.countDown()
                    }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (ended.count > 0 && SystemClock.elapsedRealtime() < deadline) {
                main {
                    furthestPosition.set(maxOf(furthestPosition.get(), player.currentPosition))
                    if (player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }) audioObserved.set(true)
                }
                ended.await(50, TimeUnit.MILLISECONDS)
            }
            assertEquals("$asset did not finish within 20 seconds", 0L, ended.count)
            error.get()?.let { throw AssertionError("$asset decode failed: ${it.errorCodeName}", it) }
            assertTrue("$asset did not report an MPV audio track", audioObserved.get())
            assertTrue("$asset clock never advanced", furthestPosition.get() >= 250)
            assertTrue("$asset never reached end of file", completed.get())
            Log.i("shyniflexPlaybackTest", "$asset: libmpv audio track, position=${furthestPosition.get()}ms, completed")
        } finally { main { player.release() } }
    }

    @Test fun videoGestureSeeksForwardBackwardCancelsAndRestoresPlayPause() {
        val keyguard = instrumentation.targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the test device before checking a visible video Surface", keyguard.isKeyguardLocked)
        val clip = fixture("gesture-av.mp4")
        var player: MpvPlayer? = null
        var gestures: Media3GestureView? = null
        val settled = AtomicLong(-1)
        val committed = AtomicLong(-1)
        val failed = AtomicLong(-1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root = FrameLayout(activity)
                val nativeView = PlayerView(activity).apply { useController = false }
                val active = newPlayer()
                val overlay = Media3GestureView(activity).apply {
                    onSeekCommitted = { committed.set(it) }
                    onSeekSettled = { settled.set(it) }
                    onSeekFailed = { failed.set(it) }
                    attachPlayer(active)
                }
                root.addView(nativeView, FrameLayout.LayoutParams(-1, -1))
                root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                nativeView.player = active
                active.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)), 1_000)
                active.prepare()
                active.playWhenReady = false
                player = active
                gestures = overlay
            }
            val active = player!!
            val overlay = gestures!!
            try {
                waitFor("AV fixture ready", diagnostics = {
                    "state=${active.playbackState}, error=${active.playerError?.errorCodeName}, " +
                        "viewport=${overlay.width}x${overlay.height}, shown=${overlay.isShown}, focus=${overlay.hasWindowFocus()}"
                }) { active.playbackState == Player.STATE_READY && overlay.width > 0 && overlay.isShown && overlay.hasWindowFocus() }
                drag(overlay, 0.2f, 0.7f)
                waitFor("forward seek settles") { !overlay.isSeekGestureActive && settled.get() > 3_000 }
                assertEquals(-1L, failed.get())
                assertTrue("Drag UP must commit its final target", committed.get() > 3_000)
                assertFalse("A paused video must stay paused after scrubbing", main { active.playWhenReady })
                assertTrue("Video timeline must match final forward target", abs(main { active.currentPosition } - settled.get()) < 750)

                settled.set(-1)
                drag(overlay, 0.7f, 0.2f)
                waitFor("reverse seek settles") { !overlay.isSeekGestureActive && settled.get() in 0..2_000 }
                assertFalse(main { active.playWhenReady })
                assertTrue(abs(main { active.currentPosition } - settled.get()) < 750)

                val beforeCancel = main { active.currentPosition }
                settled.set(-1)
                drag(overlay, 0.3f, 0.6f, cancel = true)
                waitFor("cancel rolls back") { !overlay.isSeekGestureActive && settled.get() >= 0 }
                assertTrue("ACTION_CANCEL must restore the original timeline", abs(main { active.currentPosition } - beforeCancel) < 750)
                assertFalse(main { active.playWhenReady })

                main { active.playWhenReady = true }
                waitFor("play starts") { active.isPlaying }
                settled.set(-1)
                drag(overlay, 0.25f, 0.5f)
                waitFor("playing scrub restores playback") { !overlay.isSeekGestureActive && settled.get() >= 0 && active.playWhenReady }
                assertEquals(-1L, failed.get())
            } finally {
                main { overlay.attachPlayer(null); active.release() }
            }
        }
    }

    private fun drag(view: Media3GestureView, from: Float, to: Float, cancel: Boolean = false) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) = main {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                view.width * fraction, view.height * 0.5f, 0).also { event ->
                view.dispatchTouchEvent(event); event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, from)
        repeat(4) { index ->
            Thread.sleep(90)
            send(MotionEvent.ACTION_MOVE, from + (to - from) * (index + 1) / 4)
        }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, to)
    }

    private fun newPlayer(): MpvPlayer = MpvPlayer(instrumentation.targetContext, headers = emptyMap()).apply {
        volume = 0f
        // Audio fixtures have no foreground Activity; Android 15+ can deny audio focus.
        setAudioAttributes(AudioAttributes.DEFAULT, false)
    }

    private fun fixture(name: String): File {
        val directory = File(instrumentation.targetContext.cacheDir, "media3-test-fixtures").apply { mkdirs() }
        return File(directory, name).also { output ->
            instrumentation.context.assets.open("media3/$name").use { source ->
                output.outputStream().use { destination -> source.copyTo(destination) }
            }
        }
    }

    private fun waitFor(description: String, diagnostics: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(predicate)) return
            Thread.sleep(50)
        }
        fail("Timed out: $description ${main(diagnostics)}")
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
