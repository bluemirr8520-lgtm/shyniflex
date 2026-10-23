package io.mirr.shynigsy.player.mpv

/** Full-file duration evidence must not shrink while retrying the same source. */
internal class MpvSourceDuration(sourceDurationMs: Long) {
    private val sourceFloorMs = sourceDurationMs.takeIf { it in 1..MAX_DURATION_MS }

    var durationMs: Long? = sourceFloorMs
        private set

    /** A new MediaItem must not inherit the preceding file's native estimate. */
    fun reset() { durationMs = sourceFloorMs }

    fun observe(nativeDurationSeconds: Double?): Long? {
        nativeMilliseconds(nativeDurationSeconds)?.let { native ->
            durationMs = maxOf(durationMs ?: 0, native)
        }
        return durationMs
    }

    companion object {
        // Media3 stores this duration in microseconds; keep that conversion safe too.
        private const val MAX_DURATION_MS = Long.MAX_VALUE / 1000

        internal fun nativeMilliseconds(seconds: Double?): Long? {
            if (seconds == null || !seconds.isFinite() || seconds <= 0) return null
            val milliseconds = seconds * 1000
            if (!milliseconds.isFinite() || milliseconds < 1 || milliseconds > MAX_DURATION_MS.toDouble()) return null
            return milliseconds.toLong().takeIf { it in 1..MAX_DURATION_MS }
        }
    }
}
