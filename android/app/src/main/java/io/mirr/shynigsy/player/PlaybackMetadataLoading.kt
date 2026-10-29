package io.mirr.shynigsy.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

private data class MarkerAttempt(val error: Exception?)

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

/**
 * Markers normally populate the current file's media-info cache before neighbors reuse it.
 * A transient marker failure gets bounded retries without making neighbors wait for them.
 */
internal suspend fun loadPlaybackMetadataInOrder(
    loadMarkers: suspend () -> Unit,
    loadNeighbors: suspend () -> Unit,
    markerRetryDelaysMs: List<Long> = emptyList(),
    pause: suspend (Long) -> Unit = { delay(it) },
    markerAttemptTimeoutMs: Long? = null,
    markerRetryTimeoutMs: Long? = markerAttemptTimeoutMs,
) = coroutineScope {
    suspend fun attempt(load: suspend () -> Unit): Exception? {
        currentCoroutineContext().ensureActive()
        return try { load(); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { error }
    }
    suspend fun attemptMarker(timeoutMs: Long?): Exception? {
        if (timeoutMs == null) return attempt(loadMarkers)
        require(timeoutMs > 0)
        return (withTimeoutOrNull(timeoutMs) { MarkerAttempt(attempt(loadMarkers)) }
            ?: MarkerAttempt(SocketTimeoutException("Marker request timed out"))).error
    }
    val markerError = attemptMarker(markerAttemptTimeoutMs)
    if (markerError != null && isTransientProgressFailure(markerError) && markerRetryDelaysMs.isNotEmpty()) {
        launch {
            for (delayMs in markerRetryDelaysMs) {
                require(delayMs > 0)
                pause(delayMs)
                val error = attemptMarker(markerRetryTimeoutMs) ?: break
                if (!isTransientProgressFailure(error)) break
            }
        }
    }
    // Neither an optional failure nor retry backoff delays the sibling request.
    attempt(loadNeighbors)
    Unit
}
