package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalSubtitleSpacingTest {
    @Test fun defaultSpacingPreservesTheExistingAdvanceAtEveryFontAndDensity() {
        for (density in listOf(.75f, 1f, 1.5f, 2.625f, 4f)) {
            for (fontScale in listOf(.5f, 1f, 1.3f, 2f)) {
                val previous = 28.5f * density * fontScale
                assertEquals(previous.toRawBits(), verticalSubtitleGlyphAdvancePx(previous, 100).toRawBits())
            }
        }
    }

    @Test fun spacingIsIndependentFromFontSizeAndRejectsOutOfRangePreferences() {
        assertEquals(14.25f, verticalSubtitleGlyphAdvancePx(28.5f, 50), 0f)
        assertEquals(57f, verticalSubtitleGlyphAdvancePx(28.5f, 200), 0f)
        assertEquals(50, normalizedVerticalSubtitleSpacingPercent(Int.MIN_VALUE))
        assertEquals(200, normalizedVerticalSubtitleSpacingPercent(Int.MAX_VALUE))
        assertEquals(130, normalizedVerticalSubtitleSpacingPercent(130))
        assertEquals(verticalSubtitleGlyphAdvancePx(28.5f, 50), verticalSubtitleGlyphAdvancePx(28.5f, -1), 0f)
        assertEquals(verticalSubtitleGlyphAdvancePx(28.5f, 200), verticalSubtitleGlyphAdvancePx(28.5f, 201), 0f)
    }

    @Test fun increasingSpacingWrapsLongTextEarlierWithoutChangingOrLosingGlyphs() {
        val source = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toVerticalSubtitleColumns()
        fun wrapped(spacing: Int) = source.wrapVerticalSubtitleColumns(
            500f / verticalSubtitleGlyphAdvancePx(28.5f, spacing))
        val compact = wrapped(50)
        val normal = wrapped(100)
        val spacious = wrapped(200)
        assertTrue(compact.size < normal.size)
        assertTrue(normal.size < spacious.size)
        for ((spacing, columns) in listOf(50 to compact, 100 to normal, 200 to spacious)) {
            assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", columns.flatten().joinToString("") { it.text })
            val advance = verticalSubtitleGlyphAdvancePx(28.5f, spacing)
            assertTrue(columns.all { it.verticalAdvance() * advance <= 500f })
        }
    }

    @Test fun punctuationWordsAndAccentsRetainTheirCellProportionsAtEverySpacing() {
        val glyphs = "e\u0301 ... / 다음".toVerticalSubtitleGlyphs()
        assertEquals("e\u0301", glyphs.first().text)
        assertTrue(glyphs.first { it.text == "/" }.rotate)
        assertEquals(.82f, glyphs.first { it.text == "︙" }.advanceScale, 0f)
        assertEquals(.84f, glyphs.first { it.text == "/" }.advanceScale, 0f)
        assertEquals(.22f, glyphs.first { it.spacer }.advanceScale, 0f)
        for (spacing in listOf(50, 100, 130, 200)) {
            val advance = verticalSubtitleGlyphAdvancePx(28.5f, spacing)
            assertEquals(glyphs.verticalAdvance() * advance,
                glyphs.sumOf { (it.advanceScale * advance).toDouble() }.toFloat(), .0001f)
        }
    }

    @Test fun spacingAwareWrappingStillKeepsClosingBracketsWithTheirText() {
        val advance = verticalSubtitleGlyphAdvancePx(28.5f, 150)
        val height = advance * 4f
        val columns = "가나다라)".toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(height / advance)
        assertEquals(listOf("가나다", "라︶"), columns.map { it.joinToString("") { glyph -> glyph.text } })
        assertTrue(columns.all { it.verticalAdvance() * advance <= height })
    }
}
