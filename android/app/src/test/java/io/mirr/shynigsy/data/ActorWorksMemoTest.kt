package io.mirr.shynigsy.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Synthetic identities and a controlled monotonic clock; no server or account is used. */
class ActorWorksMemoTest {
    private fun works(id: Int = 1, incomplete: Boolean = false) = ActorWorks(
        listOf(MediaItem(id, 7, "Synthetic work $id", "movie", viewItemId = 100 + id)), incomplete = incomplete)

    @Test fun completeResultsAreReusedAndEveryHitIsRevalidated() = runBlocking {
        val memo = ActorWorksMemo()
        var loads = 0
        var validations = 0
        val first = memo.getOrLoad("actor", { validations++ }) { loads++; works() }
        val beforeHit = validations
        val second = memo.getOrLoad("actor", { validations++ }) { loads++; works(2) }
        assertEquals(first, second)
        assertEquals(1, loads)
        assertTrue("Both lookup and return validate even on a hit", validations >= beforeHit + 2)
    }

    @Test fun incompleteResultsAlwaysRetryAndCompleteEmptyCataloguesCanBeCached() = runBlocking {
        val memo = ActorWorksMemo()
        var loads = 0
        repeat(2) { memo.getOrLoad("partial", {}) { loads++; works(incomplete = true) } }
        assertEquals(2, loads)
        repeat(2) { memo.getOrLoad("empty", {}) { loads++; ActorWorks(emptyList()) } }
        assertEquals(3, loads)
    }

    @Test fun expiryUsesStorageTimeAndExpiresAtTheTtlBoundary() = runBlocking {
        var now = 0L
        val memo = ActorWorksMemo(ttlMs = 100, clock = { now })
        var loads = 0
        memo.getOrLoad("actor", {}) { now = 50; loads++; works() }
        now = 149
        memo.getOrLoad("actor", {}) { loads++; works(2) }
        assertEquals(1, loads)
        now = 150
        assertEquals(works(2), memo.getOrLoad("actor", {}) { loads++; works(2) })
        assertEquals(2, loads)
        now = 149 // A regressed clock must not grant an unbounded extension.
        memo.getOrLoad("actor", {}) { loads++; works(3) }
        assertEquals(3, loads)
    }

