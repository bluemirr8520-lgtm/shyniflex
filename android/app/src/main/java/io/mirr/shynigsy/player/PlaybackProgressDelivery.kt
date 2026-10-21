package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.ApiException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

private data class ProgressAttempt<T>(val value: T)

internal fun isTransientProgressFailure(error: Exception): Boolean {
    if (error is CancellationException) return false
    if (error is ApiException && (error.status == 408 || error.status == 429 || error.status in 500..599)) return true
    val transport = when {
        error is IOException -> error
        error is ApiException && error.status == 0 -> error.cause as? IOException
        else -> null
    } ?: return false
    return transport !is ProtocolException && transport !is SSLHandshakeException && transport !is SSLPeerUnverifiedException
}

/** Retry this immutable snapshot only; authorization is rechecked by each send. */
internal suspend fun <T> retryPlaybackProgress(
    pause: suspend (Long) -> Unit = { delay(it) },
    attemptTimeoutMs: Long = 5_000,
    send: suspend () -> T,
): T {
    require(attemptTimeoutMs > 0)
    val backoffMs = longArrayOf(250, 1_000)
    for (attempt in 0..backoffMs.size) {
        currentCoroutineContext().ensureActive()
        try {
            // Our own budget expiry is a retryable transport timeout. An outer
            // cancellation still propagates, and cancels the OkHttp call.
            val response = withTimeoutOrNull(attemptTimeoutMs) { ProgressAttempt(send()) }
                ?: throw SocketTimeoutException("Playback progress request timed out")
            return response.value
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (attempt == backoffMs.size || !isTransientProgressFailure(error)) throw error
            pause(backoffMs[attempt])
        }
    }
    error("Unreachable progress retry state")
}

/** A Home refresh and a successful acknowledgement require a committed write. */
internal suspend fun deliverPlaybackProgress(
    acknowledgement: CompletableDeferred<Boolean>? = null,
    onSaved: () -> Unit = {},
    send: suspend () -> Boolean,
): Boolean {
    var saved = false
    try {
        saved = send()
        if (saved) onSaved()
        return saved
    } finally {
        // Cancellation and rejected/failed writes must not acknowledge success.
        acknowledgement?.complete(saved)
    }
}
