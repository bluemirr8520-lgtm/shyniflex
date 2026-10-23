package io.mirr.shynigsy.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One optional metadata load per prepared source, independent of later cache pauses/seeks. */
internal class PlaybackMetadataGate {
    private var generation = 0L
    private var pending = false

    fun reset() {
        generation++
        pending = true
    }

    fun cancel() {
        generation++
        pending = false
    }

    fun takePending(): Long? {
        if (!pending) return null
        pending = false
        return generation
    }

    fun isCurrent(revision: Long): Boolean = generation == revision
}

/** Markers populate the current file's media-info cache before neighbors reuse it. */
internal suspend fun loadPlaybackMetadataInOrder(loadMarkers: suspend () -> Unit, loadNeighbors: suspend () -> Unit) {
    for (load in listOf(loadMarkers, loadNeighbors)) {
        currentCoroutineContext().ensureActive()
        try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Either optional section may be unavailable without stopping authorized playback.
        }
    }
}
