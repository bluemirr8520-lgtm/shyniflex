package io.mirr.shynigsy

import io.mirr.shynigsy.data.HomeSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSnapshotPersistenceTest {
    private fun snapshot(value: Long) = HomeSnapshot(value, emptyList(), emptyList(), emptyList(), emptyMap())

    @Test fun anInFlightOldWriteFinishesBeforeClearAndTheNewSave() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val events = mutableListOf<String>()
            val oldStarted = CompletableDeferred<Unit>()
            val finishOld = CompletableDeferred<Unit>()
            val clearStarted = CompletableDeferred<Unit>()
            val finishClear = CompletableDeferred<Unit>()
            val newFinished = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { key, _ ->
                events += "write-$key-start"
                if (key == "old") { oldStarted.complete(Unit); finishOld.await() }
                events += "write-$key-end"
                if (key == "new") newFinished.complete(Unit)
            }, {
                events += "clear-start"
                clearStarted.complete(Unit)
                finishClear.await()
                events += "clear-end"
            }, debounceMs = 0)
            try {
                persistence.save("old", snapshot(1))
                oldStarted.await()
                persistence.clear()
                persistence.save("new", snapshot(2))
                assertFalse(clearStarted.isCompleted)
                finishOld.complete(Unit)
                clearStarted.await()
                assertFalse(newFinished.isCompleted)
                finishClear.complete(Unit)
                newFinished.await()
                assertEquals(listOf("write-old-start", "write-old-end", "clear-start", "clear-end", "write-new-start", "write-new-end"), events)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun rapidPartialSnapshotsWriteOnlyTheLatestValue() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val written = mutableListOf<Long>()
            var clears = 0
            val complete = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { _, value ->
                written += value.savedAtMs
                complete.complete(Unit)
            }, { clears++ }, debounceMs = 50)
            try {
                repeat(100) { persistence.save("account", snapshot(it.toLong())) }
                complete.await()
                assertEquals(listOf(99L), written)
                assertEquals(0, clears)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun aScopeSwitchDiscardsQueuedOldSnapshotsAndClearsAfterTheRunningWrite() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val events = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val complete = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { key, value ->
                events += "$key-${value.savedAtMs}"
                if (key == "old") { started.complete(Unit); release.await() }
                if (key == "new") complete.complete(Unit)
            }, { events += "clear" }, debounceMs = 0)
            try {
                persistence.save("old", snapshot(1))
                started.await()
                persistence.save("old", snapshot(2))
                persistence.save("new", snapshot(3))
                persistence.save("new", snapshot(4))
                release.complete(Unit)
                complete.await()
                assertEquals(listOf("old-1", "clear", "new-4"), events)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun clearInterruptsDebounceAndNeverWritesTheDiscardedSnapshot() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val events = mutableListOf<String>()
            val cleared = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { _, _ ->
                events += "unexpected-write"
            }, { events += "clear"; cleared.complete(Unit) }, debounceMs = 60_000)
            try {
                persistence.save("account", snapshot(1))
                yield()
                persistence.clear()
                cleared.await()
                assertEquals(listOf("clear"), events)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun writeAndClearFailuresDoNotStopLaterCacheUpdates() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val events = mutableListOf<String>()
            val firstAttempt = CompletableDeferred<Unit>()
            val complete = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { _, value ->
                events += "write-${value.savedAtMs}"
                if (value.savedAtMs == 1L) {
                    firstAttempt.complete(Unit)
                    throw IOException("fixture write failure")
                }
                complete.complete(Unit)
            }, { events += "clear"; throw IOException("fixture clear failure") }, debounceMs = 0)
            try {
                persistence.save("account", snapshot(1))
                firstAttempt.await()
                persistence.clear()
                persistence.save("account", snapshot(2))
                complete.await()
                assertEquals(listOf("write-1", "clear", "write-2"), events)
                assertTrue(worker.isActive)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun cancellingTheOwnerStopsAnInFlightWriteAndAllFutureCommands() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val events = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { _, _ ->
                events += "write"
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }, { events += "unexpected-clear" }, debounceMs = 0)
            try {
                persistence.save("account", snapshot(1))
                started.await()
                worker.cancelAndJoin()
                cancelled.await()
                persistence.clear()
                persistence.save("another", snapshot(2))
                assertEquals(listOf("write"), events)
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun callbackCancellationIsPropagatedRatherThanTreatedAsAnIoFailure() = runBlocking {
        withTimeout(5_000) {
            val worker = SupervisorJob()
            val events = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val persistence = HomeSnapshotPersistence(CoroutineScope(coroutineContext + worker), { _, _ ->
                events += "write"
                started.complete(Unit)
                throw CancellationException("fixture cancelled write")
            }, { events += "unexpected-clear" }, debounceMs = 0)
            try {
                persistence.save("account", snapshot(1))
                started.await()
                while (worker.children.any { it.isActive }) yield()
                persistence.save("account", snapshot(2))
                persistence.clear()
                assertEquals(listOf("write"), events)
            } finally { worker.cancelAndJoin() }
        }
    }
}
