package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import org.junit.Assert.*
import org.junit.Test

class EmbeddedSubtitleSelectionTest {
    private val korean = SubtitleTrack(7, "한국어", "https://test.invalid/subtitle", source = "embedded", language = "kor", streamIndex = 101)
    private val english = korean.copy(id = 6, label = "영어", language = "eng")
    private fun spec(tracks: List<SubtitleTrack>, selected: Int? = null) = PlaybackSpec("test", "https://test.invalid/video",
        itemId = 1, libraryId = 1, partId = 1, subtitles = tracks, selectedSubtitleId = selected)

    @Test fun choosesKoreanNativeTrackWithoutAssumingGlobalIndexNamespace() {
        assertEquals(1, embeddedSubtitleIndex(korean, listOf(
            EmbeddedSubtitleOption(true, "en", null), EmbeddedSubtitleOption(true, "ko-KR", null))))
    }
    @Test fun externalSubtitlesAreNeverReplacedByContainerCaptions() {
        assertNull(embeddedSubtitleIndex(korean.copy(source = "external"), listOf(EmbeddedSubtitleOption(true, "ko", null))))
    }
    @Test fun unsupportedOrDifferentLanguageNeedsServerConversion() {
        assertNull(embeddedSubtitleIndex(korean, listOf(EmbeddedSubtitleOption(false, "ko", null), EmbeddedSubtitleOption(true, "en", null))))
    }
    @Test fun ambiguousSameLanguageTracksUseStableNativeSelectionWithoutConversion() {
        val options = listOf(EmbeddedSubtitleOption(true, "ko", null), EmbeddedSubtitleOption(true, "kor", null))
        assertEquals(0, embeddedSubtitleIndex(korean, options))
        assertEquals(0, embeddedSubtitleIndex(korean, options))
    }
    @Test fun uniqueTitleResolvesSameLanguageTracks() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(label = "Korean SDH"), listOf(
            EmbeddedSubtitleOption(true, "ko", "Korean"), EmbeddedSubtitleOption(true, "ko", "Korean SDH"))))
    }
    @Test fun missingLanguageCanUseTheOnlySupportedContainerTrack() {
        assertEquals(0, embeddedSubtitleIndex(korean.copy(language = null), listOf(EmbeddedSubtitleOption(true, null, null))))
    }
    @Test fun serverLanguageCanSelectTheOnlyContainerTrackWithNoLanguageTag() {
        assertEquals(0, embeddedSubtitleIndex(korean, listOf(EmbeddedSubtitleOption(true, "und", null))))
        assertNull(embeddedSubtitleIndex(korean, listOf(EmbeddedSubtitleOption(true, "en", null))))
        assertNull(embeddedSubtitleIndex(korean, listOf(EmbeddedSubtitleOption(true, "und", null), EmbeddedSubtitleOption(false, "ko", null))))
    }
    @Test fun respectsServerSelectedSubtitleEvenWhenNotFirst() {
        assertEquals(korean, preferredSubtitle(spec(listOf(english, korean), selected = 7)))
    }
    @Test fun noServerSelectionPrefersKoreanToFirstEnglishRow() {
        assertEquals(korean, preferredSubtitle(spec(listOf(english, korean))))
    }
    @Test fun excludedServerSelectionDoesNotFallBackToUnrelatedEnglishServerTrack() {
        assertNull(preferredSubtitle(spec(listOf(english), selected = 99)))
    }

    @Test fun ambiguousKoreanTracksUseKoreanDefaultInsteadOfEnglishDefault() {
        assertEquals(2, embeddedSubtitleIndex(korean, listOf(
            EmbeddedSubtitleOption(true, "en", "English", isDefault = true),
            EmbeddedSubtitleOption(true, "ko", "Korean A"),
            EmbeddedSubtitleOption(true, "kor", "Korean B", isDefault = true))))
    }
    @Test fun missingNativeDefaultPrefersFullCaptionsToForcedOnlyCaptions() {
        assertEquals(1, embeddedSubtitleIndex(korean, listOf(
            EmbeddedSubtitleOption(true, "ko", null, isForced = true),
            EmbeddedSubtitleOption(true, "ko", null))))
    }
    @Test fun serverForcedPreferenceOutranksContainerDefault() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(forced = true), listOf(
            EmbeddedSubtitleOption(true, "ko", null, isDefault = true),
            EmbeddedSubtitleOption(true, "ko", null, isForced = true))))
    }
    @Test fun explicitNonForcedPreferenceIsDifferentFromMissingPreference() {
        val options = listOf(
            EmbeddedSubtitleOption(true, "ko", null, isDefault = true, isForced = true),
            EmbeddedSubtitleOption(true, "ko", null))
        assertEquals(0, embeddedSubtitleIndex(korean, options))
        assertEquals(1, embeddedSubtitleIndex(korean.copy(forced = false), options))
    }
    @Test fun matchingNativeSdhFlagHonorsServerAccessibilityPreference() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(sdh = true), listOf(
            EmbeddedSubtitleOption(true, "ko", null, isDefault = true),
            EmbeddedSubtitleOption(true, "ko", null, isSdh = true))))
    }
    @Test fun originalSubtitleMimeDisambiguatesTracksAfterMedia3CueTranscoding() {
        // Media3's transcoder sets all parsed sample MIME types to media3-cues.
        // The caller supplies Format.codecs, which retains the original MIME.
        assertEquals(1, embeddedSubtitleIndex(korean.copy(format = "ass"), listOf(
            EmbeddedSubtitleOption(true, "ko", null, originalMimeType = "application/x-subrip", isDefault = true),
            EmbeddedSubtitleOption(true, "ko", null, originalMimeType = "text/x-ssa"))))
    }
    @Test fun absentNativeFormatMatchStillUsesSameLanguageWithoutConversion() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(format = "subrip"), listOf(
            EmbeddedSubtitleOption(true, "ko", null),
            EmbeddedSubtitleOption(true, "kor", null, isDefault = true))))
    }
    @Test fun rawServerTitleCanResolveGeneratedDisplayLabels() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(label = "한국어 · ASS", title = "Korean SDH"), listOf(
            EmbeddedSubtitleOption(true, "ko", "Korean", isDefault = true),
            EmbeddedSubtitleOption(true, "ko", "Korean SDH"))))
    }
    @Test fun exactTitleCannotSelectAnUnrelatedLanguage() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(title = "Full captions"), listOf(
            EmbeddedSubtitleOption(true, "en", "Full captions", isDefault = true),
            EmbeddedSubtitleOption(true, "ko", "Korean"))))
        assertNull(embeddedSubtitleIndex(korean.copy(title = "Full captions"), listOf(
            EmbeddedSubtitleOption(true, "en", "Full captions", isDefault = true))))
    }
    @Test fun unsupportedDefaultCannotDisplaceSupportedSameLanguageTrack() {
        assertEquals(1, embeddedSubtitleIndex(korean, listOf(
            EmbeddedSubtitleOption(false, "ko", null, isDefault = true),
            EmbeddedSubtitleOption(true, "ko", null),
            EmbeddedSubtitleOption(true, "en", null, isDefault = true))))
    }
    @Test fun noServerLanguageAllowsContainerDefault() {
        assertEquals(1, embeddedSubtitleIndex(korean.copy(language = null), listOf(
            EmbeddedSubtitleOption(true, "en", null),
            EmbeddedSubtitleOption(true, "ko", null, isDefault = true))))
    }
    @Test fun emptyContainerTracksCannotResolveEmbeddedCaption() {
        assertNull(embeddedSubtitleIndex(korean, emptyList()))
    }
}
