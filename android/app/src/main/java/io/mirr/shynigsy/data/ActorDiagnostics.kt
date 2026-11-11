package io.mirr.shynigsy.data

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Temporary, content-free timing diagnostics for troubleshooting actor lookups. */
internal class ActorDiagnostics(
    private val kind: Kind,
    private val sink: (String) -> Unit = { line -> runCatching { Log.i(TAG, line) }; Unit },
    private val clock: () -> Long = System::nanoTime,
) {
    enum class Kind { VIEW, PROVIDER }
    enum class Event {
        CREATED, SLOT, ENQUEUED, RUNNING, HTTP, CALL_FAILURE, INVALID_JSON,
        BAD_SHAPE, ROWS, RESULT, PARTIAL, TIMEOUT, ERROR, CANCELLED
    }

    private val sequence = nextSequence.incrementAndGet()
    private val started = clock()

    init { event(Event.CREATED) }

    fun event(event: Event, status: Int = 0, count: Int = -1, more: Boolean = false) {
        // Only enum names and integers are permitted: no URLs, identities, exception
        // messages or response data may be passed through this interface.
        val elapsed = ((clock() - started) / 1_000_000L).coerceAtLeast(0L)
        runCatching { sink("n=$sequence kind=${kind.name} event=${event.name} ms=$elapsed status=$status count=$count more=${if (more) 1 else 0}") }
    }

    companion object {
        const val TAG = "ShyniActorDiag"
        private val nextSequence = AtomicLong()
    }
}
