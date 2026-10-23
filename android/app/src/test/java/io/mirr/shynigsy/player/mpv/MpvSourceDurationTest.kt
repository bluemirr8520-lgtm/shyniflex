package io.mirr.shynigsy.player.mpv

import io.mirr.shynigsy.player.reachedWatchedThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSourceDurationTest {
    @Test fun shorterNativeEstimateCannotMoveTheFullSourceThresholdToTheMidpoint() {
        val duration = MpvSourceDuration(120 * 60_000L)
        val fullDuration = duration.observe(60 * 60.0)!!
        assertEquals(120 * 60_000L, fullDuration)
        assertFalse(reachedWatchedThreshold(57 * 60_000L, fullDuration))
        assertTrue(reachedWatchedThreshold(114 * 60_000L, fullDuration))
    }

    @Test fun unknownMetadataWaitsForALatePositiveNativeDuration() {
        val duration = MpvSourceDuration(0)
        assertNull(duration.durationMs)
        assertNull(duration.observe(null))
        assertNull(duration.observe(0.0))
        assertEquals(7_200_125L, duration.observe(7_200.125))
        assertFalse(reachedWatchedThreshold(57 * 60_000L, duration.durationMs!!))
    }

    @Test fun nativeDurationMayGrowButNeverRegressDuringTheSameFile() {
        val duration = MpvSourceDuration(60_000)
        assertEquals(3_600_000L, duration.observe(3_600.0))
        assertEquals(7_200_000L, duration.observe(7_200.0))
        assertEquals(7_200_000L, duration.observe(3_600.0))
        assertEquals(7_200_000L, duration.observe(null))
    }

    @Test fun replacementMediaDropsThePreviousNativeEstimateButKeepsItsConfiguredFloor() {
        val duration = MpvSourceDuration(60_000)
        duration.observe(7_200.0)
        duration.reset()
        assertEquals(60_000L, duration.durationMs)
        assertEquals(120_000L, duration.observe(120.0))
        val unknown = MpvSourceDuration(0)
        unknown.observe(7_200.0)
        unknown.reset()
        assertNull(unknown.durationMs)
    }

    @Test fun invalidNativeValuesCannotOverflowMillisecondsOrMedia3Microseconds() {
        val duration = MpvSourceDuration(120_000)
        val unknown = MpvSourceDuration(0)
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, 0.0, 0.0001, Double.MAX_VALUE, Long.MAX_VALUE.toDouble() / 1000,
            Long.MAX_VALUE.toDouble() / 1_000_000)) {
            assertEquals(120_000L, duration.observe(seconds))
            assertNull(unknown.observe(seconds))
        }
        assertEquals(123_456L, duration.observe(123.456))
        assertNull(MpvSourceDuration(Long.MAX_VALUE).durationMs)
    }
}
