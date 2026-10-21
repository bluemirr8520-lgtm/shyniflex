package io.mirr.shynigsy

import io.mirr.shynigsy.data.ApiException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HomeSectionLoadingTest {
    @Test fun continuePublishesWhileRecentIsPendingAndTheGroupWaitsForBoth() = runBlocking {
        withTimeout(30_000) {
            val recentStarted = CompletableDeferred<Unit>()
            val releaseRecent = CompletableDeferred<Unit>()
            val continuePublished = CompletableDeferred<Unit>()
            val group = async {
                loadHomeSections(
                    loadContinue = { continuePublished.complete(Unit) },
                    loadRecent = { recentStarted.complete(Unit); releaseRecent.await() },
                )
            }

            recentStarted.await()
            continuePublished.await()
            assertFalse("A pending recent request must not block Continue publication", group.isCompleted)
            releaseRecent.complete(Unit)
            group.await()
            assertTrue(group.isCompleted)
        }
    }

    @Test fun aRecentFailureHandledByItsCallerDoesNotDiscardContinue() = runBlocking {
        withTimeout(30_000) {
            val recentHandled = CompletableDeferred<Unit>()
            val releaseContinue = CompletableDeferred<Unit>()
            var continuePublications = 0
            val group = async {
                loadHomeSections(
                    loadContinue = { releaseContinue.await(); continuePublications++ },
                    loadRecent = {
                        try {
                            throw IOException("fixture recent failure")
                        } catch (_: IOException) {
                            recentHandled.complete(Unit)
                        }
                    },
                )
            }

            recentHandled.await()
            assertFalse("The handled section error must leave its sibling active", group.isCompleted)
            releaseContinue.complete(Unit)
            group.await()
            assertEquals(1, continuePublications)
        }
    }

    @Test fun anUncaughtAuthenticationFailureCancelsTheOtherSection() = runBlocking {
        withTimeout(30_000) {
            val continueStarted = CompletableDeferred<Unit>()
            val authenticationFailure = ApiException("fixture expired session", 401)
            var continueCancelled = false
            val caught = runCatching {
                loadHomeSections(
                    loadContinue = {
                        continueStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            continueCancelled = true
                        }
                    },
                    loadRecent = { continueStarted.await(); throw authenticationFailure },
                )
            }.exceptionOrNull()

            assertTrue("Authentication failure must reach the owning load", caught is ApiException)
            assertEquals(401, (caught as ApiException).status)
            assertTrue("Structured cancellation must stop the unfinished section", continueCancelled)
        }
    }

    @Test fun parentCancellationPreventsResponsesFromPublishingAfterCancellation() = runBlocking {
        withTimeout(30_000) {
            val continueStarted = CompletableDeferred<Unit>()
            val recentStarted = CompletableDeferred<Unit>()
            val continueResponse = CompletableDeferred<Unit>()
            val recentResponse = CompletableDeferred<Unit>()
            var publications = 0
            val parent = launch {
                loadHomeSections(
                    loadContinue = { continueStarted.complete(Unit); continueResponse.await(); publications++ },
                    loadRecent = { recentStarted.complete(Unit); recentResponse.await(); publications++ },
                )
            }

            continueStarted.await()
            recentStarted.await()
            parent.cancel()
            // Responses arriving after navigation/logout must not revive the load.
            continueResponse.complete(Unit)
            recentResponse.complete(Unit)
            parent.join()
            assertTrue(parent.isCancelled)
            assertEquals(0, publications)
        }
    }
}
