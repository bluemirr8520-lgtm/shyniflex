package io.mirr.shynigsy.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRelevanceTest {
    private fun row(title: String, original: String? = null, sort: String? = null, code: String? = null) =
        JSONObject().put("title", title).put("original_title", original).put("sort_title", sort).put("metadata_code", code)
    private fun names(rows: List<JSONObject>) = rows.map { it.getString("title") }

    @Test fun directMatchesHideUnrelatedRowsAndRankExactBeforePrefix() {
        val rows = listOf(row("전혀 다른 작품"), row("구가의 서 특별편"), row("구가의 서"))
        assertEquals(listOf("구가의 서", "구가의 서 특별편"), names(rankSearchRows(rows, " 구가의 서 ")))
    }

    @Test fun koreanSpacingAndPunctuationNormalizeToTheSameTitle() {
        assertEquals(listOf("구가의 서"), names(rankSearchRows(listOf(row("다른 작품"), row("구가의 서")), "구가의서")))
    }

    @Test fun koreanInitialsFindTheTitleWithoutDiscardingOtherInitialMatches() {
        val rows = listOf(row("다른 작품"), row("구가의 서"), row("그곳의 삶"))
        assertEquals(listOf("구가의 서", "그곳의 삶"), names(rankSearchRows(rows, "ㄱㄱㅇㅅ")))
    }

    @Test fun englishOriginalTitleAndServerCodeAreRecognized() {
        val rows = listOf(row("다른 작품"), row("인턴", original = "The Intern"), row("작품 코드", code = "AB-123"))
        assertEquals(listOf("인턴"), names(rankSearchRows(rows, "the intern")))
        assertEquals(listOf("작품 코드"), names(rankSearchRows(rows, "ab123")))
    }

    @Test fun multipleKeywordsCanAppearSeparatelyInOneTitle() {
        val rows = listOf(row("관계없는 작품"), row("해리 포터와 불의 잔"))
        assertEquals(listOf("해리 포터와 불의 잔"), names(rankSearchRows(rows, "해리 불")))
    }

    @Test fun serverAliasOrTypoResultsRemainWhenNoDirectEvidenceExists() {
        val rows = listOf(row("서버 별칭 결과"), row("두 번째 결과"))
        assertEquals(rows, rankSearchRows(rows, "The Intern"))
    }

    @Test fun internQueryFiltersHundredsOfUnrelatedServerHits() {
        val unrelated = (1..214).map { row("관계없는 합성 작품 $it") }
        val rows = unrelated + listOf(row("인턴 2"), row("인턴", original = "The Intern"),
            row("The Intern", original = "인턴"))
        assertEquals(217, rows.size)
        assertEquals(listOf("인턴", "The Intern", "인턴 2"), names(rankSearchRows(rows, "인턴")))
        assertEquals(listOf("인턴", "The Intern"), names(rankSearchRows(rows, "the intern")))
    }
}
