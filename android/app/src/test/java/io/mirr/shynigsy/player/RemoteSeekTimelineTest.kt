package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class RemoteSeekTimelineTest {
    @Test fun quickTapKeepsTenSecondStep() {
        val timeline = RemoteSeekTimeline(60_000, 300_000, true, 1000)
        assertEquals(70_000L, timeline.targetAt(1000))
        assertEquals(70_000L, timeline.targetAt(1299))
        assertEquals(50_000L, RemoteSeekTimeline(60_000, 300_000, false, 1000).targetAt(1200))
    }

    @Test fun holdMovesContinuouslyWithoutDependingOnKeyRepeatEvents() {
        val timeline = RemoteSeekTimeline(60_000, 3_600_000, true, 0)
        var previous = timeline.targetAt(300)
        for (time in 316L..3300L step 16) {
            val next = timeline.targetAt(time)
            assertTrue(next > previous)
            assertTrue("No repeated ten-second jumps", next - previous < 1400)
            previous = next
        }
        assertEquals(100_000L, timeline.targetAt(1300))
        assertEquals(220_000L, timeline.targetAt(3300))
    }

    @Test fun boundariesCannotSeekBeforeStartOrThroughPlaybackCompletion() {
        assertEquals(0L, RemoteSeekTimeline(5000, 60_000, false, 0).targetAt(10_000))
        assertEquals(59_999L, RemoteSeekTimeline(50_000, 60_000, true, 0).targetAt(10_000))
        assertEquals(0L, RemoteSeekTimeline(0, 0, true, 0).targetAt(10_000))
    }

    @Test fun anotherPressBeforeSeekSettlesKeepsOnlyLatestFinalTarget() {
        val queue = LatestSeekQueue()
        queue.offer(10_000, final = true)
        queue.poll(0)
        queue.resumePreview()
        queue.offer(20_000, final = true)
        assertFalse(queue.complete()!!.final)
        val final = queue.poll(20)!!
        assertEquals(20_000L, final.positionMs)
        assertTrue(final.final)
        assertNull(queue.pending)
    }
}
