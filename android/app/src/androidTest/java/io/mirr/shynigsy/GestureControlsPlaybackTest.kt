package io.mirr.shynigsy

import android.app.KeyguardManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.PlaybackMarker
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.Media3GestureView
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exercise the production Activity, rather than a control-only imitation. */
@RunWith(AndroidJUnit4::class)
class GestureControlsPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun tvResizeUpdatesNativeRenderer() = withFixture(false) { scenario ->
        org.junit.Assume.assumeTrue(instrumentation.targetContext.isTelevision())
        waitFor(scenario, "source ready for resize") { activity ->
            val video = findView<PlayerView>(activity.findViewById(android.R.id.content))!!
            if (video.player?.playbackState == Player.STATE_READY) { video.player!!.pause(); video.player!!.volume = 0f }
            video.player?.playbackState == Player.STATE_READY
        }
        scenario.onActivity { activity ->
            // Use the real synthetic video with a 4:3 display aspect to expose letterboxing.
            `is`.xyz.mpv.MPVLib.setPropertyDouble("video-aspect-override", 4.0 / 3.0)
            repeat(6) {
                if (activity.findViewById<View>(R.id.player_resize).contentDescription.toString() != "화면 맞춤")
                    activity.findViewById<View>(R.id.player_resize).performClick()
            }
            findView<PlayerView>(activity.findViewById(android.R.id.content))!!.hideController()
        }
        val widths = linkedMapOf<String, Int>()
        var screenWidth = 0
        repeat(6) { index ->
            Thread.sleep(450)
            var label = ""
            scenario.onActivity { activity ->
                val video = findView<PlayerView>(activity.findViewById(android.R.id.content))!!
                video.hideController()
                label = activity.findViewById<View>(R.id.player_resize).contentDescription.toString()
                assertFalse("Resizing preserves paused playback", video.player!!.playWhenReady)
                assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL, video.resizeMode)
                android.util.Log.i("ResizeCheck", "$label surface=${`is`.xyz.mpv.MPVLib.getPropertyString("android-surface-size")} " +
                    "margins=${listOf("ml", "mr", "mt", "mb").map { `is`.xyz.mpv.MPVLib.getPropertyInt("osd-dimensions/$it") }}")
            }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                screenWidth = bitmap.width
                val colored = (0 until bitmap.width).filter { x -> (2..8).any { sample ->
                    val pixel = bitmap.getPixel(x, bitmap.height * sample / 10)
                    android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) > 90
                } }
                widths[label] = (colored.lastOrNull() ?: 0) - (colored.firstOrNull() ?: 0) + 1
                val folder = instrumentation.targetContext.getExternalFilesDir("resize-check")!!
                folder.mkdirs()
                File(folder, "mode-$index.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            scenario.onActivity { it.findViewById<View>(R.id.player_resize).performClick() }
        }
        assertTrue("Fit must visibly retain side bars: $widths", widths.getValue("화면 맞춤") < screenWidth * .85)
        assertTrue("Crop must visibly fill the display: $widths", widths.getValue("화면 채움") > screenWidth * .95)
        assertTrue("Stretch must visibly fill the display: $widths", widths.getValue("화면 늘림") > screenWidth * .95)
        assertTrue("110% must visibly enlarge the image: $widths", widths.getValue("확대 110%") > widths.getValue("화면 맞춤") + screenWidth * .03)
        assertTrue("125% must visibly enlarge it further: $widths", widths.getValue("확대 125%") > widths.getValue("확대 110%") + screenWidth * .03)
    }
    @Test fun tvResizePersistsForAnotherVideoAndActivityRecreation() = withFixture(false) { scenario ->
        org.junit.Assume.assumeTrue(instrumentation.targetContext.isTelevision())
        waitFor(scenario, "source ready for persistent resize") { activity ->
            findView<PlayerView>(activity.findViewById(android.R.id.content))?.player?.playbackState == Player.STATE_READY
        }
        var nextIntent: Intent? = null
        scenario.onActivity { activity ->
            repeat(6) {
                if (activity.findViewById<View>(R.id.player_resize).contentDescription.toString() != "확대 125%")
                    activity.findViewById<View>(R.id.player_resize).performClick()
            }
            val nextSpec = PlaybackSpec.fromJson(activity.intent.getStringExtra("spec")!!)
                .copy(title = "다른 영상", itemId = 2)
            nextIntent = Intent(activity.intent).putExtra("spec", nextSpec.toJson())
        }
        scenario.close()
        ActivityScenario.launchActivityForResult<NativePlayerActivity>(nextIntent!!).use { next ->
            fun assertSavedScale() {
                waitFor(next, "saved resize applied to the new output") { activity ->
                    val video = findView<PlayerView>(activity.findViewById(android.R.id.content))!!
                    if (video.player?.playbackState == Player.STATE_READY) video.player!!.pause()
                    video.player?.playbackState == Player.STATE_READY &&
                        activity.findViewById<View>(R.id.player_resize).contentDescription.toString() == "확대 125%" &&
                        abs((`is`.xyz.mpv.MPVLib.getPropertyDouble("video-zoom") ?: -1.0) - kotlin.math.log2(1.25)) < .001
                }
            }
            assertSavedScale()
            next.recreate()
            assertSavedScale()
        }
    }

    @Test fun tvSettingsTabsAndRowsAcceptDirectionalNavigation() = withFixture(false) { scenario ->
        org.junit.Assume.assumeTrue(instrumentation.targetContext.isTelevision())
        waitFor(scenario, "paused source ready for settings navigation") { activity ->
            val active = findView<PlayerView>(activity.findViewById(android.R.id.content))?.player
            if (active?.playbackState == Player.STATE_READY) active.pause()
            active?.playbackState == Player.STATE_READY && !active.playWhenReady
        }
        fun focusedLabel(): String {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return ""
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            return try { focused?.contentDescription?.toString() ?: focused?.text?.toString().orEmpty() }
            finally { focused?.recycle(); root.recycle() }
        }
        scenario.onActivity { it.findViewById<View>(R.id.player_settings).performClick() }
        waitForWindow("settings opens with speed row focused") { focusedLabel().startsWith("배속") }
        listOf("화면 크기", "화질", "오디오 트랙", "자막 선택", "자막 글꼴").forEach { label ->
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            waitForWindow("settings focus reaches $label") { focusedLabel().startsWith(label) }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForWindow("confirm opens actual font options") { activeWindowChoiceLabels().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario.onActivity { activity ->
            val active = findView<PlayerView>(activity.findViewById(android.R.id.content))!!.player!!
            assertFalse("Settings navigation must preserve paused playback", active.playWhenReady)
        }
    }

    @Test fun remoteSeekPreviewKeepsLatestTargetAndRestoresPause() = withFixture(false) { scenario ->
        var overlay: Media3GestureView? = null
        var video: PlayerView? = null
        waitFor(scenario, "paused source ready for remote preview") { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            overlay = findView(root)
            video = findView(root)
            video?.player?.let { active ->
                active.volume = 0f
                if (active.playbackState == Player.STATE_READY) active.pause()
                active.playbackState == Player.STATE_READY && !active.playWhenReady
            } == true
        }
        scenario.onActivity {
            val gesture = requireNotNull(overlay)
            assertTrue(gesture.beginRemoteSeek(true))
            gesture.previewRemoteSeek(2_500)
            gesture.previewRemoteSeek(3_000)
            gesture.previewRemoteSeek(4_000)
            assertFalse(gesture.findViewWithTag<View>(Media3GestureView.LEVEL_HUD_TAG).isShown)
            val indicator = gesture.findViewWithTag<TextView>(Media3GestureView.SEEK_INDICATOR_TAG)
            assertTrue(indicator.isShown)
            assertFalse(indicator.text.contains(':'))
            assertEquals(4_000L, gesture.pendingSeekPositionMs)
            gesture.finishRemoteSeek()
        }
        waitFor(scenario, "latest remote preview target settles without resuming paused video") {
            val active = video!!.player!!
            !overlay!!.isSeekGestureActive && active.playbackState == Player.STATE_READY &&
                !active.playWhenReady && abs(active.currentPosition - 4_000) < 500
        }
    }

    @Test fun introSkipPreservesPauseAndCreditsAtTheEndReturnsToCaller() = withFixture(false,
        markers = listOf(PlaybackMarker("intro", 1_000, 3_000), PlaybackMarker("credits", 4_000, 6_000))) { scenario ->
        var video: PlayerView? = null
        waitFor(scenario, "intro marker button appears in the paused starting interval") { activity ->
            video = findView(activity.findViewById(android.R.id.content))
            val active = video?.player
            if (active?.playbackState == Player.STATE_READY) active.pause()
            val skip = activity.findViewById<Button>(R.id.player_segment_skip)
            active != null && active.playbackState == Player.STATE_READY && !active.playWhenReady && skip.isShown &&
                skip.text.toString() == "인트로 건너뛰기"
        }
        assertHidden(scenario, "The intro shortcut must work with ordinary controls hidden")
        tapThroughActivityRoot(scenario, R.id.player_segment_skip)
        waitFor(scenario, "intro skip reaches its end and removes the shortcut") { activity ->
            val active = video!!.player!!
            active.playbackState == Player.STATE_READY && active.currentPosition >= 3_000 &&
                !activity.findViewById<View>(R.id.player_segment_skip).isShown
        }
        scenario.onActivity { assertFalse("Skipping the intro preserves paused playback", video!!.player!!.playWhenReady) }
        assertHidden(scenario, "Skipping the intro must not reveal playback controls")

        scenario.onActivity { video!!.player!!.seekTo(4_500) }
        waitFor(scenario, "credits shortcut appears only inside the credits interval") { activity ->
            val skip = activity.findViewById<Button>(R.id.player_segment_skip)
            video!!.player!!.playbackState == Player.STATE_READY && skip.isShown &&
                skip.text.toString() == "크레딧 건너뛰기"
        }
        tapThroughActivityRoot(scenario, R.id.player_segment_skip)
        assertCompletedToCaller(scenario, "Credits ending at the title duration must finish back to the caller", timeoutMs = 3_000)
    }

    @Test fun naturalEofCompletesToCallerWithASuccessfulActivityResult() = withFixture(false) { scenario ->
        waitFor(scenario, "video playing before normal EOF") { activity ->
            val active = findView<PlayerView>(activity.findViewById(android.R.id.content))?.player
            active?.volume = 0f
            active != null && active.isPlaying
        }
        assertCompletedToCaller(scenario, "Natural EOF must complete back to the caller")
    }

    @Test fun pausedSeekBeyondNinetyFivePercentStaysOpenUntilPlaybackReallyEnds() = withFixture(false) { scenario ->
        var video: PlayerView? = null
        waitFor(scenario, "paused source ready before seeking near the end") { activity ->
            video = findView(activity.findViewById(android.R.id.content))
            video?.player?.let { active ->
                active.volume = 0f
                if (active.playbackState == Player.STATE_READY) active.pause()
                active.playbackState == Player.STATE_READY && !active.playWhenReady && active.duration > 0
            } == true
        }
        scenario.onActivity { video!!.player!!.seekTo(5_800) }
        waitFor(scenario, "paused seek is committed beyond 95 percent without reaching EOF") {
            val active = video!!.player!!
            active.playbackState == Player.STATE_READY && !active.playWhenReady && !active.isPlaying &&
                active.currentPosition >= 5_700 && active.currentPosition < active.duration
        }
        // Stay paused for several EOF-reconciliation ticks; a watched threshold is not natural EOF.
        Thread.sleep(1_200)
        assertEquals("A committed near-end seek must not close playback", Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity {
            val active = video!!.player!!
            assertEquals(Player.STATE_READY, active.playbackState)
            assertFalse(active.playWhenReady)
            assertNull("Near-end seeking must not become a playback error", active.playerError)
            assertTrue(active.currentPosition < active.duration)
            active.play()
        }
        assertCompletedToCaller(scenario, "Only subsequent real EOF may finish back to the caller", timeoutMs = 3_000)
    }

    @Test fun invalidLocalMediaShowsAnErrorAndDoesNotFinishAsCompleted() = withFixture(false, invalidMedia = true) { scenario ->
        waitFor(scenario, "invalid local media reports a real playback error") { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val active = findView<PlayerView>(root)?.player
            active?.playerError != null && hasVisibleText(root, "영상을 재생할 수 없습니다.")
        }
        Thread.sleep(1_200)
        assertEquals("A load failure must keep the retry screen open", Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val active = requireNotNull(findView<PlayerView>(root)?.player)
            assertNotEquals("A failed source is not a completed video", Player.STATE_ENDED, active.playbackState)
            assertNotNull(active.playerError)
            assertTrue("The failure remains actionable", hasVisibleText(root, "이 위치에서 다시 재생"))
        }
    }

    @Test fun skipsShowOnlyTheCompactDirectionalSideIndicatorAndCancelClearsIt() = withFixture(false) { scenario ->
        var gesture: Media3GestureView? = null
        var video: PlayerView? = null
        waitFor(scenario, "paused video ready for directional skip feedback") { activity ->
            gesture = findView(activity.findViewById(android.R.id.content))
            video = findView(activity.findViewById(android.R.id.content))
            video?.player?.let { player ->
                player.volume = 0f
                if (player.playbackState == Player.STATE_READY) player.pause()
                player.playbackState == Player.STATE_READY && (gesture?.width ?: 0) > 0 && !player.playWhenReady
            } == true
        }
        val overlay = requireNotNull(gesture)
        fun assertSide(forward: Boolean) {
            val indicator = requireNotNull(overlay.findViewWithTag<TextView>(Media3GestureView.SEEK_INDICATOR_TAG))
            assertTrue("The skip delta must be visible at the video side", indicator.isShown)
            assertTrue(indicator.text.toString().endsWith(if (forward) "초>" else "초<"))
            assertFalse("The skip indicator must not contain the old central timeline", indicator.text.contains(':'))
            val layout = indicator.layoutParams as android.widget.FrameLayout.LayoutParams
            assertEquals(if (forward) Gravity.RIGHT else Gravity.LEFT, layout.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            val safe = ViewCompat.getRootWindowInsets(overlay)?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
            val edge = (24 * overlay.resources.displayMetrics.density).roundToInt()
            assertEquals("The left margin must include the physical cutout/system-bar inset", edge + safe.left, layout.leftMargin)
            assertEquals("The right margin must include the physical cutout/system-bar inset", edge + safe.right, layout.rightMargin)
            if (forward) assertTrue("Forward feedback must sit at the safe physical right edge",
                abs(overlay.width - indicator.right - edge - safe.right) <= 1)
            else assertTrue("Backward feedback must sit at the safe physical left edge",
                abs(indicator.left - edge - safe.left) <= 1)
            assertTrue(indicator.contentDescription.toString().startsWith(if (forward) "앞으로" else "뒤로"))
            assertFalse("Seeking must never show the brightness/volume center HUD",
                overlay.findViewWithTag<View>(Media3GestureView.LEVEL_HUD_TAG).isShown)
        }
        drag(overlay, .2f, .5f, .45f) { assertSide(true) }
        waitFor(scenario, "forward side-feedback seek settles") { !overlay.isSeekGestureActive }
        drag(overlay, .7f, .1f, .45f) { assertSide(false) }
        waitFor(scenario, "backward side-feedback seek settles at the start") {
            !overlay.isSeekGestureActive && (video!!.player?.currentPosition ?: Long.MAX_VALUE) < 700
        }
        Thread.sleep(350)
        doubleTap(overlay, .25f, .45f)
        main {
            assertSide(false)
            val clampedSeconds = overlay.findViewWithTag<TextView>(Media3GestureView.SEEK_INDICATOR_TAG)
                .text.toString().removeSuffix("초<").toDouble()
            assertTrue("A boundary skip must show the remaining fraction, not a fictitious ten seconds", clampedSeconds < 1)
            overlay.cancelSeekGesture()
            assertFalse(overlay.findViewWithTag<View>(Media3GestureView.SEEK_INDICATOR_TAG).isShown)
            assertFalse(overlay.findViewWithTag<View>(Media3GestureView.LEVEL_HUD_TAG).isShown)
        }
        assertHidden(scenario, "The side skip feedback must not reveal controller buttons")
    }

    @Test fun directSubtitleButtonOffersOffWithoutTracksAndPreservesAudioAndPause() = withFixture(false) { scenario ->
        var video: PlayerView? = null
        var selectedAudio: String? = null
        waitFor(scenario, "paused source ready for direct subtitle selection") { activity ->
            video = findView(activity.findViewById(android.R.id.content))
            val active = video?.player
            if (active?.playbackState == Player.STATE_READY) {
                active.pause()
                selectedAudio = active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    .firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                        ?.let { group.getTrackFormat(it).id } }
            }
            active?.playbackState == Player.STATE_READY && !active.playWhenReady
        }
        scenario.onActivity { video!!.showController() }
        tapThroughActivityRoot(scenario, R.id.player_subtitles)
        waitForWindow("direct shortcut opens the subtitle list even without subtitle tracks") {
            activeWindowChoiceLabels() == listOf("자막 끄기")
        }
        assertTrue(clickWindowText("자막 끄기"))
        scenario.onActivity {
            val active = video!!.player!!
            assertTrue(active.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            assertFalse("Subtitle selection must preserve paused playback", active.playWhenReady)
            val audio = active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                    ?.let { group.getTrackFormat(it).id } }
            assertEquals("Subtitle selection must preserve the selected audio track", selectedAudio, audio)
        }
    }

    @Test fun skipGesturesKeepControlsHiddenAndOnlySingleTapShowsThem() = withFixture(false) { scenario ->
        var view: PlayerView? = null
        var gesture: Media3GestureView? = null
        waitFor(scenario, "paused video ready with initially hidden controls") { activity ->
            view = findView(activity.findViewById(android.R.id.content))
            gesture = findView(activity.findViewById(android.R.id.content))
            val player = view?.player
            player?.volume = 0f
            if (player != null && player.playbackState == Player.STATE_READY) player.pause()
            player != null && player.playbackState == Player.STATE_READY && (gesture?.width ?: 0) > 0 && !player.playWhenReady
        }
        Thread.sleep(350)
        assertHidden(scenario, "Paused startup must not show the bottom icons")
        val overlay = requireNotNull(gesture)
        val nativeView = requireNotNull(view)

        doubleTap(overlay, .25f, .45f)
        waitFor(scenario, "double-tap backward settles") {
            !overlay.isSeekGestureActive && (nativeView.player?.currentPosition ?: Long.MAX_VALUE) < 700
        }
        Thread.sleep(350)
        assertHidden(scenario, "Double-tap skip must not reveal controls")
        scenario.onActivity { assertFalse("A paused double-tap skip stays paused", nativeView.player!!.playWhenReady) }

        drag(overlay, .2f, .5f, .45f)
        waitFor(scenario, "swipe forward settles") {
            !overlay.isSeekGestureActive && (nativeView.player?.currentPosition ?: 0) > 1_000
        }
        Thread.sleep(350)
        assertHidden(scenario, "Swipe skip must not reveal controls when playback remains paused")

        tap(overlay, .5f, .45f)
        waitFor(scenario, "single tap reveals controls and lock affordance") {
            nativeView.isControllerFullyVisible && it.findViewById<View>(R.id.player_lock).isShown
        }
        assertLocalNavigationVisibleAndDisabled(scenario)
        drag(overlay, .6f, .25f, .45f) {
            assertFalse("A skip gesture hides controls while the finger is still down", nativeView.isControllerFullyVisible)
            assertFalse("A skip gesture also hides the lock button while the finger is still down",
                nativeView.rootView.findViewById<View>(R.id.player_lock).isShown)
            assertFalse("A skip gesture immediately hides subtitle selection",
                nativeView.findViewById<View>(R.id.player_subtitles).isShown)
            assertFalse(nativeView.findViewById<View>(R.id.player_subtitles).isEnabled)
        }
        waitFor(scenario, "visible controls hide during a skip gesture") { !nativeView.isControllerFullyVisible }
        waitFor(scenario, "second swipe settles") { !overlay.isSeekGestureActive }
        Thread.sleep(350)
        assertHidden(scenario, "Completing the drag must not automatically restore the controls")
        scenario.onActivity { nativeView.player!!.play() }
        waitFor(scenario, "playback starts before checking skip restoration") { nativeView.player?.isPlaying == true }
        drag(overlay, .25f, .4f, .45f)
        waitFor(scenario, "playing drag restores playback") { !overlay.isSeekGestureActive && nativeView.player?.playWhenReady == true }
        Thread.sleep(350)
        assertHidden(scenario, "Restoring playback after a skip must keep controls hidden")
        scenario.onActivity { nativeView.player!!.pause() }
    }

    @Test fun lockedSingleTapRecoversAnAutoHiddenUnlockButtonWithoutShowingPlaybackControls() = withFixture(false) { scenario ->
        var view: PlayerView? = null
        var gesture: Media3GestureView? = null
        waitFor(scenario, "paused video ready before checking screen lock") { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            view = findView(root)
            gesture = findView(root)
            val player = view?.player
            player?.volume = 0f
            if (player != null && player.playbackState == Player.STATE_READY) player.pause()
            player != null && player.playbackState == Player.STATE_READY && !player.playWhenReady && (gesture?.width ?: 0) > 0
        }
        val nativeView = requireNotNull(view)
        val overlay = requireNotNull(gesture)
        assertHidden(scenario, "The lock button must start hidden with the playback controls")

        tap(overlay, .5f, .45f)
        waitFor(scenario, "single tap reveals the lock button") {
            nativeView.isControllerFullyVisible && it.findViewById<View>(R.id.player_lock).isShown
        }
        assertLocalNavigationVisibleAndDisabled(scenario)
        tapThroughActivityRoot(scenario, R.id.player_lock)
        waitFor(scenario, "screen lock hides playback controls but offers unlock") {
            overlay.locked && !nativeView.isControllerFullyVisible && it.findViewById<View>(R.id.player_lock).isShown
        }
        assertPlaybackControlsHidden(scenario, "Locking must leave only the unlock affordance visible")
        waitFor(scenario, "the unlock affordance automatically hides") {
            !it.findViewById<View>(R.id.player_lock).isShown
        }
        assertHidden(scenario, "The locked screen must become completely free of controls")

        val lockedPosition = main { nativeView.player!!.currentPosition }
        doubleTap(overlay, .75f, .45f)
        Thread.sleep(350)
        drag(overlay, .25f, .7f, .45f)
        Thread.sleep(350)
        assertHidden(scenario, "Locked skip gestures must not reveal any controls")
        scenario.onActivity { activity ->
            assertTrue("Skip gestures must not unlock the screen", overlay.locked)
            assertFalse("Skip gestures must not start a seek while locked", overlay.isSeekGestureActive)
            assertFalse("Locked gestures preserve paused playback", nativeView.player!!.playWhenReady)
            assertTrue("Locked gestures leave the timeline in place", abs(nativeView.player!!.currentPosition - lockedPosition) < 250)
            for (key in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                assertTrue("Locked media navigation must be consumed before PlayerView/system dispatch",
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
                assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
            }
        }
        assertPlaybackControlsHidden(scenario, "Locked media keys must not reveal the previous/next controls")

        tap(overlay, .5f, .45f)
        waitFor(scenario, "locked single tap recovers the hidden unlock button") {
            overlay.locked && it.findViewById<View>(R.id.player_lock).isShown
        }
        assertPlaybackControlsHidden(scenario, "A locked tap must reveal unlock without revealing playback controls")
        tapThroughActivityRoot(scenario, R.id.player_lock)
        waitFor(scenario, "the revealed unlock button restores the ordinary controls") {
            !overlay.locked && nativeView.isControllerFullyVisible && it.findViewById<View>(R.id.player_lock).isShown
        }
        assertLocalNavigationVisibleAndDisabled(scenario)
        scenario.onActivity {
            assertTrue("Unlock restores the play/pause control",
                nativeView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).isShown)
            assertFalse("Unlock preserves paused playback", nativeView.player!!.playWhenReady)
        }
    }

    @Test fun directSpeedAndConversionButtonsUsePersistedSpeedAndOnlyOriginalForALocalFile() = withFixture(false) { scenario ->
        var view: PlayerView? = null
        var gesture: Media3GestureView? = null
        waitFor(scenario, "paused video ready before opening playback choices") { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            view = findView(root)
            gesture = findView(root)
            val player = view?.player
            player?.volume = 0f
            if (player != null && player.playbackState == Player.STATE_READY) player.pause()
            player != null && player.playbackState == Player.STATE_READY && !player.playWhenReady && (gesture?.width ?: 0) > 0
        }
        val nativeView = requireNotNull(view)
        val overlay = requireNotNull(gesture)
        val settings = instrumentation.targetContext.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        scenario.onActivity { activity ->
            assertEquals("The fixture starts at normal playback speed", 1f, nativeView.player!!.playbackParameters.speed, .001f)
            assertEquals("1×", activity.findViewById<Button>(R.id.player_speed).text.toString())
        }
        tap(overlay, .5f, .45f)
        waitFor(scenario, "direct speed button is visible") {
            nativeView.isControllerFullyVisible && it.findViewById<View>(R.id.player_speed).isShown
        }
        tapThroughActivityRoot(scenario, R.id.player_speed)
        waitForWindow("the speed picker opens") { activeWindowChoiceLabels().isNotEmpty() }
        assertTrue("The speed picker must allow reaching 1.5× on a short landscape display", revealWindowChoice("1.5×"))
        assertTrue("The visible 1.5× option must accept a real accessibility click", clickWindowText("1.5×"))
        waitFor(scenario, "the selected speed reaches the MPV Player interface and its button") {
            abs((nativeView.player?.playbackParameters?.speed ?: 0f) - 1.5f) < .001f &&
                it.findViewById<Button>(R.id.player_speed).text.toString() == "1.5×"
        }
        assertEquals("The selected speed is persisted for future playback", 1.5f, settings.getFloat("playback_speed", 0f), .001f)
        scenario.onActivity { assertFalse("Changing speed preserves paused playback", nativeView.player!!.playWhenReady) }

        if (main { !nativeView.isControllerFullyVisible }) tap(overlay, .5f, .45f)
        waitFor(scenario, "direct conversion button is visible") {
            nativeView.isControllerFullyVisible && it.findViewById<View>(R.id.player_conversion).isShown
        }
        tapThroughActivityRoot(scenario, R.id.player_conversion)
        waitForWindow("a local file offers original playback without a server lookup") {
            activeWindowChoiceLabels().contains("원본")
        }
        assertEquals("A local file cannot offer server conversion or invented 720p/4K profiles",
            listOf("원본"), activeWindowChoiceLabels())
        if (instrumentation.targetContext.isTelevision()) {
            val root = instrumentation.uiAutomation.rootInActiveWindow!!
            val bounds = android.graphics.Rect()
            try { root.getBoundsInScreen(bounds) } finally { root.recycle() }
            assertTrue("The TV conversion picker should use a compact width", bounds.width() in
                1 until (instrumentation.targetContext.resources.displayMetrics.widthPixels * .6f).toInt())
        }
        assertTrue("The conversion picker can be dismissed without changing playback", clickWindowText("취소"))
        scenario.onActivity {
            assertFalse("Inspecting local conversion choices preserves paused playback", nativeView.player!!.playWhenReady)
            assertEquals(1.5f, nativeView.player!!.playbackParameters.speed, .001f)
        }
    }

    @Test fun longPressSubtitleDragMovesAndPersistsWithoutSeekingAndCancelRollsBack() = withFixture(true) { scenario ->
        var playerView: PlayerView? = null
        var gesture: Media3GestureView? = null
        var captions: PlexSubtitleOverlayView? = null
        waitFor(scenario, "embedded Korean caption and its drawn hitbox") { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            playerView = findView(root)
            gesture = findView(root)
            captions = findView(root)
            playerView?.player?.volume = 0f
            if (captions?.subtitleText == "한글 내장 자막 확인") playerView?.player?.pause()
            captions?.subtitleText == "한글 내장 자막 확인" && captions?.subtitleBounds()?.isEmpty == false &&
                playerView?.player?.playWhenReady == false
        }
        val overlay = requireNotNull(gesture)
        val subtitle = requireNotNull(captions)
        val nativeView = requireNotNull(playerView)
        val beforePosition = main { nativeView.player!!.currentPosition }
        val beforeX = main { subtitle.appearance.horizontalOffsetPercent }
        val beforeY = main { subtitle.appearance.verticalOffsetPercent }
        val point = main { subtitleCenter(subtitle, overlay) }
        longPressDrag(overlay, point, -overlay.width * .12f, -overlay.height * .13f)
        waitFor(scenario, "caption position changes after long-press drag") {
            subtitle.appearance.horizontalOffsetPercent != beforeX || subtitle.appearance.verticalOffsetPercent != beforeY
        }
        var committedX = 0
        var committedY = 0
        scenario.onActivity {
            committedX = subtitle.appearance.horizontalOffsetPercent
            committedY = subtitle.appearance.verticalOffsetPercent
            assertFalse("Dragging a caption must not enter timeline seeking", overlay.isSeekGestureActive)
            assertFalse("A subtitle drag preserves paused playback", nativeView.player!!.playWhenReady)
            assertTrue("A subtitle drag must leave the video timeline in place", abs(nativeView.player!!.currentPosition - beforePosition) < 250)
        }
        val prefs = instrumentation.targetContext.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        assertEquals("Dragged horizontal position is saved", committedX, prefs.getInt("subtitle_x", Int.MIN_VALUE))
        assertEquals("Dragged vertical position is saved", committedY, prefs.getInt("subtitle_y", Int.MIN_VALUE))
        waitFor(scenario, "moved caption redraws at its committed position") { !subtitle.subtitleBounds().isEmpty }
        val cancelPoint = main { subtitleCenter(subtitle, overlay) }
        longPressDrag(overlay, cancelPoint, overlay.width * .08f, -overlay.height * .08f, cancel = true) {
            assertTrue("The cancelled drag must first show a real preview", abs(subtitle.translationX) > 1 || abs(subtitle.translationY) > 1)
            assertFalse("Subtitle preview must not seek", overlay.isSeekGestureActive)
        }
        waitFor(scenario, "cancelled caption drag restores committed coordinates") {
            subtitle.appearance.horizontalOffsetPercent == committedX && subtitle.appearance.verticalOffsetPercent == committedY
        }
        assertEquals(committedX, prefs.getInt("subtitle_x", Int.MIN_VALUE))
        assertEquals(committedY, prefs.getInt("subtitle_y", Int.MIN_VALUE))
        scenario.onActivity { assertTrue(abs(nativeView.player!!.currentPosition - beforePosition) < 250) }
        assertHidden(scenario, "Caption dragging must not show the bottom controls")
    }

    private fun withFixture(subtitles: Boolean, markers: List<PlaybackMarker> = emptyList(), invalidMedia: Boolean = false,
        block: (ActivityScenario<NativePlayerActivity>) -> Unit) {
        val context = instrumentation.targetContext
        assertTrue("Instrumentation must target an isolated debug app",
            context.packageName.endsWith(".debug") || context.packageName.endsWith(".spoilerqa"))
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the device before checking real video gestures", keyguard.isKeyguardLocked)
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val settingKeys = setOf("subtitle_vertical", "subtitle_x", "subtitle_y", "subtitle_size", "subtitle_font",
            "subtitle_horizontal_writing_x", "subtitle_horizontal_writing_y", "subtitle_vertical_writing_x", "subtitle_vertical_writing_y",
            "playback_speed", "resize_mode", "resize_scale")
        val oldSettings = settingKeys.associateWith { settings.all[it] }
        val sessions = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val server = "https://gesture-controls-test.invalid"
        val oldRecord = sessions.getString(server, null)
        val oldLastServer = sessions.getString("last_server", null)
        val store = SessionStore(context)
        val asset = if (subtitles) "native-subtitle.mkv" else "gesture-av.mp4"
        val clip = File(context.cacheDir, "gesture-controls-$asset")
        try {
            settings.edit().putBoolean("subtitle_vertical", false).putInt("subtitle_x", 0).putInt("subtitle_y", 0)
                .putInt("subtitle_size", 100).putString("subtitle_font", "system").putFloat("playback_speed", 1f).commit()
            if (invalidMedia) clip.writeText("This synthetic fixture is not a playable media container.")
            else instrumentation.context.assets.open("media3/$asset").use { source -> clip.outputStream().use { source.copyTo(it) } }
            store.save(server, "synthetic-gesture-test-only", "테스트")
            val spec = PlaybackSpec(title = "제스처 회귀 검사", url = Uri.fromFile(clip).toString(),
                itemId = 1, libraryId = 1, partId = 1, viewItemId = 1, resumeMs = if (subtitles) 0 else 1_500,
                durationMs = 6_000, selectedSubtitleId = if (subtitles) 7 else null,
                subtitles = if (subtitles) listOf(SubtitleTrack(id = 7, label = "한국어", url = "$server/must-not-fetch",
                    mimeType = "application/x-subrip", source = "embedded", language = "kor", streamIndex = 2)) else emptyList(),
                markers = markers)
            val intent = Intent(context, NativePlayerActivity::class.java).putExtra("server", server).putExtra("spec", spec.toJson())
            ActivityScenario.launchActivityForResult<NativePlayerActivity>(intent).use { scenario ->
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                block(scenario)
            }
        } finally {
            store.clear(server)
            sessions.edit().apply {
                if (oldRecord == null) remove(server) else putString(server, oldRecord)
                if (oldLastServer == null) remove("last_server") else putString("last_server", oldLastServer)
            }.commit()
            restore(settings, oldSettings)
            clip.delete()
        }
    }

    private fun assertCompletedToCaller(scenario: ActivityScenario<NativePlayerActivity>, message: String, timeoutMs: Long = 8_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(message, Lifecycle.State.DESTROYED, scenario.state)
        val result = scenario.result
        assertEquals(message, Activity.RESULT_OK, result.resultCode)
        // Native finishes its own Activity; the caller retains its library/episode screen.
        // RESULT_OK without a data Intent is the completion contract.
        assertFalse("Completion must not request navigation to Home", result.resultData?.getBooleanExtra("return_home", false) == true)
        assertNull("Completion must not choose a replacement Activity", result.resultData?.component)
    }

    private fun hasVisibleText(view: View, text: String): Boolean {
        if (view is TextView && view.isShown && view.text.toString().contains(text)) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (hasVisibleText(view.getChildAt(index), text)) return true
        }
        return false
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, Any?>) {
        preferences.edit().apply {
            for ((key, value) in values) when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
            }
        }.commit()
    }

    private fun assertHidden(scenario: ActivityScenario<NativePlayerActivity>, message: String) = scenario.onActivity { activity ->
        val playerView = requireNotNull(findView<PlayerView>(activity.findViewById(android.R.id.content)))
        assertFalse(message, playerView.isControllerFullyVisible)
        assertFalse(message, playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_lock).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_previous).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_next).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_subtitles).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_subtitles).isEnabled)
    }

    private fun assertPlaybackControlsHidden(scenario: ActivityScenario<NativePlayerActivity>, message: String) = scenario.onActivity { activity ->
        val playerView = requireNotNull(findView<PlayerView>(activity.findViewById(android.R.id.content)))
        assertFalse(message, playerView.isControllerFullyVisible)
        assertFalse(message, playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_previous).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_next).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_subtitles).isShown)
        assertFalse(message, activity.findViewById<View>(R.id.player_subtitles).isEnabled)
    }

    private fun assertLocalNavigationVisibleAndDisabled(scenario: ActivityScenario<NativePlayerActivity>) =
        scenario.onActivity { activity ->
            for (id in listOf(R.id.player_previous, R.id.player_next)) {
                val icon = activity.findViewById<View>(id)
                assertTrue("A single tap reveals both media navigation icons", icon.isShown)
                assertFalse("A local fixture without a source catalog must not enable neighbor navigation", icon.isEnabled)
            }
            val subtitle = activity.findViewById<View>(R.id.player_subtitles)
            assertTrue("A single tap reveals direct subtitle selection", subtitle.isShown)
            assertTrue("Subtitle off must remain selectable for a file without subtitles", subtitle.isEnabled)
            assertEquals("자막 선택", subtitle.contentDescription.toString())
            assertEquals((48 * activity.resources.displayMetrics.density).roundToInt(), subtitle.height)
            if (activity.isTelevision()) assertTrue("TV remote navigation must include subtitle selection", subtitle.isFocusable)
        }

    /** Dispatch through the real root so the button must win hit testing above the gesture layer. */
    private fun tapThroughActivityRoot(scenario: ActivityScenario<NativePlayerActivity>, viewId: Int) {
        var target: Triple<View, Float, Float>? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val button = activity.findViewById<View>(viewId)
            assertTrue("The button must be shown before receiving a real touch", button.isShown)
            val buttonLocation = IntArray(2).also(button::getLocationOnScreen)
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            target = Triple(root, buttonLocation[0] - rootLocation[0] + button.width / 2f,
                buttonLocation[1] - rootLocation[1] + button.height / 2f)
        }
        val (root, x, y) = requireNotNull(target)
        val down = SystemClock.uptimeMillis()
        send(root, down, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(25)
        send(root, down, MotionEvent.ACTION_UP, x, y)
    }

    private fun subtitleCenter(subtitle: PlexSubtitleOverlayView, gesture: Media3GestureView): PointF {
        val bounds = subtitle.subtitleBounds()
        check(!bounds.isEmpty) { "The caption must have a drawn hitbox before dragging" }
        val subtitleLocation = IntArray(2).also(subtitle::getLocationOnScreen)
        val gestureLocation = IntArray(2).also(gesture::getLocationOnScreen)
        return PointF(bounds.centerX() + subtitleLocation[0] - gestureLocation[0], bounds.centerY() + subtitleLocation[1] - gestureLocation[1])
    }

    private fun tap(view: View, xFraction: Float, yFraction: Float) {
        val down = SystemClock.uptimeMillis()
        val x = view.width * xFraction
        val y = view.height * yFraction
        send(view, down, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(25)
        send(view, down, MotionEvent.ACTION_UP, x, y)
    }

    private fun doubleTap(view: View, xFraction: Float, yFraction: Float) {
        tap(view, xFraction, yFraction)
        Thread.sleep(80)
        tap(view, xFraction, yFraction)
    }

    private fun drag(view: View, from: Float, to: Float, yFraction: Float, midway: () -> Unit = {}) {
        val down = SystemClock.uptimeMillis()
        send(view, down, MotionEvent.ACTION_DOWN, view.width * from, view.height * yFraction)
        repeat(4) { index ->
            Thread.sleep(75)
            send(view, down, MotionEvent.ACTION_MOVE, view.width * (from + (to - from) * (index + 1) / 4), view.height * yFraction)
            if (index == 2) main(midway)
        }
        send(view, down, MotionEvent.ACTION_UP, view.width * to, view.height * yFraction)
    }

    private fun longPressDrag(view: View, start: PointF, dx: Float, dy: Float, cancel: Boolean = false, beforeEnd: () -> Unit = {}) {
        val down = SystemClock.uptimeMillis()
        send(view, down, MotionEvent.ACTION_DOWN, start.x, start.y)
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120)
        repeat(4) { index ->
            Thread.sleep(65)
            send(view, down, MotionEvent.ACTION_MOVE, start.x + dx * (index + 1) / 4, start.y + dy * (index + 1) / 4)
        }
        main(beforeEnd)
        send(view, down, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, start.x + dx, start.y + dy)
    }

    private fun send(view: View, down: Long, action: Int, x: Float, y: Float) = main {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun waitFor(scenario: ActivityScenario<NativePlayerActivity>, description: String, predicate: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        var diagnostics = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                ready = predicate(activity)
                val player = findView<PlayerView>(activity.findViewById(android.R.id.content))?.player
                diagnostics = "state=${player?.playbackState}, position=${player?.currentPosition}, error=${player?.playerError?.errorCodeName}"
            }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out: $description ($diagnostics)")
    }

    private fun waitForWindow(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        fail("Timed out: $description (choices=${activeWindowChoiceLabels()})")
    }

    /** Read the actual dialog rows, excluding its title and Cancel button. */
    private fun activeWindowChoiceLabels(): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isCheckable && !node.text.isNullOrBlank()) labels += node.text.toString()
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { collect(child) } finally { child.recycle() }
            }
        }
        try { collect(root) } finally { root.recycle() }
        return labels
    }

    private fun clickWindowText(text: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(text)
        var clicked = false
        try {
            for (node in matches) if (!clicked && node.text?.toString() == text) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) node.parent?.let { parent ->
                    try { clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { parent.recycle() }
                }
            }
        } finally {
            matches.forEach(AccessibilityNodeInfo::recycle)
            root.recycle()
        }
        return clicked
    }

    private fun revealWindowChoice(text: String): Boolean {
        repeat(12) {
            if (activeWindowChoiceLabels().contains(text)) return true
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                    try { if (scroll(child)) return true } finally { child.recycle() }
                }
                return false
            }
            val moved = try { scroll(root) } finally { root.recycle() }
            if (!moved) return false
            Thread.sleep(120)
        }
        return activeWindowChoiceLabels().contains(text)
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private inline fun <reified T : View> findView(view: View): T? = findViewOfType(view, T::class.java)
    private fun <T : View> findViewOfType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) findViewOfType(view.getChildAt(index), type)?.let { return it }
        return null
    }
}
