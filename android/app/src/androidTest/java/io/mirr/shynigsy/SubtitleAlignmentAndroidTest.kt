package io.mirr.shynigsy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.subtitles.PlexSubtitleAppearance
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import io.mirr.shynigsy.player.subtitles.SubtitleHorizontalAlignment
import io.mirr.shynigsy.player.subtitles.SubtitleVerticalAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SubtitleAlignmentAndroidTest {
    @Before fun isolatedPackage() {
        assertEquals("io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun horizontalWritingAlignsMultipleLinesAndPreservesOffsets() = onMain { context ->
        val overlay = overlay(context)
        val base = PlexSubtitleAppearance()
        val original = draw(overlay, context, base)
        val explicitDefault = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.CENTER,
            verticalAlignment = SubtitleVerticalAlignment.BOTTOM))
        assertEquals(original.bounds, explicitDefault.bounds)
        assertEquals(original.pixelHash, explicitDefault.pixelHash)
        val left = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.LEFT))
        val center = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.CENTER))
        val right = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.RIGHT))
        assertTrue(left.bounds.centerX() < center.bounds.centerX() && center.bounds.centerX() < right.bounds.centerX())
        val top = draw(overlay, context, base.copy(verticalAlignment = SubtitleVerticalAlignment.TOP))
        val middle = draw(overlay, context, base.copy(verticalAlignment = SubtitleVerticalAlignment.CENTER))
        val bottom = draw(overlay, context, base.copy(verticalAlignment = SubtitleVerticalAlignment.BOTTOM))
        assertTrue(top.bounds.centerY() < middle.bounds.centerY() && middle.bounds.centerY() < bottom.bounds.centerY())
        val moved = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.LEFT,
            verticalAlignment = SubtitleVerticalAlignment.TOP, horizontalOffsetPercent = 10, verticalOffsetPercent = 10))
        val anchor = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.LEFT,
            verticalAlignment = SubtitleVerticalAlignment.TOP))
        assertEquals(overlay.width * .1f, moved.bounds.centerX() - anchor.bounds.centerX(), 1f)
        assertEquals(overlay.height * .1f, moved.bounds.centerY() - anchor.bounds.centerY(), 1f)
        listOf(left, center, right, top, middle, bottom, moved).forEach(::assertContainsInk)
    }

    @Test fun verticalWritingAlignsColumnsAndKeepsExistingDefault() = onMain { context ->
        val overlay = overlay(context)
        val base = PlexSubtitleAppearance(verticalWriting = true)
        val original = draw(overlay, context, base)
        val explicitDefault = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.RIGHT,
            verticalAlignment = SubtitleVerticalAlignment.CENTER))
        assertEquals(original.bounds, explicitDefault.bounds)
        assertEquals(original.pixelHash, explicitDefault.pixelHash)
        val left = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.LEFT))
        val center = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.CENTER))
        val right = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.RIGHT))
        assertTrue(left.bounds.centerX() < center.bounds.centerX() && center.bounds.centerX() < right.bounds.centerX())
        val top = draw(overlay, context, base.copy(verticalAlignment = SubtitleVerticalAlignment.TOP))
        val middle = draw(overlay, context, base.copy(verticalAlignment = SubtitleVerticalAlignment.CENTER))
        val bottom = draw(overlay, context, base.copy(verticalAlignment = SubtitleVerticalAlignment.BOTTOM))
        assertTrue(top.bounds.centerY() < middle.bounds.centerY() && middle.bounds.centerY() < bottom.bounds.centerY())
        val moved = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.LEFT,
            verticalAlignment = SubtitleVerticalAlignment.TOP, horizontalOffsetPercent = 10, verticalOffsetPercent = 10))
        val anchor = draw(overlay, context, base.copy(horizontalAlignment = SubtitleHorizontalAlignment.LEFT,
            verticalAlignment = SubtitleVerticalAlignment.TOP))
        assertEquals(overlay.width * .1f, moved.bounds.centerX() - anchor.bounds.centerX(), 1f)
        assertEquals(overlay.height * .1f, moved.bounds.centerY() - anchor.bounds.centerY(), 1f)
        listOf(left, center, right, top, middle, bottom, moved).forEach(::assertContainsInk)
    }

    private fun overlay(context: Context) = PlexSubtitleOverlayView(context).apply { subtitleText = "긴 자막입니다\n짧은 줄" }

    private fun draw(overlay: PlexSubtitleOverlayView, context: Context, appearance: PlexSubtitleAppearance): Snapshot {
        val density = context.resources.displayMetrics.density
        val width = (360 * density).roundToInt()
        val height = (600 * density).roundToInt()
        overlay.appearance = appearance
        overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            overlay.draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width; var top = height; var right = -1; var bottom = -1
            for (y in 0 until height) for (x in 0 until width) if (pixels[y * width + x].ushr(24) > 0) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
            assertTrue("Subtitle glyphs must be visible", right >= left && bottom >= top)
            return Snapshot(Rect(left, top, right + 1, bottom + 1), overlay.subtitleBounds(), pixels.contentHashCode())
        } finally { bitmap.recycle() }
    }

    private fun assertContainsInk(snapshot: Snapshot) {
        assertFalse(snapshot.bounds.isEmpty)
        assertTrue(snapshot.bounds.left <= snapshot.ink.left + 2f)
        assertTrue(snapshot.bounds.top <= snapshot.ink.top + 2f)
        assertTrue(snapshot.bounds.right >= snapshot.ink.right - 2f)
        assertTrue(snapshot.bounds.bottom >= snapshot.ink.bottom - 2f)
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }

    private data class Snapshot(val ink: Rect, val bounds: RectF, val pixelHash: Int)
}
