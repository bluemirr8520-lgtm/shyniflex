package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleAlignmentLayoutTest {
    @Test fun alignmentUsesAvailableBoundsAndPreservesDragOffsets() {
        val left = subtitleHorizontalStart(20f, 980f, 200f, SubtitleHorizontalAlignment.LEFT,
            SubtitleHorizontalAlignment.CENTER, 30f)
        val center = subtitleHorizontalStart(20f, 980f, 200f, SubtitleHorizontalAlignment.CENTER,
            SubtitleHorizontalAlignment.CENTER, 30f)
        val right = subtitleHorizontalStart(20f, 980f, 200f, SubtitleHorizontalAlignment.RIGHT,
            SubtitleHorizontalAlignment.CENTER, 30f)
        assertEquals(50f, left, .001f)
        assertEquals(430f, center, .001f)
        assertEquals(810f, right, .001f)
        assertEquals(12f, subtitleVerticalStart(10f, 590f, 100f, SubtitleVerticalAlignment.TOP,
            SubtitleVerticalAlignment.BOTTOM, 2f), .001f)
        assertEquals(252f, subtitleVerticalStart(10f, 590f, 100f, SubtitleVerticalAlignment.CENTER,
            SubtitleVerticalAlignment.BOTTOM, 2f), .001f)
        assertEquals(492f, subtitleVerticalStart(10f, 590f, 100f, SubtitleVerticalAlignment.BOTTOM,
            SubtitleVerticalAlignment.BOTTOM, 2f), .001f)
    }

    @Test fun defaultUsesEachWritingModesOriginalPlacement() {
        assertEquals(400f, subtitleHorizontalStart(0f, 1000f, 200f, SubtitleHorizontalAlignment.DEFAULT,
            SubtitleHorizontalAlignment.CENTER, 0f), .001f)
        assertEquals(800f, subtitleHorizontalStart(0f, 1000f, 200f, SubtitleHorizontalAlignment.DEFAULT,
            SubtitleHorizontalAlignment.RIGHT, 0f), .001f)
        assertEquals(500f, subtitleVerticalStart(0f, 600f, 100f, SubtitleVerticalAlignment.DEFAULT,
            SubtitleVerticalAlignment.BOTTOM, 0f), .001f)
        assertEquals(250f, subtitleVerticalStart(0f, 600f, 100f, SubtitleVerticalAlignment.DEFAULT,
            SubtitleVerticalAlignment.CENTER, 0f), .001f)
    }
}
