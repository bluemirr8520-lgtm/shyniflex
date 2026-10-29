package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Refresh the visible catalog when a delayed server watch-state write completes. */
object PlaybackCompletionSignals {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun notifyChanged() { revision.update { it + 1 } }

    data class ConfirmedWatch internal constructor(
        val sequence: Long, val scope: String, val item: MediaItem,
        val grantRevision: Long?, val choiceRevision: Long, val confirmedNanos: Long,
    )

    private val sequence = AtomicLong()
    private val confirmationLock = Any()
    private val watchFence = PlaybackWatchChoiceEpoch()
    private val confirmed = MutableStateFlow<List<ConfirmedWatch>>(emptyList())
    val confirmations = confirmed.asStateFlow()
    fun choiceRevision(): Long = watchFence.snapshot()
    fun invalidateForManualChoice(scope: String?, item: MediaItem) = watchFence.record(scope, item)
    fun isChoiceCurrent(scope: String?, item: MediaItem?, revision: Long): Boolean = watchFence.isCurrent(scope, item, revision)

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