    @Test fun leastRecentlyUsedEntryIsEvictedAtTheConfiguredLimit() = runBlocking {
        val memo = ActorWorksMemo(maxEntries = 2)
        val loads = mutableMapOf<String, Int>()
        suspend fun read(key: String) = memo.getOrLoad(key, {}) {
            loads[key] = loads.getOrDefault(key, 0) + 1
            works()
        }
        read("a"); read("b"); read("a"); read("c"); read("a"); read("b")
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 1), loads)
    }

    @Test fun zeroCapacityOrTtlDisablesCaching() = runBlocking {
        for (memo in listOf(ActorWorksMemo(maxEntries = 0), ActorWorksMemo(ttlMs = 0))) {
            var loads = 0
            repeat(2) { memo.getOrLoad("actor", {}) { loads++; works() } }
            assertEquals(2, loads)
        }
    }

    @Test fun sameKeyConcurrentCallersShareOneLoadButValidateIndividually() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val validations = IntArray(8)
            val callers = List(8) { index -> async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", { validations[index]++ }) { loads++; gate.await(); works() }
            } }
            assertEquals(1, loads)
            assertTrue(validations.all { it == 1 })
            gate.complete(Unit)
            callers.forEach { assertEquals(works(), it.await()) }
            assertEquals(1, loads)
            assertTrue(validations.all { it >= 2 })
        }
    }

    @Test fun differentKeysLoadInParallel() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val gate = CompletableDeferred<Unit>()
            val started = mutableSetOf<String>()
            val a = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("a", {}) { started += "a"; gate.await(); works() }
            }
            val b = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("b", {}) { started += "b"; gate.await(); works(2) }
            }
            assertEquals(setOf("a", "b"), started)
            gate.complete(Unit)
            assertEquals(works(), a.await())
            assertEquals(works(2), b.await())
        }
    }

    @Test fun cancellingAWaiterDoesNotCancelTheOwnerOrOtherWaiters() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { loads++; gate.await(); works() }
            }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { error("Must join the owner") }
            }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { error("Must join the owner") }
            }
            cancelled.cancelAndJoin()
            assertFalse(owner.isCancelled)
            gate.complete(Unit)
            assertEquals(works(), owner.await())
            assertEquals(works(), waiting.await())
            assertEquals(works(), memo.getOrLoad("actor", {}) { error("Must use the complete result") })
            assertEquals(1, loads)
        }
    }

    @Test fun ownerCancellationLetsAnActiveWaiterRetryAndCacheItsOwnLoad() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { awaitCancellation() }
            }
            var retries = 0
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { retries++; works(2) }
            }
            owner.cancelAndJoin()
            assertEquals(works(2), waiter.await())
            assertEquals(1, retries)
            assertEquals(works(2), memo.getOrLoad("actor", {}) { error("Must use the replacement's result") })
        }
    }

    @Test fun ordinaryOwnerFailureDoesNotSilentlyRetryAWaitersLoad() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val gate = CompletableDeferred<Unit>()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", {}) { gate.await(); throw IOException("Synthetic error") } }
            }
            var retries = 0
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", {}) { retries++; works(2) } }
            }
            gate.complete(Unit)
            assertTrue(owner.await().exceptionOrNull() is IOException)
            assertTrue(waiter.await().exceptionOrNull() is IOException)
            assertEquals(0, retries)
            assertEquals(works(2), memo.getOrLoad("actor", {}) { works(2) })
        }
    }

    @Test fun cancelledUncooperativeLoaderCannotPopulateTheCache() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val gate = CompletableDeferred<Unit>()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { withContext(NonCancellable) { gate.await(); works() } }
            }
            try { owner.cancel() } finally { gate.complete(Unit) }
            owner.join()
            assertEquals(works(2), memo.getOrLoad("actor", {}) { works(2) })
        }
    }

    @Test fun failedLoadsAreNotMemoized() = runBlocking {
        val memo = ActorWorksMemo()
        val failure = runCatching { memo.getOrLoad("actor", {}) { throw IOException("Synthetic failure") } }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals(works(), memo.getOrLoad("actor", {}) { works() })
    }

    @Test fun clearSeparatesNewRequestsAndRejectsLateOldResults() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val oldGate = CompletableDeferred<Unit>()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", {}) { oldGate.await(); works() } }
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", {}) { error("Must join the old owner") } }
            }
            memo.clear()
            assertTrue(waiter.await().exceptionOrNull() is CancellationException)
            assertEquals(works(2), memo.getOrLoad("actor", {}) { works(2) })
            oldGate.complete(Unit)
            assertTrue(owner.await().exceptionOrNull() is CancellationException)
            assertEquals(works(2), memo.getOrLoad("actor", {}) { error("Old response cannot replace new entry") })
        }
    }

    @Test fun revokedAccessRejectsHitsAndLateLoadsWithoutCachingTheRejectedResult() = runBlocking {
        val memo = ActorWorksMemo()
        var allowed = true
        val validate: suspend () -> Unit = { if (!allowed) throw SecurityException("Synthetic revoked grant") }
        memo.getOrLoad("cached", validate) { works() }
        allowed = false
        assertTrue(runCatching { memo.getOrLoad("cached", validate) { error("No unauthorized load") } }
            .exceptionOrNull() is SecurityException)
        allowed = true
        assertTrue(runCatching { memo.getOrLoad("late", validate) { allowed = false; works() } }
            .exceptionOrNull() is SecurityException)
        allowed = true
        assertEquals(works(2), memo.getOrLoad("late", validate) { works(2) })
    }

    @Test fun clearDuringHitValidationRejectsThePreviouslyReadEntry() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            memo.getOrLoad("actor", {}) { works() }
            val returnValidation = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var checks = 0
            val hit = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", {
                    if (++checks == 2) { returnValidation.complete(Unit); release.await() }
                }) { error("Must initially read the cached entry") } }
            }
            returnValidation.await()
            memo.clear()
            release.complete(Unit)
            assertTrue(hit.await().exceptionOrNull() is CancellationException)
            assertEquals(works(2), memo.getOrLoad("actor", {}) { works(2) })
        }
    }

    @Test fun aLateOldOwnerCannotRemoveTheReplacementFlight() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val oldGate = CompletableDeferred<Unit>()
            val newGate = CompletableDeferred<Unit>()
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", {}) { oldGate.await(); works() } }
            }
            memo.clear()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { newGate.await(); works(2) }
            }
            oldGate.complete(Unit)
            assertTrue(old.await().exceptionOrNull() is CancellationException)
            val joined = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { error("Replacement flight must remain registered") }
            }
            newGate.complete(Unit)
            assertEquals(works(2), replacement.await())
            assertEquals(works(2), joined.await())
        }
    }

    @Test fun waiterRevalidatesItsOwnAccessAfterTheSharedLoadCompletes() = runBlocking {
        withTimeout(3_000) {
            val memo = ActorWorksMemo()
            val gate = CompletableDeferred<Unit>()
            var waiterAllowed = true
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                memo.getOrLoad("actor", {}) { gate.await(); works() }
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { memo.getOrLoad("actor", { check(waiterAllowed) }) { error("Must share load") } }
            }
            waiterAllowed = false
            gate.complete(Unit)
            assertEquals(works(), owner.await())
            assertTrue(waiter.await().exceptionOrNull() is IllegalStateException)
        }
    }
}
