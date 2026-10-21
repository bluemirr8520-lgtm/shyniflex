package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NativeSubtitlePlaybackTest {
    @Test fun embeddedKoreanSubtitleRendersWithoutCallingServerExtraction() =
        verifyEmbeddedKoreanSubtitle(vertical = false)

    @Test fun verticalEmbeddedKoreanSubtitleUsesShyniCanvasWithoutServerExtraction() =
        verifyEmbeddedKoreanSubtitle(vertical = true)

    private fun verifyEmbeddedKoreanSubtitle(vertical: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the test device before checking visible native subtitles", keyguard.isKeyguardLocked)

        // Assets are installed in the test APK; copy the synthetic clip into the target app's cache.
        val directory = File(context.cacheDir, "media3-test-fixtures").apply { mkdirs() }
        val clip = File(directory, "native-subtitle.mkv")
        instrumentation.context.assets.open("media3/native-subtitle.mkv").use { source ->
            clip.outputStream().use { destination -> source.copyTo(destination) }
        }

        val attempts = AtomicInteger()
        val acceptFailure = AtomicReference<Throwable>()
        val extractionServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 200
        }
        val acceptThread = thread(name = "native-subtitle-extraction-probe", isDaemon = true) {
            while (!extractionServer.isClosed) {
                try {
                    extractionServer.accept().use { attempts.incrementAndGet() }
                } catch (_: SocketTimeoutException) {
                    // Wait for the test to close the listener.
                } catch (error: SocketException) {
                    if (!extractionServer.isClosed) acceptFailure.set(error)
                    break
                } catch (error: Throwable) {
                    acceptFailure.set(error)
                    break
                }
            }
        }

        val server = "https://127.0.0.1:1"
        val store = SessionStore(context)
        store.save(server, "fake-only-native-subtitle-fixture", "테스트")
        val spec = PlaybackSpec(
            title = "내장 자막 회귀 검사",
            url = Uri.fromFile(clip).toString(),
            itemId = 1,
            libraryId = 1,
            partId = 1,
            viewItemId = 1,
            durationMs = 6_000,
            subtitles = listOf(SubtitleTrack(
                id = 7,
                label = "한국어",
                url = "https://127.0.0.1:${extractionServer.localPort}/must-not-fetch",
                mimeType = "application/x-subrip",
                source = "embedded",
                language = "kor",
                streamIndex = 2,
            )),
            selectedSubtitleId = 7,
        )
        val intent = Intent(context, NativePlayerActivity::class.java)
            .putExtra("server", server).putExtra("spec", spec.toJson())
        val subtitlePrefs = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val hadVertical = subtitlePrefs.contains("subtitle_vertical")
        val previousVertical = subtitlePrefs.getBoolean("subtitle_vertical", false)
        subtitlePrefs.edit().putBoolean("subtitle_vertical", vertical).commit()
        try {
            ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    findView<PlayerView>(activity.findViewById(android.R.id.content))?.player?.volume = 0f
                }
                var selected = false
                var text = ""
                var diagnostics = ""
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (SystemClock.elapsedRealtime() < deadline && !(selected && text == FIXTURE_TEXT)) {
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<View>(android.R.id.content)
                        val player = findView<PlayerView>(content)?.player
                        selected = player?.currentTracks?.groups?.any { group ->
                            group.type == C.TRACK_TYPE_TEXT && group.isSelected
                        } == true
                        text = findView<PlexSubtitleOverlayView>(content)?.subtitleText.orEmpty()
                        diagnostics = "state=${player?.playbackState}, error=${player?.playerError?.errorCodeName}, " +
                            "selected=$selected, text=$text, extractionAttempts=${attempts.get()}"
                        if (selected && text == FIXTURE_TEXT) player?.pause()
                    }
                    if (!(selected && text == FIXTURE_TEXT)) Thread.sleep(75)
                }
                assertTrue("MPV must select the embedded text track: $diagnostics", selected)
                assertEquals("The Plex overlay must receive the embedded Korean cue: $diagnostics", FIXTURE_TEXT, text)
                // A failed HTTPS request still establishes TCP first, so the probe catches attempted extraction.
                Thread.sleep(200)
                acceptFailure.get()?.let { throw AssertionError("Subtitle extraction probe failed", it) }
                assertEquals("Embedded subtitles must not request server extraction", 0, attempts.get())

                scenario.onActivity { activity ->
                    val content = activity.findViewById<View>(android.R.id.content)
                    assertEquals("Keep the original Shyni writing direction", vertical,
                        findView<PlexSubtitleOverlayView>(content)?.appearance?.verticalWriting)
                    findView<PlayerView>(content)?.showController()
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(200)
                captureScreenshot(if (vertical) "mpv-vertical-subtitle-screen.png" else "native-subtitle-screen.png")

                verifyResizeIconCycleAndPersistence(scenario)

                scenario.onActivity { activity ->
                    activity.findViewById<ImageButton>(R.id.player_settings).performClick()
                }
                instrumentation.waitForIdleSync()
                val subtitleTab = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("자막")
                    ?.firstOrNull { it.text?.toString() == "자막" && it.isClickable }
                assertNotNull("Playback settings must expose the subtitle tab", subtitleTab)
                assertTrue("The subtitle settings tab must be selectable",
                    subtitleTab!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                Thread.sleep(200)
                captureScreenshot("native-settings-screen.png")
            }
        } finally {
            subtitlePrefs.edit().apply {
                if (hadVertical) putBoolean("subtitle_vertical", previousVertical) else remove("subtitle_vertical")
            }.commit()
            extractionServer.close()
            acceptThread.join(1_000)
            store.clear(server)
            clip.delete()
        }
    }

    @Test fun multipleKoreanTracksRenderTheSelectedFullCaptionWithoutExtractionDelay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the test device before checking visible native subtitles", keyguard.isKeyguardLocked)
        val directory = File(context.cacheDir, "media3-test-fixtures").apply { mkdirs() }
        val clip = File(directory, "native-subtitle-multiple.mkv")
        instrumentation.context.assets.open("media3/native-subtitle-multiple.mkv").use { source ->
            clip.outputStream().use { destination -> source.copyTo(destination) }
        }
        val server = "https://127.0.0.1:1"
        val store = SessionStore(context)
        store.save(server, "fake-only-multiple-subtitle-fixture", "테스트")
        try {
            ExtractionProbe().use { probe ->
                // The two Korean rows deliberately have the same generated label.
                // Global FFprobe indices are metadata, not player track identities.
                val tracks = listOf(
                    SubtitleTrack(7, "한국어", probe.url, source = "embedded", language = "kor",
                        streamIndex = 2, format = "subrip", forced = true, sdh = false),
                    SubtitleTrack(8, "한국어", probe.url, source = "embedded", language = "kor",
                        streamIndex = 3, format = "subrip", forced = false, sdh = false),
                    SubtitleTrack(9, "영어", probe.url, source = "embedded", language = "eng",
                        streamIndex = 4, format = "subrip", forced = false, sdh = false),
                )
                val spec = PlaybackSpec("여러 내장 자막 회귀 검사", Uri.fromFile(clip).toString(),
                    itemId = 1, libraryId = 1, partId = 1, viewItemId = 1,
                    durationMs = 6_000, subtitles = tracks, selectedSubtitleId = 8)
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", server).putExtra("spec", spec.toJson())
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        findView<PlayerView>(activity.findViewById(android.R.id.content))?.player?.volume = 0f
                    }
                    var text = ""
                    var selectedFullKorean = false
                    var nativeTextTracks = 0
                    var diagnostics = ""
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (SystemClock.elapsedRealtime() < deadline && !(selectedFullKorean && text == MULTIPLE_FIXTURE_TEXT)) {
                        scenario.onActivity { activity ->
                            val content = activity.findViewById<View>(android.R.id.content)
                            val player = findView<PlayerView>(content)?.player
                            val groups = player?.currentTracks?.groups.orEmpty().filter { it.type == C.TRACK_TYPE_TEXT }
                            nativeTextTracks = groups.sumOf { it.length }
                            selectedFullKorean = groups.any { group ->
                                (0 until group.length).any { index ->
                                    val format = group.getTrackFormat(index)
                                    group.isTrackSelected(index) && format.language in setOf("ko", "kor") &&
                                        format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 &&
                                        format.selectionFlags and C.SELECTION_FLAG_FORCED == 0 &&
                                        format.label == "Korean Main"
                                }
                            }
                            text = findView<PlexSubtitleOverlayView>(content)?.subtitleText.orEmpty()
                            diagnostics = "state=${player?.playbackState}, error=${player?.playerError?.errorCodeName}, " +
                                "tracks=$nativeTextTracks, fullKorean=$selectedFullKorean, text=$text, extractionAttempts=${probe.attempts.get()}"
                            if (selectedFullKorean && text == MULTIPLE_FIXTURE_TEXT) player?.pause()
                        }
                        if (!(selectedFullKorean && text == MULTIPLE_FIXTURE_TEXT)) Thread.sleep(75)
                    }
                    assertEquals("The fixture must expose both Korean tracks and the English track: $diagnostics", 3, nativeTextTracks)
                    assertTrue("MPV must select the normal Korean track, not forced-only or English: $diagnostics", selectedFullKorean)
                    assertEquals("The first full Korean caption must render within five seconds: $diagnostics", MULTIPLE_FIXTURE_TEXT, text)
                    probe.assertNoExtraction()
                }
            }
        } finally {
            store.clear(server)
            clip.delete()
        }
    }

    private fun verifyResizeIconCycleAndPersistence(scenario: ActivityScenario<NativePlayerActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val modes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
        val labels = listOf("화면 맞춤", "화면 채움", "화면 늘림")
        var initial = -1
        scenario.onActivity { activity ->
            initial = findView<PlayerView>(activity.findViewById(android.R.id.content))!!.resizeMode
        }
        assertTrue("Playback must start in a supported screen size", initial in modes)
        repeat(3) { step ->
            val expected = modes[(modes.indexOf(initial) + step + 1) % modes.size]
            scenario.onActivity { activity ->
                val button = activity.findViewById<ImageButton>(R.id.player_resize)
                assertTrue("The screen size icon must respond directly", button.performClick())
                assertEquals("The icon must cycle the video size without a dialog", expected,
                    findView<PlayerView>(activity.findViewById(android.R.id.content))!!.resizeMode)
                assertEquals(labels[modes.indexOf(expected)], button.contentDescription.toString())
                assertEquals("The selected size must be persisted", expected,
                    activity.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE).getInt("resize_mode", -1))
            }
            instrumentation.waitForIdleSync()
            assertTrue("The size icon must not open a choice dialog",
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("android:id/alertTitle").orEmpty().isEmpty())
            if (step == 0) {
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("Screen size must survive activity recreation", expected,
                        findView<PlayerView>(activity.findViewById(android.R.id.content))!!.resizeMode)
                }
            }
        }
    }

    private class ExtractionProbe : AutoCloseable {
        val attempts = AtomicInteger()
        private val failure = AtomicReference<Throwable>()
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        val url: String get() = "https://127.0.0.1:${server.localPort}/must-not-fetch"
        private val worker = thread(name = "multiple-subtitle-extraction-probe", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { attempts.incrementAndGet() }
                } catch (_: SocketTimeoutException) {
                    // Keep listening while the player resolves the selected subtitle.
                } catch (error: SocketException) {
                    if (!server.isClosed) failure.set(error)
                    break
                } catch (error: Throwable) {
                    failure.set(error)
                    break
                }
            }
        }
        fun assertNoExtraction() {
            Thread.sleep(200)
            failure.get()?.let { throw AssertionError("Subtitle extraction probe failed", it) }
            assertEquals("Supported Korean captions must not attempt server extraction", 0, attempts.get())
        }
        override fun close() { server.close(); worker.join(1_000) }
    }

    private fun captureScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("Device screenshot is unavailable: $name")
        try {
            File(directory, name).outputStream().use { output ->
                assertTrue("Device screenshot must be saved: $name",
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { screenshot.recycle() }
    }

    private inline fun <reified T : View> findView(root: View): T? {
        if (root is T) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = findViewOfType(root.getChildAt(index), T::class.java)
                if (child != null) return child
            }
        }
        return null
    }

    private fun <T : View> findViewOfType(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findViewOfType(root.getChildAt(index), type)?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val FIXTURE_TEXT = "한글 내장 자막 확인"
        const val MULTIPLE_FIXTURE_TEXT = "일반 한국어 자막 즉시 확인"
    }
}
