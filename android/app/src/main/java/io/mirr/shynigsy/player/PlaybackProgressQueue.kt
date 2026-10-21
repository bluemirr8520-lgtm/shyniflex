package io.mirr.shynigsy.player

import java.util.ArrayDeque
import kotlinx.coroutines.channels.Channel

/** Coalesce routine ticks without dropping completion or exit snapshots. */
internal class PlaybackProgressQueue<T : Any> {
    private data class Entry<T>(val value: T, val important: Boolean)
    private val lock = Any()
    private val pending = ArrayDeque<Entry<T>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    fun submit(value: T, important: Boolean = false): Boolean = synchronized(lock) {
        if (closed) return false
        // Preserve order around important snapshots, including a final EOF write.
        if (!important && pending.peekLast()?.important == false) pending.removeLast()
        pending.addLast(Entry(value, important))
        wake.trySend(Unit)
        true
    }

    suspend fun next(): T? {
        while (true) {
            val entry = synchronized(lock) {
                pending.pollFirst() ?: if (closed) return null else null
            }
            if (entry != null) return entry.value
            wake.receiveCatching()
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        wake.close()
    }
}
