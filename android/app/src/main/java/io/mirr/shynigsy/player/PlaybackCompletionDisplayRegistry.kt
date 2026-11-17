package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.player.PlaybackCompletionSignals.LocalCompletion
import io.mirr.shynigsy.player.PlaybackCompletionSignals.LocalCompletionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-local UI hints; accepted progress writes remain owned by the player writer. */
internal class PlaybackCompletionDisplayRegistry(
    private val choiceCurrent: (String?, MediaItem?, Long) -> Boolean,
    private val now: () -> Long = System::nanoTime,
    private val cleanupScope: CoroutineScope? = null,
) {
    private val lock = Any()
    private var nextSequence = 0L
    private val mutableEvents = MutableStateFlow<List<LocalCompletion>>(emptyList())
    val events = mutableEvents.asStateFlow()
    private var cleanupJob: Job? = null
    private var cleanupGeneration = 0L

    fun accept(scope: String?, item: MediaItem?, grantRevision: Long?, choiceRevision: Long,
        enqueue: (Long?) -> Boolean): Boolean = synchronized(lock) {
        removeStaleLocked()
        if (scope == null || item == null || item.id <= 0 || item.libraryId <= 0 ||
            item.viewItemId == null || item.viewItemId <= 0 || !item.playable ||
            !choiceCurrent(scope, item, choiceRevision)) return@synchronized enqueue(null)
        val sequence = ++nextSequence
        // Outcomes take the same lock: even an immediately awakened IO writer
        // cannot confirm or reject this token before its accepted hint exists.
        if (!enqueue(sequence)) return@synchronized false
        val time = now()
        mutableEvents.value = (mutableEvents.value + LocalCompletion(sequence, scope, item,
            grantRevision, choiceRevision, time, time + PENDING_LIFETIME_NANOS,
            LocalCompletionStatus.PENDING)).takeLast(MAX_RECEIPTS)
        scheduleCleanupLocked()
        true
    }

    fun acknowledge(sequence: Long?): Boolean = transition(sequence, LocalCompletionStatus.CONFIRMED)
    fun fail(sequence: Long?, showFailure: Boolean): Boolean =
        transition(sequence, if (showFailure) LocalCompletionStatus.FAILED else null)

    private fun transition(sequence: Long?, status: LocalCompletionStatus?): Boolean = synchronized(lock) {
        if (sequence == null) return@synchronized false
        removeStaleLocked()
        val receipt = mutableEvents.value.firstOrNull { it.sequence == sequence &&
            it.status == LocalCompletionStatus.PENDING } ?: return@synchronized false
        val replacement = status?.let { receipt.copy(status = it, expiresNanos = now() + RESOLVED_LIFETIME_NANOS) }
        // A single publication prevents an un-watched frame between pending and ACK.
        mutableEvents.value = mutableEvents.value.mapNotNull { if (it.sequence == sequence) replacement else it }
        scheduleCleanupLocked()
        true
    }

    fun discard(sequences: Collection<Long>) = synchronized(lock) {
        val discarded = sequences.toHashSet()
        mutableEvents.value = mutableEvents.value.filterNot {
            it.status == LocalCompletionStatus.PENDING && it.sequence in discarded
        }
        removeStaleLocked()
        scheduleCleanupLocked()
    }

    fun removeStale() = synchronized(lock) {
        removeStaleLocked()
        scheduleCleanupLocked()
    }

    private fun removeStaleLocked() {
        val time = now()
        mutableEvents.value = mutableEvents.value.filter {
            time < it.expiresNanos && choiceCurrent(it.scope, it.item, it.choiceRevision)
        }
    }

    private fun scheduleCleanupLocked() {
        val generation = ++cleanupGeneration
        cleanupJob?.cancel()
        val scope = cleanupScope ?: return
        val deadline = mutableEvents.value.minOfOrNull { it.expiresNanos } ?: return
        cleanupJob = scope.launch {
            delay(((deadline - now()) / 1_000_000L).coerceAtLeast(1L))
            synchronized(lock) {
                if (generation != cleanupGeneration) return@synchronized
                cleanupJob = null
                removeStaleLocked()
                scheduleCleanupLocked()
            }
        }
    }

    companion object {
        // EOF can wait behind a threshold write; each write has two bounded retry sequences.
        internal const val PENDING_LIFETIME_NANOS = 120_000_000_000L
        internal const val RESOLVED_LIFETIME_NANOS = 60_000_000_000L
        internal const val MAX_RECEIPTS = 32
    }
}
