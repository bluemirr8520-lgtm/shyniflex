package io.mirr.shynigsy.player

/** Reauthenticate with server-issued time remaining; no local deadline extension is permitted. */
internal object SpoilerPlaybackReauthenticationPolicy {
    fun delayMs(remainingMs: Long, lifetimeMs: Long): Long {
        val margin = minOf(300_000L, (lifetimeMs.coerceAtLeast(0) / 3).coerceAtLeast(1))
        return (remainingMs.coerceAtLeast(0) - margin).coerceAtLeast(0)
    }

    fun timeoutMs(remainingMs: Long): Long =
        (remainingMs.coerceAtLeast(0) - 250).coerceIn(0, 30_000)

    fun flushTimeoutMs(remainingMs: Long): Long = minOf(2_000, timeoutMs(remainingMs) / 4)
}

/** Old queued/in-flight writes and stale reconnect completions cannot cross a credential rotation. */
internal class PlaybackAuthenticationEpoch {
    private var generation = 0L
    private var accepting = true

    @Synchronized fun captureProgress(): Long? = generation.takeIf { accepting }

    @Synchronized fun isCurrent(value: Long): Boolean = generation == value

    @Synchronized fun beginRotation(): Long {
        accepting = false
        return ++generation
    }

    @Synchronized fun completeRotation(value: Long): Boolean {
        if (generation != value) return false
        accepting = true
        return true
    }
}
