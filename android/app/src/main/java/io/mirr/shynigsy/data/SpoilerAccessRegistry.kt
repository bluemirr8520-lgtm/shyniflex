package io.mirr.shynigsy.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground-only capabilities and PINs. Nothing in this store is serialized. */
object SpoilerAccessRegistry {
    internal data class Grant(val token: String, val expiresAtMs: Long, val deadlineNanos: Long,
        val lifetimeMs: Long, val generation: Long)
    internal data class Started(val wallMs: Long, val nanos: Long)
    internal data class Unlock(val generation: Long, val revision: Long)
    internal class Reauthentication(val unlock: Unlock, val grant: Grant, val pin: CharArray)
    private val store = SpoilerGrantStore()
    val changes: StateFlow<Long> = store.changes

    internal fun get(key: String) = store.get(key)
    internal fun started() = store.started()
    internal fun remainingMs(key: String) = store.remainingMs(key)
    internal fun failedReauthentication(key: String) = store.failedReauthentication(key)
    internal fun beginUnlock(key: String) = store.beginUnlock(key)
    internal fun completeUnlock(key: String, unlock: Unlock, token: String, seconds: Long,
        pin: CharArray, started: Started) = store.completeUnlock(key, unlock, token, seconds, pin, started)
    internal fun beginReauthentication(key: String) = store.beginReauthentication(key)
    internal fun completeReauthentication(key: String, attempt: Reauthentication, token: String,
        seconds: Long, started: Started) = store.completeReauthentication(key, attempt, token, seconds, started)
    internal fun cacheReauthenticatedPin(key: String, grant: Grant, attempt: Reauthentication) =
        store.cacheReauthenticatedPin(key, grant, attempt)
    internal fun finishReauthentication(key: String, attempt: Reauthentication) = store.finishReauthentication(key, attempt)
    internal fun failReauthentication(key: String, grant: Grant) = store.failReauthentication(key, grant)
    internal fun isCurrent(key: String, grant: Grant) = store.isCurrent(key, grant)
    internal fun remove(key: String) = store.remove(key)
    fun clearAll() = store.clearAll()
}

