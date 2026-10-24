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

    @Test fun refreshWakesAtTheNearbyStartAndEndInsteadOfHalfASecondLate() {
        val intro = PlaybackMarker("intro", 1_000, 3_000)
        val markers = listOf(intro)
        assertNull(currentSkipMarker(markers, 875, 10_000))
        assertEquals(125L, playbackMarkerRefreshDelayMs(markers, 875, 10_000, 1f, true))
        assertEquals(intro, currentSkipMarker(markers, 1_000, 10_000))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 1_000, 10_000, 1f, true))
        assertEquals(intro, currentSkipMarker(markers, 2_987, 10_000))
        assertEquals(13L, playbackMarkerRefreshDelayMs(markers, 2_987, 10_000, 1f, true))
        assertNull(currentSkipMarker(markers, 3_000, 10_000))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 3_000, 10_000, 1f, true))
    }

    @Test fun refreshConvertsFullSourceDistanceToWallTimeAtTheSelectedSpeed() {
        val markers = listOf(PlaybackMarker("intro", 1_000, 1_500))
        assertEquals(125L, playbackMarkerRefreshDelayMs(markers, 750, 10_000, 2f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 750, 10_000, .5f, true))
        assertEquals(4L, playbackMarkerRefreshDelayMs(markers, 999, 10_000, .25f, true))
        assertEquals(75L, playbackMarkerRefreshDelayMs(markers, 1_200, 10_000, 4f, true))
    }

    @Test fun fractionalWallTimesRoundUpAndNeverRequestAZeroDelay() {
        val markers = listOf(PlaybackMarker("intro", 100, 200))
        assertEquals(3L, playbackMarkerRefreshDelayMs(markers, 95, 1_000, 2f, true))
        assertEquals(2L, playbackMarkerRefreshDelayMs(markers, 99, 1_000, .75f, true))
        assertEquals(1L, playbackMarkerRefreshDelayMs(markers, 99, 1_000, 1.5f, true))
        assertNull(currentSkipMarker(markers, 99, 1_000))
        assertEquals(markers.single(), currentSkipMarker(markers, 100, 1_000))
        assertEquals(1L, playbackMarkerRefreshDelayMs(markers, 199, 1_000, 4f, true))
    }

    @Test fun nextBoundaryIsRecomputedAfterSeekingBackOnTheFullSourceClock() {
        val intro = PlaybackMarker("intro", 5_000, 6_000)
        val credits = PlaybackMarker("credits", 6_100, 7_000)
        val markers = listOf(credits, intro)
        assertEquals(75L, playbackMarkerRefreshDelayMs(markers, 5_925, 8_000, 1f, true))
        assertEquals(100L, playbackMarkerRefreshDelayMs(markers, 6_000, 8_000, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 7_100, 8_000, 1f, true))
        assertEquals(45L, playbackMarkerRefreshDelayMs(markers, 4_955, 8_000, 1f, true))
        assertNull(currentSkipMarker(markers, 4_955, 8_000))
        assertEquals(intro, currentSkipMarker(markers, 5_000, 8_000))
    }

    @Test fun nearestBoundaryIsIndependentOfInputOrderAndIntroSelectionPriority() {
        val intro = PlaybackMarker("intro", 1_000, 1_200)
        val credits = PlaybackMarker("credits", 0, 2_000)
        val later = PlaybackMarker("credits", 1_500, 1_600)
        val markers = listOf(later, credits, intro)
        assertEquals(25L, playbackMarkerRefreshDelayMs(markers, 975, 3_000, 1f, true))
        assertEquals(25L, playbackMarkerRefreshDelayMs(markers.reversed(), 975, 3_000, 1f, true))
        assertEquals(intro, currentSkipMarker(markers, 1_150, 3_000))
        assertEquals(50L, playbackMarkerRefreshDelayMs(markers, 1_150, 3_000, 1f, true))
        assertEquals(credits, currentSkipMarker(markers, 1_200, 3_000))
    }

    @Test fun refreshUsesTheClampedDurationEndAndCanonicalSupportedType() {
        val markers = listOf(PlaybackMarker(" CREDITS ", 8_000, Long.MAX_VALUE))
        assertEquals(125L, playbackMarkerRefreshDelayMs(markers, 8_875, 9_000, 1f, true))
        assertEquals(PlaybackMarker("credits", 8_000, 9_000), currentSkipMarker(markers, 8_999, 9_000))
        assertEquals(1L, playbackMarkerRefreshDelayMs(markers, 8_999, 9_000, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 9_000, 9_000, 1f, true))
        assertNull(currentSkipMarker(markers, 9_000, 9_000))
    }

    @Test fun unsupportedAndMalformedMarkersCannotCreateSpuriousWakeups() {
        val invalid = listOf(PlaybackMarker("recap", 710, 900), PlaybackMarker("intro", -1, 710),
            PlaybackMarker("credits", 700, 699), PlaybackMarker("intro", 800, 800),
            PlaybackMarker("credits", 5_000, 6_000), PlaybackMarker("intro", Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(500L, playbackMarkerRefreshDelayMs(invalid, 700, 5_000, 1f, true))
        assertEquals(300L, playbackMarkerRefreshDelayMs(invalid + PlaybackMarker("credits", 1_000, 1_100),
            700, 5_000, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(emptyList(), 700, 5_000, 1f, true))
    }

    @Test fun pausedInvalidClocksAndInvalidSpeedsKeepTheNormalRefreshInterval() {
        val markers = listOf(PlaybackMarker("intro", 100, 200))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 99, 1_000, 1f, false))
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 99, 1_000, speed, true))
        }
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 99, duration, 1f, true))
        }
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, -1, 1_000, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 1_000, 1_000, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, Long.MAX_VALUE, 1_000, 1f, true))
    }

    @Test fun hugeNearbyClocksAndExtremePositiveSpeedsDoNotOverflowOrLoseMilliseconds() {
        val markers = listOf(PlaybackMarker("intro", Long.MAX_VALUE - 10, Long.MAX_VALUE))
        assertEquals(1L, playbackMarkerRefreshDelayMs(markers, Long.MAX_VALUE - 11, Long.MAX_VALUE, 1f, true))
        assertEquals(2L, playbackMarkerRefreshDelayMs(markers, Long.MAX_VALUE - 2, Long.MAX_VALUE, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, 0, Long.MAX_VALUE, 1f, true))
        assertEquals(500L, playbackMarkerRefreshDelayMs(markers, Long.MAX_VALUE - 11, Long.MAX_VALUE, Float.MIN_VALUE, true))
        assertEquals(1L, playbackMarkerRefreshDelayMs(markers, 0, Long.MAX_VALUE, Float.MAX_VALUE, true))
        val largeSpeed = (1L shl 55).toFloat()
        val distantStart = (1L shl 55) + 1
        // Converting this distance to Double before division would incorrectly round down to 1ms.
        assertEquals(2L, playbackMarkerRefreshDelayMs(listOf(PlaybackMarker("intro", distantStart, distantStart + 100)),
            0, distantStart + 200, largeSpeed, true))
    }
}
