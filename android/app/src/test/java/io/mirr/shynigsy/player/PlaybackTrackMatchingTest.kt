package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTrackMatchingTest {
    private fun preference(language: String?, label: String? = null) = PlaybackTrackPreference(language, label)
    private fun subtitle(
        id: Int = 1,
        language: String? = "ko",
        label: String = "Korean",
        source: String? = "embedded",
        format: String? = "ass",
        forced: Boolean? = false,
        sdh: Boolean? = false,
        title: String? = null,
    ) = SubtitleTrack(id, label, "https://test.invalid/sub/$id", source = source, language = language,
        format = format, forced = forced, sdh = sdh, title = title)

    @Test fun languageAndLabelSurviveReordering() {
        assertEquals(2, matchingPlaybackTrack(preference("ko", "Commentary"), listOf(
            preference("ko", "Main"), preference("en", "Commentary"), preference("kor", "Commentary"))))
    }

    @Test fun languageAliasesAndRegionalTagsMatch() {
        for ((old, replacement) in listOf("kor" to "ko-KR", "eng" to "en_US", "JPN" to "ja-JP")) {
            assertEquals(1, matchingPlaybackTrack(preference(old), listOf(preference("fr"), preference(replacement))))
        }
    }

    @Test fun renamedLabelFallsBackToTheSameLanguage() {
        assertEquals(1, matchingPlaybackTrack(preference("eng", "Original"), listOf(
            preference("ko", "Original"), preference("en", "AAC stereo"))))
    }

    @Test fun languageLessPreferenceCanUseItsMeaningfulLabel() {
        assertEquals(1, matchingPlaybackTrack(preference("und", "  Director  Commentary "), listOf(
            preference(null, "Main"), preference("en", "director commentary"))))
    }

    @Test fun identicalLabelsCannotOverrideDifferentKnownLanguages() {
        assertNull(matchingPlaybackTrack(preference("ko", "Main"), listOf(preference("en", "Main"))))
        assertNull(matchingPlaybackTrack(preference("qaa", "Main"), listOf(preference("qab", "Main"))))
    }

    @Test fun missingMetadataLeavesTheNewDefaultUntouched() {
        assertNull(matchingPlaybackTrack(preference(null), listOf(preference("ko"))))
        assertNull(matchingPlaybackTrack(preference("und", "Track 1"), listOf(preference(null, "Track 1"))))
        assertNull(matchingPlaybackTrack(preference("ko"), emptyList()))
    }

    @Test fun replacementFindsNewIdsByMetadata() {
        val previous = subtitle(title = "Korean SDH", sdh = true)
        val wanted = subtitle(72, "kor", "한국어 · ASS", title = "Korean SDH", sdh = true)
        assertEquals(wanted, replacementSubtitle(previous, listOf(subtitle(71, "en"), wanted)))
    }

    @Test fun collidingIdAndTitleCannotCrossLanguages() {
        val previous = subtitle()
        val unrelated = previous.copy(language = "eng")
        val wanted = subtitle(40, "kor")
        assertEquals(wanted, replacementSubtitle(previous, listOf(unrelated, wanted)))
        assertNull(replacementSubtitle(previous, listOf(unrelated)))
    }

    @Test fun aSharedIdWithoutLanguageOrMeaningfulLabelIsNotIdentity() {
        val previous = subtitle(language = null, label = "Subtitle 1", source = "embedded")
        assertNull(replacementSubtitle(previous, listOf(previous.copy(url = "https://test.invalid/new-part"))))
    }

    @Test fun sameIdBreaksTiesWhenSemanticIdentityStillMatches() {
        val previous = subtitle()
        val sameId = previous.copy(language = "kor", source = "internal", url = "https://test.invalid/new-part")
        assertEquals(sameId, replacementSubtitle(previous, listOf(previous.copy(id = 99), sameId)))
    }

    @Test fun exactMetadataBeatsAnIdCollisionInTheSameLanguage() {
        val previous = subtitle(title = "Full captions", forced = false, sdh = true)
        val collision = previous.copy(title = "Signs only", label = "Signs", forced = true, sdh = false)
        val wanted = previous.copy(id = 99)
        assertEquals(wanted, replacementSubtitle(previous, listOf(collision, wanted)))
    }

    @Test fun descriptiveTitleOutranksASharedGeneratedLanguageLabel() {
        val previous = subtitle(label = "한국어 · ASS", title = "Director commentary")
        val wanted = previous.copy(id = 99)
        val collision = previous.copy(title = "Main captions")
        assertEquals(wanted, replacementSubtitle(previous, listOf(collision, wanted)))
    }

    @Test fun sourcePreferenceSurvivesIdsChangingAcrossParts() {
        val previous = subtitle(source = "external")
        val wanted = previous.copy(id = 8, source = "sidecar")
        assertEquals(wanted, replacementSubtitle(previous, listOf(subtitle(id = previous.id), wanted)))
    }

    @Test fun explicitNonForcedAndSdhPreferencesAreRetained() {
        val previous = subtitle(forced = false, sdh = true)
        val wanted = previous.copy(id = 18)
        assertEquals(wanted, replacementSubtitle(previous, listOf(
            previous.copy(id = 2, forced = true), previous.copy(id = 3, sdh = false), wanted)))
    }

    @Test fun originalBitmapFormatWinsEvenWhenDefaultCueMimeSaysText() {
        val previous = subtitle(format = "hdmv_pgs_subtitle")
        val wanted = subtitle(5, format = "pgs")
        assertEquals(wanted, replacementSubtitle(previous, listOf(subtitle(), wanted)))
    }

    @Test fun plaintextSelectionPrefersTextToAnIdCollidingBitmapTrack() {
        val previous = subtitle(format = "subrip")
        val wanted = subtitle(5, format = "srt")
        assertEquals(wanted, replacementSubtitle(previous, listOf(subtitle(format = "pgs"), wanted)))
    }

    @Test fun changedFormatAndSourceCanStillFallBackToTheSelectedLanguage() {
        val previous = subtitle(source = "external", format = "pgs")
        val wanted = subtitle(22, label = "한국어", format = "srt")
        assertEquals(wanted, replacementSubtitle(previous, listOf(subtitle(3, "en"), wanted)))
    }

    @Test fun missingLanguageNeedsMatchingDescriptiveMetadata() {
        val previous = subtitle(language = null, label = "Director commentary")
        val wanted = subtitle(9, language = null, label = "director COMMENTARY")
        assertEquals(wanted, replacementSubtitle(previous, listOf(subtitle(), wanted)))
        assertNull(replacementSubtitle(previous, listOf(subtitle())))
    }

    @Test fun unrelatedLanguageTagsAndEmptyInputsDoNotInventSelection() {
        val previous = subtitle(language = "qaa")
        assertNull(replacementSubtitle(previous, listOf(previous.copy(language = "qab"))))
        assertNull(replacementSubtitle(previous, emptyList()))
        assertNull(replacementSubtitle(null, listOf(subtitle())))
    }
}
