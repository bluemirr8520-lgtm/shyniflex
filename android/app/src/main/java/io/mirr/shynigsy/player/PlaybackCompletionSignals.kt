package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Refresh the visible catalog when a delayed server watch-state write completes. */
object PlaybackCompletionSignals {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun notifyChanged() { revision.update { it + 1 } }

    data class ConfirmedWatch internal constructor(
        val sequence: Long, val scope: String, val item: MediaItem,
        val grantRevision: Long?, val choiceRevision: Long, val confirmedNanos: Long,
    )

    enum class LocalCompletionStatus { PENDING, CONFIRMED, FAILED }
    /** A temporary display receipt. It must never be serialized as server watch state. */
    data class LocalCompletion internal constructor(
        val sequence: Long, val scope: String, val item: MediaItem,
        val grantRevision: Long?, val choiceRevision: Long,
        val createdNanos: Long, val expiresNanos: Long, val status: LocalCompletionStatus,
    )

    private val sequence = AtomicLong()
    private val confirmationLock = Any()
    private val watchFence = PlaybackWatchChoiceEpoch()
    private val watchMutation = Mutex()
    private val local = PlaybackCompletionDisplayRegistry(
        choiceCurrent = ::isChoiceCurrent,
        cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val presentation = local.events
    private val confirmed = MutableStateFlow<List<ConfirmedWatch>>(emptyList())
    val confirmations = confirmed.asStateFlow()
    fun choiceRevision(): Long = watchFence.snapshot()
    fun invalidateForManualChoice(scope: String?, item: MediaItem) {
        watchFence.record(scope, item)
        local.removeStale()
    }
    fun isChoiceCurrent(scope: String?, item: MediaItem?, revision: Long): Boolean = watchFence.isCurrent(scope, item, revision)

    /** The enqueue callback must be synchronous and must not perform network work. */
    fun acceptPending(scope: String?, item: MediaItem?, grantRevision: Long?, choiceRevision: Long,
        enqueue: (Long?) -> Boolean): Boolean = local.accept(scope, item, grantRevision, choiceRevision, enqueue)

    fun acknowledgeLocal(sequence: Long?) = local.acknowledge(sequence)
    fun failLocal(sequence: Long?, showFailure: Boolean = true) = local.fail(sequence, showFailure)
    fun discardLocal(sequence: Long?) = local.fail(sequence, showFailure = false)
    fun discardLocals(sequences: Collection<Long>) = local.discard(sequences)

    /** Manual choices record their fence before waiting, so older queued writes skip. */
    suspend fun <T> withWatchMutation(write: suspend () -> T): T = watchMutation.withLock { write() }

    /** Identity is retained only as a digest; credentials are never logged or serialized. */
    fun scope(server: String, token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val input = (server + "\u0000" + token).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
    }

    fun confirm(scope: String?, item: MediaItem?, grantRevision: Long?, choiceRevision: Long) {
        if (scope == null || item == null || item.id <= 0 || item.libraryId <= 0 ||
            item.viewItemId == null || item.viewItemId <= 0 || !item.playable || !isChoiceCurrent(scope, item, choiceRevision)) return
        // Sequence and publication share one lock so a later event cannot overtake
        // an earlier one before the consumer advances its last-seen sequence.
        synchronized(confirmationLock) {
            val event = ConfirmedWatch(sequence.incrementAndGet(), scope, item, grantRevision, choiceRevision, System.nanoTime())
            confirmed.value = (confirmed.value + event).takeLast(32)
        }
    }
}