/** Injectable clocks keep expiry and late-response tests independent of real sleeps. */
internal class SpoilerGrantStore(
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val nanos: () -> Long = { System.nanoTime() },
) {
    private data class Entry(var grant: SpoilerAccessRegistry.Grant, var pin: CharArray?)
    private val entries = mutableMapOf<String, Entry>()
    private val generations = mutableMapOf<String, Long>()
    private val pendingPins = mutableMapOf<String, CharArray>()
    private val failed = mutableSetOf<String>()
    private var sequence = 0L
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision.asStateFlow()

    fun started() = SpoilerAccessRegistry.Started(wallMs(), nanos())

    private fun wipe(key: String) {
        entries.remove(key)?.pin?.fill('\u0000')
        pendingPins.remove(key)?.fill('\u0000')
        generations.remove(key)
    }

    private fun live(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (entry.grant.deadlineNanos - nanos() <= 0) {
            wipe(key); failed.remove(key); revision.value += 1
            return null
        }
        return entry
    }

    @Synchronized fun get(key: String) = live(key)?.grant
    @Synchronized fun remainingMs(key: String): Long = live(key)?.let {
        ((it.grant.deadlineNanos - nanos()).coerceAtLeast(0) / 1_000_000)
    } ?: 0
    @Synchronized fun failedReauthentication(key: String) = key in failed
    @Synchronized fun isCurrent(key: String, grant: SpoilerAccessRegistry.Grant): Boolean =
        live(key)?.grant?.let { it.generation == grant.generation && it.token == grant.token } == true

    /** Beginning a new PIN request fences older requests without revoking a still-live grant. */
    @Synchronized fun beginUnlock(key: String): SpoilerAccessRegistry.Unlock {
        val entry = live(key)
        entry?.pin?.fill('\u0000'); entry?.pin = null
        pendingPins.remove(key)?.fill('\u0000')
        val generation = ++sequence
        generations[key] = generation
        if (entry != null) entry.grant = entry.grant.copy(generation = generation)
        return SpoilerAccessRegistry.Unlock(generation, revision.value)
    }

    private fun validUnlock(key: String, unlock: SpoilerAccessRegistry.Unlock) =
        generations[key] == unlock.generation && revision.value == unlock.revision

    private fun grant(token: String, seconds: Long, started: SpoilerAccessRegistry.Started): SpoilerAccessRegistry.Grant? {
        val lifetime = seconds.coerceIn(1, 86_400) * 1000
        val deadline = started.nanos + lifetime * 1_000_000
        if (deadline - nanos() <= 0) return null
        return SpoilerAccessRegistry.Grant(token, started.wallMs + lifetime, deadline, lifetime, ++sequence)
    }

    @Synchronized fun completeUnlock(key: String, unlock: SpoilerAccessRegistry.Unlock, token: String,
        seconds: Long, pin: CharArray, started: SpoilerAccessRegistry.Started): Boolean {
        if (!validUnlock(key, unlock)) return false
        val next = grant(token, seconds, started) ?: return false
        wipe(key)
        entries[key] = Entry(next, pin.copyOf())
        generations[key] = next.generation
        failed.remove(key); revision.value += 1
        return true
    }

    /** Consume the cached PIN before networking: an unknown outcome must never retry it silently. */
    @Synchronized fun beginReauthentication(key: String): SpoilerAccessRegistry.Reauthentication? {
        val entry = live(key) ?: return null
        val pin = entry.pin ?: return null
        val copy = pin.copyOf()
        pin.fill('\u0000'); entry.pin = null
        val generation = ++sequence
        generations[key] = generation
        entry.grant = entry.grant.copy(generation = generation)
        pendingPins[key] = copy
        return SpoilerAccessRegistry.Reauthentication(
            SpoilerAccessRegistry.Unlock(generation, revision.value), entry.grant, copy)
    }

    @Synchronized fun completeReauthentication(key: String, attempt: SpoilerAccessRegistry.Reauthentication,
        token: String, seconds: Long, started: SpoilerAccessRegistry.Started): SpoilerAccessRegistry.Grant? {
        if (!isCurrent(key, attempt.grant) || !validUnlock(key, attempt.unlock) ||
            pendingPins[key] !== attempt.pin) return null
        val next = grant(token, seconds, started) ?: return null
        // Retain this tracked in-flight PIN only until fresh playback is fully authorized.
        entries[key] = Entry(next, null)
        generations[key] = next.generation
        failed.remove(key); revision.value += 1
        return next
    }

    @Synchronized fun cacheReauthenticatedPin(key: String, grant: SpoilerAccessRegistry.Grant,
        attempt: SpoilerAccessRegistry.Reauthentication): Boolean {
        if (!isCurrent(key, grant) || pendingPins[key] !== attempt.pin) return false
        entries[key]?.pin = attempt.pin.copyOf()
        pendingPins.remove(key)
        return true
    }

    @Synchronized fun finishReauthentication(key: String, attempt: SpoilerAccessRegistry.Reauthentication) {
        if (pendingPins[key] === attempt.pin) pendingPins.remove(key)
        attempt.pin.fill('\u0000')
    }

    @Synchronized fun failReauthentication(key: String, grant: SpoilerAccessRegistry.Grant): Boolean {
        if (!isCurrent(key, grant)) return false
        wipe(key); failed.add(key); revision.value += 1
        return true
    }

    @Synchronized fun remove(key: String) {
        wipe(key); failed.remove(key); revision.value += 1
    }

    @Synchronized fun clearAll() {
        (entries.keys + generations.keys + pendingPins.keys).toSet().forEach(::wipe)
        failed.clear(); revision.value += 1
    }
}
