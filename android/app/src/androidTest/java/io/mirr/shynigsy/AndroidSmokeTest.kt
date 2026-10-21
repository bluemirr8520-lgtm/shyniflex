package io.mirr.shynigsy

import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.subtitles.PlexSubtitleAppearance
import io.mirr.shynigsy.player.subtitles.PlexSubtitleFonts
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.mirr.shynigsy.player.mpv.MpvPlayer
import `is`.xyz.mpv.MPVLib
import androidx.media3.ui.CaptionStyleCompat
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Before fun keepTestActivityAwake() {
        screen.runOnUiThread {
            screen.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun loginScreenRendersAndScreenshot() {
        val keyguard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("Unlock the test device before checking visible Compose UI", keyguard.isKeyguardLocked)
        // The login form scrolls on short or landscape viewports. Check that every
        // control is reachable instead of requiring the whole form to fit at once.
        listOf("서버 주소", "아이디", "비밀번호", "로그인").forEach { label ->
            screen.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
            screen.onNodeWithText(label).assertIsDisplayed()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "login-screen.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun mpvEngineLoadsNativeLibrary() {
        screen.runOnUiThread {
            val player = MpvPlayer(screen.activity, emptyMap())
            try {
                assertFalse("The bundled MPV JNI must report its version", MPVLib.getPropertyString("mpv-version").isNullOrBlank())
            } finally { player.release() }
        }
    }

    @Test fun tokenIsEncryptedWithAndroidKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        val server = "https://smoke-test.invalid"
        val token = "fake-only-test-session-7B19"
        try {
            store.save(server, token, "테스트")
            assertEquals(token, store.load(server)?.token)
            val preferences = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
            assertFalse(preferences.getString(server, "")!!.contains(token))
        } finally { store.clear(server) }
    }

    @Test fun systemFontRendersKoreanAndEnglishInBothWritingDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            assertEquals(listOf("system"), PlexSubtitleFonts.keys)
            PlexSubtitleFonts.keys.forEach { fontKey ->
                val font = PlexSubtitleFonts.typeface(context, fontKey)
                assertNotNull("System font must load: $fontKey", font)
                listOf(false, true).forEach { vertical ->
                    val width = 1280
                    val height = 720
                    val overlay = PlexSubtitleOverlayView(context).apply {
                        subtitleTypeface = font
                        subtitleText = "한글 자막\nHello World"
                        appearance = PlexSubtitleAppearance(verticalWriting = vertical,
                            foregroundColor = Color.WHITE, backgroundColor = Color.TRANSPARENT,
                            edgeType = CaptionStyleCompat.EDGE_TYPE_NONE)
                        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        layout(0, 0, width, height)
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        overlay.draw(Canvas(bitmap))
                        val pixels = IntArray(width * height)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        var visiblePixels = 0
                        var left = width
                        var top = height
                        var right = -1
                        var bottom = -1
                        pixels.forEachIndexed { index, pixel ->
                            if (Color.alpha(pixel) > 0) {
                                visiblePixels++
                                val x = index % width
                                val y = index / width
                                left = minOf(left, x); right = maxOf(right, x)
                                top = minOf(top, y); bottom = maxOf(bottom, y)
                            }
                        }
                        val label = "$fontKey ${if (vertical) "vertical" else "horizontal"}"
                        assertTrue("$label must draw real glyph pixels", visiblePixels > 80)
                        assertTrue("$label must keep its transparent video background", visiblePixels < width * height / 4)
                        assertTrue("$label glyphs must fit inside the viewport: $left,$top,$right,$bottom",
                            left > 0 && top > 0 && right < width - 1 && bottom < height - 1)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test fun nativeMedia3ScreenShowsRecoverableError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        val server = "https://127.0.0.1:1"
        store.save(server, "fake-only-local-error-fixture", "테스트")
        val spec = PlaybackSpec(title = "ShyniFlix 화면 확인", url = "$server/non-existing-smoke-video.mp4",
            itemId = 1, libraryId = 1, partId = 1, viewItemId = 1)
        val intent = Intent(context, NativePlayerActivity::class.java)
            .putExtra("server", server).putExtra("spec", spec.toJson())
        try {
            ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                var found = false
                repeat(40) {
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        fun hasError(view: android.view.View): Boolean {
                            if (view is android.widget.TextView && view.text.contains("영상을 재생할 수 없습니다")) return true
                            return view is android.view.ViewGroup && (0 until view.childCount).any { hasError(view.getChildAt(it)) }
                        }
                        found = hasError(content)
                    }
                    if (!found) Thread.sleep(250)
                }
                assertTrue("Native Media3 screen must show a recoverable error instead of crashing", found)
            }
        } finally { store.clear(server) }
    }
}
