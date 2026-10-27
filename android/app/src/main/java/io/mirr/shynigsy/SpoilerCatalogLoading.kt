package io.mirr.shynigsy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Reserve the first catalog request for an explicit PIN unlock without publishing off Main. */
internal fun <T> CoroutineScope.startPriorityHomeCatalog(load: suspend () -> T): Deferred<Result<T>> =
    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            Result.success(load())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // The caller retains the existing fatal-authentication versus optional-catalog policy.
            Result.failure(error)
        }
    }
