package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMarkerSelectionTest {
    @Test fun startIsInclusiveAndEndIsExclusive() {
        val intro = PlaybackMarker("intro", 1_000, 5_000)
        assertNull(currentSkipMarker(listOf(intro), 999, 20_000))
        assertEquals(intro, currentSkipMarker(listOf(intro), 1_000, 20_000))
        assertEquals(intro, currentSkipMarker(listOf(intro), 4_999, 20_000))
        assertNull(currentSkipMarker(listOf(intro), 5_000, 20_000))
        assertNull(currentSkipMarker(listOf(intro), 5_001, 20_000))
    }

    @Test fun onlyIntroAndCreditsAreSupportedAndTheirTypesAreCanonical() {
        assertNull(currentSkipMarker(listOf(PlaybackMarker("recap", 0, 5_000)), 1_000, 20_000))
        assertNull(currentSkipMarker(listOf(PlaybackMarker("chapter", 0, 5_000)), 1_000, 20_000))
        assertNull(currentSkipMarker(listOf(PlaybackMarker("", 0, 5_000)), 1_000, 20_000))
        assertEquals(PlaybackMarker("intro", 0, 5_000),
            currentSkipMarker(listOf(PlaybackMarker(" Intro ", 0, 5_000)), 1_000, 20_000))
        assertEquals(PlaybackMarker("credits", 10_000, 20_000),
            currentSkipMarker(listOf(PlaybackMarker("CREDITS", 10_000, 20_000)), 15_000, 20_000))
    }

    @Test fun negativeEmptyReversedAndOutOfRangeMarkersAreIgnored() {
        val invalid = listOf(PlaybackMarker("intro", -1, 5_000), PlaybackMarker("intro", 1_000, 1_000),
            PlaybackMarker("credits", 5_000, 1_000), PlaybackMarker("credits", 20_000, 21_000),
            PlaybackMarker("intro", Long.MIN_VALUE, Long.MAX_VALUE), PlaybackMarker("intro", Long.MAX_VALUE, Long.MAX_VALUE))
        assertNull(currentSkipMarker(invalid, 1_500, 20_000))
    }

    @Test fun aMarkerPastTheKnownEndIsClampedToTheFullMediaDuration() {
        val credits = PlaybackMarker("credits", 8_000, 12_000)
        assertEquals(PlaybackMarker("credits", 8_000, 10_000), currentSkipMarker(listOf(credits), 9_000, 10_000))
        assertNull(currentSkipMarker(listOf(credits), 10_000, 10_000))
        assertEquals(PlaybackMarker("credits", 8_000, 10_000),
            currentSkipMarker(listOf(credits.copy(endMs = Long.MAX_VALUE)), 9_000, 10_000))
    }

    @Test fun overlappingIntroWinsAndEquivalentTypesHaveAStableTimeOrder() {
        val credits = PlaybackMarker("credits", 0, 15_000)
        val intro = PlaybackMarker("intro", 1_000, 10_000)
        assertEquals(intro, currentSkipMarker(listOf(credits, intro), 2_000, 20_000))
        assertEquals(intro, currentSkipMarker(listOf(intro, credits), 2_000, 20_000))
        val shorter = intro.copy(endMs = 6_000)
        val later = intro.copy(startMs = 1_500, endMs = 4_000)
        assertEquals(shorter, currentSkipMarker(listOf(later, intro, shorter), 2_000, 20_000))
    }

    @Test fun unknownDurationAndPositionsOutsideTheTimelineNeverOfferASkip() {
        val markers = listOf(PlaybackMarker("intro", 0, 5_000))
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) assertNull(currentSkipMarker(markers, 1_000, duration))
        assertNull(currentSkipMarker(markers, -1, 20_000))
        assertNull(currentSkipMarker(markers, Long.MAX_VALUE, 20_000))
        assertNull(currentSkipMarker(emptyList(), 1_000, 20_000))
    }
}
