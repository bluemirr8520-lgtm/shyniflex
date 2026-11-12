package io.mirr.shynigsy.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvRebufferPolicyTest {
    @Test fun startupPauseSeekFocusLossAndEofDoNotIncreaseTheTarget() {
        val policy = MpvRebufferPolicy(45, 96)
        // Each excluded state may emit the same native cache-pause events as starvation.
        repeat(6) { excluded ->
            assertNull(policy.observe(excluded * 100L, true, eligible = false, progressing = false))
            // Becoming eligible while the same cache pause remains is not a new underrun.
            assertNull(policy.observe(excluded * 100L + 1, true, eligible = true, progressing = false))
            assertNull(policy.observe(excluded * 100L + 2, false, eligible = true, progressing = true))
        }
        assertEquals(1, policy.waitSeconds)
    }

    @Test fun separateUnderrunsIncreaseHeadroomButRepeatedTrueEventsDoNot() {
        val policy = MpvRebufferPolicy(45, 96)
        assertEquals(2, policy.observe(1_000, true, true, false))
        assertNull(policy.observe(2_000, true, true, false))
        assertNull(policy.observe(3_000, false, true, true))
        assertEquals(4, policy.observe(4_000, true, true, false))
        policy.observe(5_000, false, true, true)
        assertEquals(6, policy.observe(6_000, true, true, false))
        policy.observe(7_000, false, true, true)
        assertNull(policy.observe(8_000, true, true, false))
        assertEquals(6, policy.waitSeconds)
    }

    @Test fun uninterruptedRecoveryReturnsToTheFastTarget() {
        val policy = MpvRebufferPolicy(30, 48)
        policy.observe(0, true, true, false)
        policy.observe(1_000, false, true, true)
        assertNull(policy.observe(60_999, false, true, true))
        assertEquals(1, policy.observe(61_000, false, true, true))
        assertNull(policy.observe(62_000, false, true, true))
        assertEquals(2, policy.observe(63_000, true, true, false))
    }

    @Test fun timeSpentPausedOrSeekingDoesNotCountAsStablePlayback() {
        val policy = MpvRebufferPolicy(30, 48)
        policy.observe(0, true, true, false)
        policy.observe(1_000, false, true, true)
        policy.observe(50_000, false, false, false)
        assertNull(policy.observe(150_000, false, true, true))
        assertEquals(2, policy.waitSeconds)
        assertNull(policy.observe(209_999, false, true, true))
        assertEquals(1, policy.observe(210_000, false, true, true))
    }

    @Test fun readinessLossRestartsTheStablePeriodEvenWithPlaybackRequested() {
        val policy = MpvRebufferPolicy(30, 48)
        policy.observe(0, true, true, false)
        policy.observe(1_000, false, true, true)
        policy.observe(50_000, false, true, false)
        policy.observe(70_000, false, true, true)
        assertNull(policy.observe(129_999, false, true, true))
        assertEquals(1, policy.observe(130_000, false, true, true))
    }

    @Test fun newSourceOrSeekDropsTheOldStallHistory() {
        val policy = MpvRebufferPolicy(30, 48)
        policy.observe(1_000, true, true, false)
        policy.observe(2_000, false, true, true)
        policy.observe(3_000, true, true, false)
        assertEquals(1, policy.reset())
        assertNull(policy.reset())
        assertEquals(1, policy.waitSeconds)
        policy.observe(4_000, true, false, false)
        policy.observe(5_000, false, true, true)
        assertEquals(2, policy.observe(6_000, true, true, false))
    }

    @Test fun cacheAndMemoryLimitsBoundEveryRecoveryTarget() {
        for ((cache, memory, expected) in listOf(
            Triple(45, 96, 6), Triple(30, 47, 4), Triple(20, 15, 2),
            Triple(3, 96, 3), Triple(1, 96, 1), Triple(0, 0, 1))) {
            val policy = MpvRebufferPolicy(cache, memory)
            repeat(20) { index ->
                policy.observe(index * 2L, true, true, false)
                policy.observe(index * 2L + 1, false, true, true)
            }
            assertEquals(expected, policy.waitSeconds)
        }
    }

    @Test fun clockResetDoesNotTreatAnOldTimestampAsLongRecovery() {
        val policy = MpvRebufferPolicy(30, 48)
        policy.observe(90_000, true, true, false)
        policy.observe(100_000, false, true, true)
        assertNull(policy.observe(1_000, false, true, true))
        assertNull(policy.observe(60_999, false, true, true))
        assertEquals(1, policy.observe(61_000, false, true, true))
    }
}
