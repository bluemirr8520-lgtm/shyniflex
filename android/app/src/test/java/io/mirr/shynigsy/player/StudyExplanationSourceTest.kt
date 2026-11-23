package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class StudyExplanationSourceTest {
    @Test fun translatedTrackDoesNotBlockEmbeddedEnglishFallback() {
        assertNull(selectStudyEnglishPassage(StudyExplanationInput("오늘은 집에 가야 해요.")))
    }
    @Test fun prefersEnglishOverKoreanTrackWithLatinNames() {
        val english = StudyExplanationInput("I should have called you.")
        assertSame(english, selectStudyEnglishPassage(StudyExplanationInput("John, 네게 전화했어야 했어."), english))
    }
    @Test fun equallyEnglishTracksKeepPrimarySelection() {
        val primary = StudyExplanationInput("We have already left.")
        assertSame(primary, selectStudyEnglishPassage(primary, StudyExplanationInput("We already left.")))
    }
    @Test fun emptyAndBitmapOnlySourcesHaveNoPassage() {
        assertNull(selectStudyEnglishPassage(null, StudyExplanationInput(" 123 ! ")))
    }
    @Test fun preservesTheOriginalTextAndTruncationFlag() {
        val source = StudyExplanationInput("I’m looking forward to it!", true)
        assertSame(source, selectStudyEnglishPassage(source))
    }
    @Test fun textOutsideAnalysisBudgetCannotSelectWrongTrack() {
        assertNull(selectStudyEnglishPassage(StudyExplanationInput("한".repeat(4_000) + "English")))
    }
}
