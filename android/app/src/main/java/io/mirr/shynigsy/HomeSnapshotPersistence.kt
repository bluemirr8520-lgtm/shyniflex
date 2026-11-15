package io.mirr.shynigsy

import io.mirr.shynigsy.data.HomeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One writer owns disk mutations; a clear is a barrier that save coalescing cannot erase. */
internal class HomeSnapshotPersistence(
    scope: CoroutineScope,
    private val write: suspend (String, HomeSnapshot) -> Unit,
    private val clear: suspend () -> Unit,
    private val debounceMs: Long = 350,
) {
    private data class Pending(val scopeKey: String, val snapshot: HomeSnapshot, val revision: Long, val atNanos: Long)
    private sealed interface Action {
        data object Clear : Action
        data class Write(val pending: Pending) : Action
        data class Wait(val remainingMs: Long) : Action
        data object Idle : Action
    }

    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var desiredScope: String? = null
    private var revision = 0L
    private var pending: Pending? = null
    private var clearPending = false
    private var closed = false

    init {
        require(debounceMs >= 0)
        scope.launch {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    when (val action = nextAction()) {
                        Action.Clear -> bestEffort { clear.invoke() }
                        is Action.Write -> {
                            val current = synchronized(lock) {
                                !closed && revision == action.pending.revision && desiredScope == action.pending.scopeKey
                            }
                            if (current) bestEffort { write(action.pending.scopeKey, action.pending.snapshot) }
                        }
                        is Action.Wait -> withTimeoutOrNull(action.remainingMs) { signal.receive() }
                        Action.Idle -> signal.receive()
                    }
                }
            } finally {
                synchronized(lock) {
                    closed = true
                    pending = null
                    desiredScope = null
                    clearPending = false
                }
                signal.close()
            }
        }
    }

    fun save(scopeKey: String, snapshot: HomeSnapshot) {
        synchronized(lock) {
            if (closed) return
            if (desiredScope != null && desiredScope != scopeKey) clearPending = true
            desiredScope = scopeKey
            pending = Pending(scopeKey, snapshot, ++revision, System.nanoTime())
            signal.trySend(Unit)
        }
    }

    fun clear() {
        synchronized(lock) {
            if (closed) return
            revision++
            desiredScope = null
            pending = null
            clearPending = true
            signal.trySend(Unit)
        }
    }

    private fun nextAction(): Action = synchronized(lock) {
        if (clearPending) {
            clearPending = false
            return@synchronized Action.Clear
        }
        val save = pending ?: return@synchronized Action.Idle
        val elapsedMs = (System.nanoTime() - save.atNanos) / 1_000_000
        val remainingMs = debounceMs - elapsedMs
        if (remainingMs > 0) return@synchronized Action.Wait(remainingMs)
        pending = null
        Action.Write(save)
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A cache failure must not fail navigation or authentication. */ }
    }
}
