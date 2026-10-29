package io.mirr.shynigsy.player

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressQueueTest {
    @Test fun routineTicksCoalesceToTheLatestPosition() = runBlocking {
        val queue = PlaybackProgressQueue<Int>()
        repeat(1_000) { queue.submit(it) }
        queue.close()
        assertEquals(999, queue.next())
        assertNull(queue.next())
    }

    @Test fun thresholdSnapshotSurvivesLaterTicksAndVideoChanges() = runBlocking {
        val queue = PlaybackProgressQueue<String>()
        queue.submit("old tick")
        queue.submit("95 percent", important = true)
        queue.submit("new video 1")
        queue.submit("new video 2")
        queue.close()
        assertEquals("old tick", queue.next())
        assertEquals("95 percent", queue.next())
        assertEquals("new video 2", queue.next())
        assertNull(queue.next())
    }

    @Test fun exitSnapshotsDrainInOrderAfterClose() = runBlocking {
        val queue = PlaybackProgressQueue<String>()
        queue.submit("threshold", important = true)
        queue.submit("position")
        queue.submit("completed", important = true)
        queue.close()
        assertEquals("threshold", queue.next())
        assertEquals("position", queue.next())
        assertEquals("completed", queue.next())
        assertNull(queue.next())
    }

    @Test fun completionSupersedesOnlyMatchingRoutineSnapshots() = runBlocking {
        data class Snapshot(val source: String, val position: Int)
        val queue = PlaybackProgressQueue<Snapshot>()
        queue.submit(Snapshot("first", 10))
        queue.submit(Snapshot("other", 20), important = true)
        queue.submit(Snapshot("first", 30))
        queue.submit(Snapshot("first", 95), important = true, supersedes = { it.source == "first" })
        queue.close()
        assertEquals(Snapshot("other", 20), queue.next())
        assertEquals(Snapshot("first", 95), queue.next())
        assertNull(queue.next())
    }

    @Test fun suspendedReaderWakesForCompletionAndClose() = runBlocking {
        val queue = PlaybackProgressQueue<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.next() }
        queue.submit(95, important = true)
        assertEquals(95, withTimeout(1_000) { first.await() })
        val end = async(start = CoroutineStart.UNDISPATCHED) { queue.next() }
        queue.close()
        assertNull(withTimeout(1_000) { end.await() })
    }

    @Test fun closedQueueRejectsNewWritesWithoutLosingAcceptedExit() = runBlocking {
        val queue = PlaybackProgressQueue<Int>()
        assertTrue(queue.submit(100, important = true))
        queue.close()
        assertFalse(queue.submit(0))
        assertEquals(100, queue.next())
        assertNull(queue.next())
    }
}
