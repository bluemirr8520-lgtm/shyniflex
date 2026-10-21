package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalSubtitleBracketTest {
    @Test
    fun straightDoubleQuotesKeepOpeningAndClosingSidesAcrossLines() {
        val columns = "\"첫 줄\n둘째 줄\"".toVerticalSubtitleColumns()

        assertEquals("﹁", columns.first().first().text)
        assertEquals("﹂", columns.last().last().text)
    }

    @Test
    fun straightSingleQuotesKeepOpeningAndClosingSidesAcrossLines() {
        val columns = "'첫 줄\n둘째 줄'".toVerticalSubtitleColumns()

        assertEquals("﹃", columns.first().first().text)
        assertEquals("﹄", columns.last().last().text)
    }

    @Test
    fun fixedBracketPairsKeepTheirSidesAcrossLines() {
        val pairs = listOf(
            "(" to ")" to ("︵" to "︶"),
            "{" to "}" to ("︷" to "︸"),
            "〔" to "〕" to ("︹" to "︺"),
            "【" to "】" to ("︻" to "︼"),
            "《" to "》" to ("︽" to "︾"),
            "〈" to "〉" to ("︿" to "﹀"),
            "「" to "」" to ("﹁" to "﹂"),
            "『" to "』" to ("﹃" to "﹄"),
            "[" to "]" to ("﹇" to "﹈"),
        )

        pairs.forEach { (sourcePair, expectedPair) ->
            val columns = "${sourcePair.first}앞\n뒤${sourcePair.second}"
                .toVerticalSubtitleColumns()
            assertEquals(expectedPair.first, columns.first().first().text)
            assertEquals(expectedPair.second, columns.last().last().text)
        }
    }

    @Test
    fun multipleQuotedSentencesRestoreOpeningState() {
        val glyphs = "\"첫 줄\n둘째 줄\"\n\"셋째 줄\n넷째 줄\""
            .toVerticalSubtitleColumns()
            .flatten()
            .map { it.text }
            .filter { it == "﹁" || it == "﹂" }

        assertEquals(listOf("﹁", "﹂", "﹁", "﹂"), glyphs)
    }

    @Test
    fun blankLineStartsANewBracketContext() {
        val columns = "\"닫히지 않은 문장\n\n\"새 문장\""
            .toVerticalSubtitleColumns()

        assertEquals("﹁", columns[0].first().text)
        assertEquals("﹁", columns[1].first().text)
        assertEquals("﹂", columns[1].last().text)
    }

    @Test
    fun apostropheInsideWordDoesNotChangeSingleQuoteSide() {
        val columns = "'don't\nstop'".toVerticalSubtitleColumns()

        assertEquals("﹃", columns.first().first().text)
        assertEquals("﹄", columns.last().last().text)
    }
}
