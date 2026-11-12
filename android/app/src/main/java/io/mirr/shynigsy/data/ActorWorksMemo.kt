package io.mirr.shynigsy.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A short-lived, memory-only memo for complete actor catalogues. The caller owns the account/view
 * key and authorization check. No suspend operation runs under the metadata lock, so unrelated
 * actors load in parallel; the flight map contains only requests that are currently in progress.
 */
internal class ActorWorksMemo(
    private val ttlMs: Long = 120_000L,
    private val maxEntries: Int = 24,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(ttlMs >= 0) { "Actor works TTL must be non-negative" }
        require(maxEntries >= 0) { "Actor works capacity must be non-negative" }
    }

    private data class Entry(val works: ActorWorks, val storedAt: Long)
    private class Flight(val generation: Long) {
        // Deliberately parentless: cancelling a waiter must not cancel the loading caller.
        val result = CompletableDeferred<ActorWorks>()
    }
    private sealed interface Lookup {
        data class Hit(val works: ActorWorks, val generation: Long) : Lookup
        data class Pending(val flight: Flight, val owner: Boolean) : Lookup
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val flights = HashMap<String, Flight>()
    private var generation = 0L

    suspend fun getOrLoad(key: String, validate: suspend () -> Unit, load: suspend () -> ActorWorks): ActorWorks {
        checkAccess(validate)
        val lookup = synchronized(lock) {
            removeExpired(clock())
            entries[key]?.let { Lookup.Hit(it.works, generation) }
                ?: flights[key]?.let { Lookup.Pending(it, owner = false) }
                ?: Flight(generation).let { flight ->
                    flights[key] = flight
                    Lookup.Pending(flight, owner = true)
                }
        }
        when (lookup) {
            is Lookup.Hit -> {
                checkAccess(validate)
                synchronized(lock) { requireCurrent(lookup.generation) }
                return lookup.works
            }
            is Lookup.Pending -> {
                val flight = lookup.flight
                if (!lookup.owner) {
                    val result = try {
                        flight.result.await()
                    } catch (cancel: CancellationException) {
                        // A cancelled previous screen may own this flight. An active replacement
                        // screen can start fresh, but its own cancellation, clear, or revoked grant
                        // must still stop it. Failed owners remove their flight before waking us.
                        checkAccess(validate)
                        synchronized(lock) { requireCurrent(flight.generation) }
                        return getOrLoad(key, validate, load)
                    }
                    checkAccess(validate)
                    synchronized(lock) { requireCurrent(flight.generation) }
                    return result
                }
                try {
                    val result = load()
                    // This is the final suspend point before both publication and return. It also
                    // catches loaders/validators that swallowed their caller's cancellation.
                    checkAccess(validate)
                    synchronized(lock) {
                        requireCurrent(flight.generation)
                        if (!result.incomplete && maxEntries > 0 && ttlMs > 0) {
                            val now = clock()
                            removeExpired(now)
                            entries[key] = Entry(result, now)
                            while (entries.size > maxEntries) entries.entries.iterator().run { next(); remove() }
                        }
                        if (flights[key] === flight) flights.remove(key)
                    }
                    flight.result.complete(result)
                    return result
                } catch (failure: Throwable) {
                    synchronized(lock) { if (flights[key] === flight) flights.remove(key) }
                    flight.result.completeExceptionally(failure)
                    throw failure
                }
            }
        }
    }

    /** Old callers cannot repopulate the memo or return stale results after invalidation. */
    fun clear() {
        val abandoned = synchronized(lock) {
            generation++
            entries.clear()
            flights.values.toList().also { flights.clear() }
        }
        abandoned.forEach { it.result.completeExceptionally(invalidated()) }
    }

    private suspend fun checkAccess(validate: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        validate()
        currentCoroutineContext().ensureActive()
    }

    /** Called only while holding [lock]. A regressed clock cannot extend a result's lifetime. */
    private fun removeExpired(now: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val age = now - iterator.next().storedAt
            if (age < 0 || age >= ttlMs) iterator.remove()
        }
    }

    private fun requireCurrent(expected: Long) {
        if (generation != expected) throw invalidated()
    }

    private fun invalidated() = CancellationException("Actor works were invalidated")
}
