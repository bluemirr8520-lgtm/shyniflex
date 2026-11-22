package io.mirr.shynigsy.player

internal data class StudyInterval(val startMs: Long, val endMs: Long)

internal sealed interface StudyRepeatDecision {
    data object Idle : StudyRepeatDecision
    data class Seek(val positionMs: Long) : StudyRepeatDecision
    data object Waiting : StudyRepeatDecision
    data object Failed : StudyRepeatDecision
}

/** Source-clock repeat state; the activity owns readiness, visibility and seek execution. */
internal class LanguageStudyRepeat {
    var startMs: Long? = null
        private set
    var range: StudyInterval? = null
        private set
    var sentence: Boolean = false
        private set
    private var seekRequestedAtMs: Long? = null

    fun markStart(positionMs: Long) {
        clear()
        startMs = positionMs.coerceAtLeast(0)
    }

    fun markEnd(positionMs: Long, durationMs: Long): Boolean {
        val start = startMs ?: return false
        val valid = validated(StudyInterval(start, positionMs), durationMs) ?: return false
        setRange(valid, sentence = false)
        return true
    }

    fun repeatSentence(interval: StudyInterval, durationMs: Long): Boolean {
        val valid = validated(interval, durationMs) ?: return false
        setRange(valid, sentence = true)
        return true
    }

    fun clear() {
        startMs = null
        range = null
        sentence = false
        resetPending()
    }

    fun cancelSentence() {
        if (sentence) clear()
    }

    /** A replacement engine can retain the range without inheriting an old player's seek. */
    fun resetPending() {
        seekRequestedAtMs = null
    }

    fun poll(positionMs: Long, ended: Boolean, nowMs: Long): StudyRepeatDecision {
        val interval = range ?: return StudyRepeatDecision.Idle
        val now = nowMs.coerceAtLeast(0)
        seekRequestedAtMs?.let { requestedAt ->
            if (!ended && positionMs >= interval.startMs && positionMs < interval.endMs) {
                resetPending()
                return StudyRepeatDecision.Idle
            }
            if (now >= requestedAt && now - requestedAt >= SeekTimeoutMs) {
                clear()
                return StudyRepeatDecision.Failed
            }
            return StudyRepeatDecision.Waiting
        }
        if (!ended && positionMs < interval.endMs) return StudyRepeatDecision.Idle
        seekRequestedAtMs = now
        return StudyRepeatDecision.Seek(interval.startMs)
    }

    private fun setRange(interval: StudyInterval, sentence: Boolean) {
        startMs = interval.startMs
        range = interval
        this.sentence = sentence
        resetPending()
    }

    private fun validated(interval: StudyInterval, durationMs: Long): StudyInterval? {
        if (interval.startMs < 0) return null
        val end = if (durationMs > 0) minOf(interval.endMs, durationMs) else interval.endMs
        // Subtract only nonnegative ordered values so even a very long source cannot overflow.
        if (end < interval.startMs || end - interval.startMs < MinimumIntervalMs) return null
        return StudyInterval(interval.startMs, end)
    }

    private companion object {
        const val MinimumIntervalMs = 500L
        const val SeekTimeoutMs = 10_000L
    }
}
