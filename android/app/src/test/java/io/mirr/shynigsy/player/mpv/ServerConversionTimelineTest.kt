package io.mirr.shynigsy.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConversionTimelineTest {
    @Test fun reopenReplacesOnlyStartAndPreservesSignedQueryBytes() {
        val timeline = ServerConversionTimeline(3_600_000)
        val load = timeline.reopen("https://test.invalid/transcode/12?ticket=a%2Bb%2F%3D&mode=video&max_height=720&start_ms=5000", 1_200_000)
        assertEquals("https://test.invalid/transcode/12?ticket=a%2Bb%2F%3D&mode=video&max_height=720&start_ms=1200000", load.url)
        assertEquals(1_200_000L, load.sourcePositionMs)
        assertEquals(0L, load.nativeStartMs)
    }

    @Test fun everyReopenUsesTheRequestedSourceTargetRatherThanThePreviousBase() {
        val timeline = ServerConversionTimeline(3_600_000)
        val initial = timeline.reopen("https://test.invalid/transcode/12?ticket=secret", 1_200_000)
        val seek = timeline.reopen(initial.url, 400_000)
        assertEquals("https://test.invalid/transcode/12?ticket=secret&start_ms=400000", seek.url)
        assertEquals(400_000L, timeline.sourcePosition(0))
        assertEquals(403_000L, timeline.sourcePosition(3_000))
    }

    @Test fun activityRecreationReplacesAStaleOffsetInTheSavedUrl() {
        val recreated = ServerConversionTimeline(3_600_000)
        val load = recreated.reopen("https://test.invalid/transcode/12?mode=video&start_ms=1200000&ticket=secret", 1_350_000)
        assertEquals("https://test.invalid/transcode/12?mode=video&ticket=secret&start_ms=1350000", load.url)
        assertEquals(1_350_000L, recreated.sourcePosition(0))
    }

    @Test fun duplicateAndEncodedOffsetKeysCannotOverrideTheNewTarget() {
        val load = ServerConversionTimeline(0).reopen("https://test.invalid/t?start_ms=1&ticket=a+b&start%5Fms=2&mode=video#kept", 42)
        assertEquals("https://test.invalid/t?ticket=a+b&mode=video&start_ms=42#kept", load.url)
    }

    @Test fun sourceClockAndBufferedClockShareOneOffset() {
        val timeline = ServerConversionTimeline(100_000)
        timeline.reopen("https://test.invalid/t", 30_000)
        assertEquals(35_000L, timeline.sourcePosition(5_000))
        assertEquals(47_000L, timeline.bufferedPosition(5_000, 12_000))
        assertEquals(30_000L, timeline.sourcePosition(-1))
    }

    @Test fun originalDurationWinsOverRemainingOrMissingNativeDuration() {
        val timeline = ServerConversionTimeline(100_000)
        timeline.reopen("https://test.invalid/t", 30_000)
        assertEquals(100_000L, timeline.duration(70_000))
        assertEquals(100_000L, timeline.duration(null))
        assertEquals(100_000L, timeline.duration(0))
    }

    @Test fun unknownSourceLengthUsesRemainingDurationOnlyWhenAvailable() {
        val timeline = ServerConversionTimeline(0)
        timeline.reopen("https://test.invalid/t", 30_000)
        assertNull(timeline.duration(null))
        assertNull(timeline.duration(0))
        assertEquals(100_000L, timeline.duration(70_000))
    }

    @Test fun targetsAndBuffersStayInsideKnownSourceBounds() {
        val timeline = ServerConversionTimeline(100_000)
        assertEquals(0L, timeline.reopen("https://test.invalid/t", -10).sourcePositionMs)
        assertEquals(100_000L, timeline.reopen("https://test.invalid/t", 120_000).sourcePositionMs)
        timeline.reopen("https://test.invalid/t", 90_000)
        assertEquals(100_000L, timeline.bufferedPosition(5_000, 20_000))
    }

    @Test fun zeroStartAndEmptyQueryAreExplicit() {
        val timeline = ServerConversionTimeline(0)
        assertEquals("https://test.invalid/t?start_ms=0", timeline.reopen("https://test.invalid/t?", 0).url)
    }

    @Test fun largeUnknownClockValuesDoNotWrapNegative() {
        val timeline = ServerConversionTimeline(0)
        timeline.reopen("https://test.invalid/t", Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, timeline.sourcePosition(10))
        assertEquals(Long.MAX_VALUE, timeline.bufferedPosition(10, Long.MAX_VALUE))
    }
}
