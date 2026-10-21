package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class SeekGestureMathTest {
    @Test fun longMoviesHaveTheSameSensitivityAndUseActualViewWidth() {
        assertEquals(330_000L, SeekGestureMath.position(300_000, 250f, 1000, 7_200_000))
        assertEquals(330_000L, SeekGestureMath.position(300_000, 250f, 1000, 14_400_000))
        assertEquals(360_000L, SeekGestureMath.position(300_000, 250f, 500, 7_200_000))
    }

    @Test fun shortClipsAndBoundarySeeksRemainInsideTheTimeline() {
        assertEquals(0L, SeekGestureMath.position(5_000, -900f, 1000, 30_000))
        assertEquals(29_999L, SeekGestureMath.position(25_000, 900f, 1000, 30_000))
        assertEquals(15_000L, SeekGestureMath.position(0, 500f, 1000, 30_000))
    }

    @Test fun ambiguousDiagonalMotionCannotStealBrightnessOrVolume() {
        assertFalse(SeekGestureMath.horizontal(30f, 30f, 8f))
        assertFalse(SeekGestureMath.vertical(30f, 30f, 8f))
        assertTrue(SeekGestureMath.horizontal(-40f, 10f, 8f))
        assertTrue(SeekGestureMath.vertical(10f, -40f, 8f))
        assertFalse(SeekGestureMath.horizontal(7f, 0f, 8f))
    }

    @Test fun slowDecoderGetsOnlyOneInFlightSeekAndTheLatestPendingTarget() {
        val queue = LatestSeekQueue()
        queue.offer(1_000)
        assertEquals(1_000L, queue.poll(0)?.positionMs)
        repeat(500) { queue.offer((2_000 + it).toLong()) }
        assertNull(queue.poll(10_000))
        assertEquals(2_499L, queue.pending?.positionMs)
        queue.complete()
        assertEquals(2_499L, queue.poll(10_000)?.positionMs)
    }

    @Test fun previewRequestsAreThrottledButFinalCommitIsNotDelayed() {
        val queue = LatestSeekQueue()
        queue.offer(1_000)
        queue.poll(0)
        queue.complete()
        queue.offer(2_000)
        assertNull(queue.poll(249))
        assertEquals(2_000L, queue.poll(250)?.positionMs)
        queue.complete()
        queue.offer(3_000, final = true)
        assertEquals(LatestSeekQueue.Request(3_000, true), queue.poll(251))
    }

    @Test fun reversingToAnInFlightTargetDropsAnObsoletePendingSeek() {
        val queue = LatestSeekQueue()
        queue.offer(1_000)
        queue.poll(0)
        queue.offer(2_000)
        queue.offer(1_000, final = true)
        assertNull(queue.pending)
        assertEquals(LatestSeekQueue.Request(1_000, true), queue.complete())
        assertNull(queue.poll(300))
    }
}
