package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalSubtitleTypographyTest {
    @Test
    fun latinAndDigitsAreUprightAndFullwidthFormsMatchAscii() {
        val cells = "한AＢｃ12３글".toVerticalSubtitleGlyphs()
        assertEquals(listOf("한", "A", "B", "c", "1", "2", "3", "글"), cells.map { it.text })
        assertTrue(cells.none { it.rotate })
        assertTrue(cells.subList(1, 7).all { it.centerInCell })
    }

    @Test
    fun accentsAndInWordApostrophesStayInTheirLetterCell() {
        val cells = "Cafe\u0301 don't O’Neil".toVerticalSubtitleGlyphs()
        assertEquals(
            listOf("C", "a", "f", "e\u0301", " ", "d", "o", "n'", "t", " ", "O’", "N", "e", "i", "l"),
            cells.map { it.text },
        )
        assertTrue(cells.none { it.rotate })
        assertEquals(1f, cells.first { it.text == "e\u0301" }.advanceScale, 0f)
    }

    @Test
    fun quoteRolesSurviveSourceLineBreaksAndAutomaticWrapping() {
        val columns = "\"첫째 줄\n둘째 줄\"".toVerticalSubtitleColumns()
            .wrapVerticalSubtitleColumns(4f)
        val text = columns.flatten().filterNot { it.spacer }.joinToString("") { it.text }
        assertEquals("﹁첫째줄둘째줄﹂", text)
        assertEquals("﹁", columns.first().first().text)
        assertEquals("﹂", columns.last().last().text)
    }

    @Test
    fun blankLineResetsQuotesForAnIndependentCue() {
        val columns = "\"첫말\n\n\"다음말".toVerticalSubtitleColumns()
        assertEquals(listOf("﹁", "﹁"), columns.map { it.first().text })
    }

    @Test
    fun trailingUnmatchedQuoteUsesClosingContext() {
        assertEquals("﹂", "끝난 말\"".toVerticalSubtitleGlyphs().last().text)
        assertEquals("﹄", "끝난 말'".toVerticalSubtitleGlyphs().last().text)
        assertEquals("﹁", "말: \"시작".toVerticalSubtitleGlyphs().first { it.text == "﹁" }.text)
    }

    @Test
    fun pairedBracketsKeepTheirDistinctFormsAcrossLines() {
        val cells = "(첫줄 [내용\n다음줄]) 〖덧말〗".toVerticalSubtitleColumns().flatten()
        assertEquals(
            listOf("︵", "﹇", "﹈", "︶", "︗", "︘"),
            cells.map { it.text }.filter { it in setOf("︵", "﹇", "﹈", "︶", "︗", "︘") },
        )
        assertTrue(cells.filter { it.text in setOf("︵", "﹇", "﹈", "︶") }.all { it.centerInCell })
    }

    @Test
    fun wordWrappingRetainsWordsAndDropsBoundaryWhitespace() {
        val columns = "  가나다   라마바  ".toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(4f)
        assertEquals(listOf("가나다", "라마바"), columns.map { it.joinToString("") { glyph -> glyph.text } })
        assertTrue(columns.all { it.verticalAdvance() <= 4f })
    }

    @Test
    fun longWordsAvoidAnOpeningBracketAtTheEndOfAColumn() {
        val columns = "가나다(라마)".toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(4f)
        assertEquals(listOf("가나다", "︵라마︶"), columns.map { it.joinToString("") { cell -> cell.text } })
    }

    @Test
    fun longWordsKeepClosingBracketsWithTheirPrecedingLetter() {
        val columns = "(가나다라)".toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(4f)
        assertEquals(listOf("︵가나다", "라︶"), columns.map { it.joinToString("") { cell -> cell.text } })
        val closingAtBoundary = "가나다라)".toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(4f)
        assertEquals(listOf("가나다", "라︶"), closingAtBoundary.map { it.joinToString("") { cell -> cell.text } })
    }

    @Test
    fun longEnglishWordsWrapBetweenUprightClusters() {
        val columns = "ABCDEFGHI".toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(4f)
        assertEquals(listOf("ABCD", "EFGH", "I"), columns.map { it.joinToString("") { cell -> cell.text } })
        assertTrue(columns.flatten().none { it.rotate })
    }

    @Test
    fun ellipsesAndSymbolsRetainVerticalPresentation() {
        val cells = "... … / % & +".toVerticalSubtitleGlyphs().filterNot { it.spacer }
        assertEquals(listOf("︙", "︙", "/", "%", "&", "+"), cells.map { it.text })
        assertFalse(cells.first().rotate)
        assertTrue(cells.drop(2).all { it.rotate && it.centerInCell })
    }

    @Test
    fun punctuationOnlyAndEmptyInputAlwaysTerminateWithoutLosingCells() {
        val source = "((((()))))"
        val columns = source.toVerticalSubtitleColumns().wrapVerticalSubtitleColumns(4f)
        assertEquals(source.length, columns.sumOf { it.size })
        assertTrue(columns.all { it.isNotEmpty() && it.verticalAdvance() <= 4f })
        assertTrue(" \n\n".toVerticalSubtitleColumns().isEmpty())
    }
}
