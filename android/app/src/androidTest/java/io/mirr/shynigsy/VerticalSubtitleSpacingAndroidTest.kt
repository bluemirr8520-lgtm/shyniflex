package io.mirr.shynigsy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.subtitles.PlexSubtitleAppearance
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Synthetic Canvas regressions; no account, player, files, or device settings are used. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VerticalSubtitleSpacingAndroidTest {
    @Before
    fun requireIsolatedTargetPackage() {
        assertEquals("Run only against the isolated fixture app", "io.mirr.shyniflex.spoilerqa",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test
    fun verticalSpacingChangesDrawnHeightAndKeepsDragBoundsAroundTheInk(): Unit = onMain { context ->
        val overlay = overlay(context)
        val narrow = draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true, verticalSpacingPercent = 50))
        val normal = draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true))
        val wide = draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true, verticalSpacingPercent = 200))
        assertTrue("Narrow spacing must shorten the actual glyph column", narrow.ink.height() < normal.ink.height())
        assertTrue("Wide spacing must lengthen the actual glyph column", normal.ink.height() < wide.ink.height())
        assertEquals("Spacing must not change glyph width", narrow.ink.width().toFloat(), wide.ink.width().toFloat(), 1f)
        listOf(narrow, normal, wide).forEach(::assertInkInsideDragBounds)

        val moved = draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true, verticalSpacingPercent = 200,
            horizontalOffsetPercent = 10, verticalOffsetPercent = -10))
        assertEquals(overlay.width * .1f, moved.bounds.centerX() - wide.bounds.centerX(), 1f)
        assertEquals(-overlay.height * .1f, moved.bounds.centerY() - wide.bounds.centerY(), 1f)
        assertInkInsideDragBounds(moved)
    }

    @Test
    fun returningToDefaultRestoresTheExactVerticalRaster(): Unit = onMain { context ->
        val overlay = overlay(context)
        val initial = draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true))
        draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true, verticalSpacingPercent = 200))
        val restored = draw(overlay, context, PlexSubtitleAppearance(verticalWriting = true, verticalSpacingPercent = 100))
        assertEquals(initial.ink, restored.ink)
        assertEquals(initial.bounds, restored.bounds)
        assertEquals("Returning to 100% must invalidate the widened layout", initial.pixelHash, restored.pixelHash)
    }

    @Test
    fun verticalSpacingDoesNotChangeTheHorizontalRaster(): Unit = onMain { context ->
        val overlay = overlay(context)
        val appearance = PlexSubtitleAppearance(verticalSpacingPercent = 50, sizePercent = 120)
        val narrow = draw(overlay, context, appearance)
        val wide = draw(overlay, context, appearance.copy(verticalSpacingPercent = 200))
        assertEquals(narrow.ink, wide.ink)
        assertEquals(narrow.bounds, wide.bounds)
        assertEquals("Horizontal glyphs and placement must remain identical", narrow.pixelHash, wide.pixelHash)
    }

    @Test
    fun fontSizeAndWritingModeChangesRetainTheSelectedVerticalSpacing(): Unit = onMain { context ->
        val overlay = overlay(context)
        overlay.subtitleTypeface = Typeface.MONOSPACE
        val selected = PlexSubtitleAppearance(verticalWriting = true, verticalSpacingPercent = 180)
        val enlarged = selected.copy(sizePercent = 120)
        val wide = draw(overlay, context, enlarged)
        val normal = draw(overlay, context, enlarged.copy(verticalSpacingPercent = 100))
        assertTrue("A font and size change must still honor selected spacing", wide.ink.height() > normal.ink.height())
        draw(overlay, context, enlarged.copy(verticalWriting = false))
        val restored = draw(overlay, context, enlarged.copy(verticalWriting = false).copy(verticalWriting = true))
        assertEquals(180, overlay.appearance.verticalSpacingPercent)
        assertEquals(wide.ink, restored.ink)
        assertEquals(wide.pixelHash, restored.pixelHash)
        assertInkInsideDragBounds(restored)
    }

    private fun overlay(context: Context) = PlexSubtitleOverlayView(context).apply {
        subtitleText = "가나다라"
    }

    private fun draw(overlay: PlexSubtitleOverlayView, context: Context,
        appearance: PlexSubtitleAppearance): Snapshot {
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
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) for (x in 0 until width) {
                if (pixels[y * width + x].ushr(24) > 0) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("The native Canvas must draw subtitle glyphs", right >= left && bottom >= top)
            return Snapshot(Rect(left, top, right + 1, bottom + 1), overlay.subtitleBounds(), pixels.contentHashCode())
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertInkInsideDragBounds(snapshot: Snapshot) {
        assertFalse("Drawn subtitles must expose drag bounds", snapshot.bounds.isEmpty)
        val tolerance = 2f
        assertTrue(snapshot.bounds.left <= snapshot.ink.left + tolerance)
        assertTrue(snapshot.bounds.top <= snapshot.ink.top + tolerance)
        assertTrue(snapshot.bounds.right >= snapshot.ink.right - tolerance)
        assertTrue(snapshot.bounds.bottom >= snapshot.ink.bottom - tolerance)
    }

    private fun onMain(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { block(instrumentation.targetContext) }
    }

    private data class Snapshot(val ink: Rect, val bounds: RectF, val pixelHash: Int)
}
