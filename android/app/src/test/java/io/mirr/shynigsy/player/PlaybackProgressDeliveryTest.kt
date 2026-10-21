package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.ApiException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressDeliveryTest {
    private fun assertPreservedFailure(expected: Exception, actual: Throwable?) {
        assertNotNull(actual)
        assertEquals(expected.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stack-trace recovery may wrap the original in a copy of
        // the same exception. It must still retain that original as a cause.
        assertTrue("The original failure must remain in the causal chain",
            generateSequence(actual) { it.cause }.take(16).any { it === expected })
    }

    @Test fun transportFailuresRetryTheSameOperationWithBoundedBackoff() = runBlocking {
        val pauses = mutableListOf<Long>()
        var attempts = 0
        val result = retryPlaybackProgress(pause = { pauses += it }) {
            attempts++
            if (attempts < 3) throw IOException("temporary fixture failure")
            "committed"
        }
        assertEquals("committed", result)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 1_000L), pauses)
    }

    @Test fun wrappedTransportFailureRetainsItsRetryClassification() = runBlocking {
        var attempts = 0
        retryPlaybackProgress(pause = {}) {
            if (++attempts == 1) throw ApiException("fixture transport failure").apply {
                initCause(IOException("fixture cause"))
            }
        }
        assertEquals(2, attempts)
    }

    @Test fun temporaryHttpStatusesRetryButAuthorizationAndValidationDoNot() = runBlocking {
        for (status in listOf(408, 429, 500, 502, 503, 599)) {
            var attempts = 0
            retryPlaybackProgress(pause = {}) {
                if (++attempts == 1) throw ApiException("fixture", status)
            }
            assertEquals("HTTP $status", 2, attempts)
        }
        for (status in listOf(0, 400, 401, 403, 404, 409, 422)) {
            var attempts = 0
            val failure = ApiException("fixture", status)
            val caught = runCatching {
                retryPlaybackProgress(pause = { fail("HTTP $status must not back off") }) {
                    attempts++
                    throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals("HTTP $status", 1, attempts)
        }
    }

    @Test fun permanentTlsAndProtocolFailuresAreNotRetried() = runBlocking {
        for (failure in listOf(ProtocolException("fixture"), SSLHandshakeException("fixture"), SSLPeerUnverifiedException("fixture"))) {
            var attempts = 0
            val caught = runCatching {
                retryPlaybackProgress(pause = { fail("Permanent transport failure must not back off") }) {
                    attempts++
                    throw failure
                }
            }.exceptionOrNull()
            assertPreservedFailure(failure, caught)
            assertEquals(1, attempts)
        }
    }

    @Test fun retryBudgetStopsAfterThreeAttempts() = runBlocking {
        var attempts = 0
        val pauses = mutableListOf<Long>()
        val failure = IOException("fixture")
        val caught = runCatching {
            retryPlaybackProgress(pause = { pauses += it }) {
                attempts++
                throw failure
            }
        }.exceptionOrNull()
        assertPreservedFailure(failure, caught)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 1_000L), pauses)
    }

    @Test fun requestCancellationPropagatesWithoutRetry() = runBlocking {
        var attempts = 0
        val cancel = CancellationException("fixture")
        val caught = runCatching {
            retryPlaybackProgress(pause = { fail("Cancellation must not retry") }) {
                attempts++
                throw cancel
            }
        }.exceptionOrNull()
        assertPreservedFailure(cancel, caught)
        assertEquals(1, attempts)
    }

    @Test fun cancellationDuringBackoffPreventsAnotherRequest() = runBlocking {
        var attempts = 0
        val cancel = CancellationException("fixture backoff cancellation")
        val caught = runCatching {
            retryPlaybackProgress(pause = { throw cancel }) {
                attempts++
                throw IOException("fixture")
            }
        }.exceptionOrNull()
        assertSame(cancel, caught)
        assertEquals(1, attempts)
    }

    @Test fun privateAccessRevocationOnRetryStopsImmediately() = runBlocking {
        var attempts = 0
        val caught = runCatching {
            retryPlaybackProgress(pause = {}) {
                attempts++
                if (attempts == 1) throw IOException("temporary fixture failure")
                throw ApiException("fixture grant revoked", 403)
            }
        }.exceptionOrNull() as ApiException
        assertEquals(403, caught.status)
        assertEquals(2, attempts)
    }

    @Test fun stalledRequestsAreCancelledWithinThePerAttemptBudget() = runBlocking {
        var attempts = 0
        var cancelledRequests = 0
        val caught = runCatching {
            retryPlaybackProgress(pause = {}, attemptTimeoutMs = 10) {
                attempts++
                try { awaitCancellation() } finally { cancelledRequests++ }
            }
        }.exceptionOrNull()
        assertTrue(caught is SocketTimeoutException)
        assertEquals(3, attempts)
        assertEquals(3, cancelledRequests)
    }

    @Test fun foreignTimeoutIsNotConvertedIntoATransportRetry() = runBlocking {
        var attempts = 0
        val caught = runCatching {
            retryPlaybackProgress(pause = { fail("A foreign timeout must not retry") }, attemptTimeoutMs = 60_000) {
                attempts++
                // Start the foreign timeout only after the request has entered.
                // An outer 10ms budget can expire before entry on a loaded JVM.
                withTimeout(10) { awaitCancellation() }
            }
        }.exceptionOrNull()
        assertTrue(caught is CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun revokedPrivateGrantFailsAcknowledgementAndDoesNotRefresh() = runBlocking {
        var attempts = 0
        var refreshed = false
        val acknowledgement = CompletableDeferred<Boolean>()
        val saved = deliverPlaybackProgress(acknowledgement, onSaved = { refreshed = true }) {
            try {
                retryPlaybackProgress(pause = {}) {
                    if (++attempts == 1) throw IOException("fixture temporary failure")
                    throw ApiException("fixture private grant revoked", 403)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: ApiException) {
                false
            }
        }
        assertFalse(saved)
        assertFalse(acknowledgement.await())
        assertFalse(refreshed)
        assertEquals(2, attempts)
    }

    @Test fun acknowledgementAndRefreshFollowSuccessfulCommit() = runBlocking {
        val acknowledgement = CompletableDeferred<Boolean>()
        val events = mutableListOf<String>()
        val saved = deliverPlaybackProgress(acknowledgement, onSaved = {
            assertFalse(acknowledgement.isCompleted)
            events += "refresh"
        }) {
            assertFalse(acknowledgement.isCompleted)
            events += "commit"
            true
        }
        assertTrue(saved)
        assertTrue(acknowledgement.await())
        assertEquals(listOf("commit", "refresh"), events)
    }

    @Test fun rejectedSaveAcknowledgesFailureWithoutRefresh() = runBlocking {
        val acknowledgement = CompletableDeferred<Boolean>()
        var refreshes = 0
        assertFalse(deliverPlaybackProgress(acknowledgement, onSaved = { refreshes++ }) { false })
        assertFalse(acknowledgement.await())
        assertEquals(0, refreshes)
    }

    @Test fun thrownFailureAcknowledgesFailureWithoutRefresh() = runBlocking {
        val acknowledgement = CompletableDeferred<Boolean>()
        val failure = IOException("fixture")
        var refreshes = 0
        val caught = runCatching {
            deliverPlaybackProgress(acknowledgement, onSaved = { refreshes++ }) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertFalse(acknowledgement.await())
        assertEquals(0, refreshes)
    }

    @Test fun cancelledSaveCannotBecomeSuccessfulAcknowledgement() = runBlocking {
        val acknowledgement = CompletableDeferred<Boolean>()
        val cancel = CancellationException("fixture")
        val caught = runCatching {
            deliverPlaybackProgress(acknowledgement, onSaved = { fail("Cancelled save must not refresh") }) { throw cancel }
        }.exceptionOrNull()
        assertSame(cancel, caught)
        assertFalse(acknowledgement.await())
    }

    @Test fun latePartialCommitSignalsHomeAfterItsInitialFetch() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Boolean>()
        val revisionBefore = PlaybackCompletionSignals.changes.value
        val sender = async(start = CoroutineStart.UNDISPATCHED) {
            deliverPlaybackProgress(acknowledgement, onSaved = { PlaybackCompletionSignals.notifyChanged() }) {
                committed.await()
                true
            }
        }
        // Home's initial fetch has returned before the final progress POST.
        assertFalse(acknowledgement.isCompleted)
        assertEquals(revisionBefore, PlaybackCompletionSignals.changes.value)
        committed.complete(Unit)
        assertTrue(withTimeout(1_000) { sender.await() })
        assertTrue(acknowledgement.await())
        assertEquals(revisionBefore + 1, PlaybackCompletionSignals.changes.value)
    }

    @Test fun failedLateSaveDoesNotPublishAFalseHomeSuccess() = runBlocking {
        val before = PlaybackCompletionSignals.changes.value
        val acknowledgement = CompletableDeferred<Boolean>()
        assertFalse(deliverPlaybackProgress(acknowledgement, onSaved = { PlaybackCompletionSignals.notifyChanged() }) { false })
        assertFalse(acknowledgement.await())
        assertEquals(before, PlaybackCompletionSignals.changes.value)
    }
}
