package io.mirr.shynigsy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A real embedded mpv cue must reach the original Shyni vertical Canvas. */
@RunWith(AndroidJUnit4::class)
class MpvVerticalSubtitlePlaybackTest {
    @Test
    fun embeddedKoreanTextUsesTheExistingVerticalWritingRenderer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val hadVertical = prefs.contains("subtitle_vertical")
        val oldVertical = prefs.getBoolean("subtitle_vertical", false)
        val hadX = prefs.contains("subtitle_x")
        val oldX = prefs.getInt("subtitle_x", 0)
        val hadY = prefs.contains("subtitle_y")
        val oldY = prefs.getInt("subtitle_y", 0)
        prefs.edit().putBoolean("subtitle_vertical", true).putInt("subtitle_x", 0).putInt("subtitle_y", 0).commit()

        val directory = File(context.cacheDir, "mpv-vertical-fixture").apply { mkdirs() }
        val clip = File(directory, "native-subtitle.mkv")
        instrumentation.context.assets.open("media3/native-subtitle.mkv").use { source ->
            clip.outputStream().use { destination -> source.copyTo(destination) }
        }
        val server = "https://127.0.0.1:1"
        val store = SessionStore(context)
        store.save(server, "synthetic-vertical-subtitle-test", "테스트")
        try {
            val spec = PlaybackSpec(
                title = "MPV 세로 자막 검사",
                url = Uri.fromFile(clip).toString(),
                itemId = 1, libraryId = 1, partId = 1, viewItemId = 1,
                durationMs = 6_000,
                subtitles = listOf(SubtitleTrack(
                    id = 7, label = "한국어", url = "$server/must-not-fetch",
                    mimeType = "application/x-subrip", source = "embedded", language = "kor", streamIndex = 2,
                )),
                selectedSubtitleId = 7,
            )
            val intent = Intent(context, NativePlayerActivity::class.java)
                .putExtra("server", server).putExtra("spec", spec.toJson())
            ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                var text = ""
                var selected = false
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < deadline && !(selected && text == "한글 내장 자막 확인")) {
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<View>(android.R.id.content)
                        val player = findView<PlayerView>(content)?.player
                        player?.volume = 0f
                        selected = player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } == true
                        text = findView<PlexSubtitleOverlayView>(content)?.subtitleText.orEmpty()
                        if (selected && text == "한글 내장 자막 확인") player?.pause()
                    }
                    if (!(selected && text == "한글 내장 자막 확인")) Thread.sleep(75)
                }
                assertTrue("MPV must select the embedded Korean text track", selected)
                assertEquals("Embedded text must reach Shyni's Canvas", "한글 내장 자막 확인", text)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val overlay = requireNotNull(findView<PlexSubtitleOverlayView>(activity.findViewById(android.R.id.content)))
                    assertTrue("The original vertical writing option must stay enabled", overlay.appearance.verticalWriting)
                    assertEquals(View.VISIBLE, overlay.visibility)
                    assertTrue(overlay.width > 0 && overlay.height > 0)
                    val image = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
                    try {
                        overlay.draw(Canvas(image))
                        var left = image.width
                        var right = -1
                        var top = image.height
                        var bottom = -1
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        for (y in 0 until image.height) for (x in 0 until image.width) {
                            if (pixels[y * image.width + x].ushr(24) > 0) {
                                left = minOf(left, x); right = maxOf(right, x)
                                top = minOf(top, y); bottom = maxOf(bottom, y)
                            }
                        }
                        assertTrue("Vertical glyphs must actually be drawn", right >= left && bottom >= top)
                        assertTrue("Vertical text must flow downward", bottom - top > right - left)
                        assertTrue("The default vertical column stays on the right", left > image.width / 2)
                        val file = File(context.getExternalFilesDir(null), "mpv-shyni-vertical-subtitles.png")
                        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { image.recycle() }
                }
            }
        } finally {
            prefs.edit().apply {
                if (hadVertical) putBoolean("subtitle_vertical", oldVertical) else remove("subtitle_vertical")
                if (hadX) putInt("subtitle_x", oldX) else remove("subtitle_x")
                if (hadY) putInt("subtitle_y", oldY) else remove("subtitle_y")
            }.commit()
            store.clear(server)
            clip.delete()
        }
    }

    private inline fun <reified T : View> findView(view: View): T? {
        return findViewOfType(view, T::class.java)
    }

    private fun <T : View> findViewOfType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findViewOfType(view.getChildAt(index), type)?.let { return it }
        }
        return null
    }
}
