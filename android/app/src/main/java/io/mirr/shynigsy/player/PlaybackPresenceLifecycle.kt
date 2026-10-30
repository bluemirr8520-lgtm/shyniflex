package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec

/** A best-effort presence connection; progress history is saved independently. */
internal interface PlaybackPresenceConnection {
    fun update(positionMs: Long, durationMs: Long, state: String)
    fun close(endSession: Boolean)
}

internal fun samePlaybackPresenceSource(a: PlaybackSpec, b: PlaybackSpec): Boolean =
    a.partId == b.partId && a.itemId == b.itemId && a.libraryId == b.libraryId &&
        a.viewItemId == b.viewItemId && a.sessionId == b.sessionId &&
        a.progressToken == b.progressToken && (a.sourceItem?.isSpoiler == true) == (b.sourceItem?.isSpoiler == true)

/** Owned by the player thread. Stale playback updates cannot affect a newer session. */
internal class PlaybackPresenceLifecycle(
    private val open: (PlaybackSpec, (String?) -> Unit) -> PlaybackPresenceConnection?,
    private val onForceStop: (PlaybackSpec, Long, String?) -> Unit = { _, _, _ -> },
) {
    private data class Identity(val part: Int, val item: Int, val library: Int, val view: Int?,
        val session: String?, val context: String?, val spoiler: Boolean)

    private fun identity(spec: PlaybackSpec) = Identity(spec.partId, spec.itemId, spec.libraryId,
        spec.viewItemId, spec.sessionId, spec.progressToken, spec.sourceItem?.isSpoiler == true)

    private var activeIdentity: Identity? = null
    private var active: PlaybackPresenceConnection? = null
    private var generation = 0L

    fun isCurrent(spec: PlaybackSpec, revision: Long): Boolean =
        active != null && generation == revision && activeIdentity == identity(spec)

    fun update(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String) {
        val key = identity(spec)
        if (state == "stopped" || state == "completed") {
            if (key == activeIdentity) close(endSession = true)
            return
        }
        if (state !in setOf("playing", "paused", "buffering") || spec.sessionId.isNullOrBlank() ||
            spec.partId <= 0 || spec.libraryId <= 0 || spec.viewItemId?.let { it > 0 } != true) return
        if (key != activeIdentity) {
            // Only stop the old session when the new one has a different ID;
            // a delayed stop for the same ID could otherwise remove its successor.
            close(endSession = activeIdentity?.session != null && activeIdentity?.session != key.session)
            val revision = ++generation
            active = runCatching { open(spec) { message -> onForceStop(spec, revision, message) } }.getOrNull()
            activeIdentity = if (active != null) key else null
        }
        runCatching { active?.update(positionMs, durationMs, state) }
    }

    fun close(endSession: Boolean) {
        generation++
        val old = active
        active = null
        activeIdentity = null
        runCatching { old?.close(endSession) }
    }
}
