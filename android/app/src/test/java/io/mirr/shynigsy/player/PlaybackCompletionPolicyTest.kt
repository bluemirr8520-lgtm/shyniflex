package io.mirr.shynigsy.player

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class PlaybackCompletionPolicyTest {
    @Test fun exactlyNinetyFivePercentCountsButThePrecedingMillisecondDoesNot() {
        assertFalse(reachedWatchedThreshold(94_999, 100_000))
        assertTrue(reachedWatchedThreshold(95_000, 100_000))
        assertTrue(reachedWatchedThreshold(100_000, 100_000))
    }

    @Test fun fractionalMillisecondThresholdRoundsUp() {
        assertFalse(reachedWatchedThreshold(950, 1_001))
        assertTrue(reachedWatchedThreshold(951, 1_001))
        assertFalse(reachedWatchedThreshold(0, 1))
        assertTrue(reachedWatchedThreshold(1, 1))
    }

    @Test fun unknownOrInvalidTimelinesCannotBecomeWatched() {
        assertFalse(reachedWatchedThreshold(10_000, 0))
        assertFalse(reachedWatchedThreshold(10_000, -1))
        assertFalse(reachedWatchedThreshold(-1, 100_000))
    }

    @Test fun veryLongMediaDoesNotOverflow() {
        val threshold = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(95))
            .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValueExact()
        assertFalse(reachedWatchedThreshold(threshold - 1, Long.MAX_VALUE))
        assertTrue(reachedWatchedThreshold(threshold, Long.MAX_VALUE))
    }
}
