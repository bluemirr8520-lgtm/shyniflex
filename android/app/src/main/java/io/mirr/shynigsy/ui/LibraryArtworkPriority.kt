package io.mirr.shynigsy.ui

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Optional episode counts wait for artwork, while a slow image cannot starve them. */
internal suspend fun awaitLibraryArtwork(
    artworkSettled: Deferred<Unit>,
    minimumDelayMs: Long = 250,
    maxWaitMs: Long = 1_500,
) = coroutineScope {
    require(minimumDelayMs >= 0 && maxWaitMs >= minimumDelayMs)
    val minimumDelay = async { delay(minimumDelayMs) }
    withTimeoutOrNull(maxWaitMs) { artworkSettled.await() }
    minimumDelay.await()
}
