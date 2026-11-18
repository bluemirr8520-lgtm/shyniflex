package io.mirr.shynigsy.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Process-wide FIFO survives a player closing while a user selection is saved. */
internal object LocalSubtitleWrites {
    private sealed interface Command
    private data class Write(val store: LocalSubtitleStore, val owner: String, val id: String?, val selected: Boolean?, val done: (Boolean) -> Unit) : Command
    private data class Barrier(val receipt: CompletableDeferred<Unit>) : Command
    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch {
            for (command in queue) {
                if (command is Barrier) { command.receipt.complete(Unit); continue }
                val write = command as Write
                val success = runCatching {
                    if (write.selected == null) write.store.removeIf(write.owner, requireNotNull(write.id))
                    else if (write.id == null) write.store.selectCurrent(write.owner, write.selected) != null
                    else write.store.select(write.owner, write.id, write.selected) != null
                }.getOrDefault(false)
                runCatching { write.done(success) }
            }
        }
    }
    fun select(store: LocalSubtitleStore, owner: String, id: String, selected: Boolean, done: (Boolean) -> Unit = {}) {
        queue.trySend(Write(store, owner, id, selected, done))
    }
    fun remove(store: LocalSubtitleStore, owner: String, id: String, done: (Boolean) -> Unit = {}) {
        queue.trySend(Write(store, owner, id, null, done))
    }
    fun deselectCurrent(store: LocalSubtitleStore, owner: String, done: (Boolean) -> Unit = {}) {
        queue.trySend(Write(store, owner, null, false, done))
    }
    suspend fun awaitPending() {
        val receipt = CompletableDeferred<Unit>()
        queue.send(Barrier(receipt))
        receipt.await()
    }
}
