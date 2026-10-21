package io.mirr.shynigsy.player.mpv

import java.net.URLDecoder

/** Progressive conversion has a local zero clock and is reopened for each seek. */
internal class ServerConversionTimeline(sourceDurationMs: Long) {
    val sourceDurationMs: Long = sourceDurationMs.coerceAtLeast(0)
    var streamStartPositionMs: Long = 0
        private set

    data class Load(val url: String, val sourcePositionMs: Long, val nativeStartMs: Long = 0)

    fun reopen(url: String, sourcePositionMs: Long): Load {
        streamStartPositionMs = clamp(sourcePositionMs)
        return Load(withStartPosition(url, streamStartPositionMs), streamStartPositionMs)
    }

    fun sourcePosition(nativePositionMs: Long): Long = clamp(add(streamStartPositionMs, nativePositionMs.coerceAtLeast(0)))

    fun bufferedPosition(nativePositionMs: Long, bufferedDurationMs: Long): Long =
        sourcePosition(add(nativePositionMs.coerceAtLeast(0), bufferedDurationMs.coerceAtLeast(0)))

    /** A fragment's missing or remaining duration must not replace the title length. */
    fun duration(nativeDurationMs: Long?): Long? = sourceDurationMs.takeIf { it > 0 }
        ?: nativeDurationMs?.takeIf { it > 0 }?.let { add(streamStartPositionMs, it) }

    fun clamp(positionMs: Long): Long = if (sourceDurationMs > 0) positionMs.coerceIn(0, sourceDurationMs)
        else positionMs.coerceAtLeast(0)

    companion object {
        /** Preserve the signed ticket and all other raw URL components byte for byte. */
        private fun withStartPosition(url: String, positionMs: Long): String {
            val fragmentIndex = url.indexOf('#')
            val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
            val resource = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
            val queryIndex = resource.indexOf('?')
            val path = if (queryIndex >= 0) resource.substring(0, queryIndex) else resource
            val query = if (queryIndex >= 0) resource.substring(queryIndex + 1) else ""
            val kept = query.split('&').filter { parameter ->
                val key = parameter.substringBefore('=')
                val decoded = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
                parameter.isNotEmpty() && decoded != "start_ms"
            }
            return path + "?" + (kept + "start_ms=$positionMs").joinToString("&") + fragment
        }

        private fun add(left: Long, right: Long): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
