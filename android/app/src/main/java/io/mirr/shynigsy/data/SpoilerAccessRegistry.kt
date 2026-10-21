package io.mirr.shynigsy.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-only capabilities. No PIN, account token or grant is written to storage. */
object SpoilerAccessRegistry {
    internal class Grant(val token: String, val expiresAtMs: Long, val deadlineNanos: Long)
    private val grants = mutableMapOf<String, Grant>()
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision.asStateFlow()

    @Synchronized internal fun get(key: String): Grant? {
        val grant = grants[key] ?: return null
        if (System.nanoTime() >= grant.deadlineNanos) {
            grants.remove(key); revision.value += 1
            return null
        }
        return grant
    }

    @Synchronized internal fun put(key: String, token: String, seconds: Long) {
        val lifetime = seconds.coerceIn(1, 86_400)
        grants[key] = Grant(token, System.currentTimeMillis() + lifetime * 1000,
            System.nanoTime() + lifetime * 1_000_000_000)
        revision.value += 1
    }

    @Synchronized internal fun remove(key: String) {
        grants.remove(key); revision.value += 1
    }

    @Synchronized fun clearAll() {
        grants.clear(); revision.value += 1
    }
}
