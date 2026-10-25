package io.mirr.shynigsy.player

/** Identity belongs to the physical playback source, not just the visible button label. */
internal data class TvSegmentMarkerKey(
    val partId: Int,
    val type: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Offer automatic focus once per timeline interval, without carrying a held remote
 * press into a newly focused action. Supply the raw timeline key even while a
 * loading state temporarily hides its button; only an actual exit supplies null.
 */
internal class TvSegmentFocusGate {
    private var observed: TvSegmentMarkerKey? = null
    private var handled = false
    private val heldKeys = mutableSetOf<Int>()

    fun keyDown(keyCode: Int) { heldKeys.add(keyCode) }
    fun keyUp(keyCode: Int) { heldKeys.remove(keyCode) }
    fun clearHeldKeys() { heldKeys.clear() }

    /** Manual navigation to either action must not be replaced by a later timer tick. */
    fun focused() {
        if (observed != null) handled = true
    }

    fun offer(
        key: TvSegmentMarkerKey?,
        eligible: Boolean,
        automaticAllowed: Boolean,
        requestFocus: () -> Boolean,
    ): Boolean {
        if (key != observed) {
            observed = key
            handled = false
        }
        if (key == null || handled || !eligible || !automaticAllowed || heldKeys.isNotEmpty()) return false
        val granted = requestFocus()
        if (granted) handled = true
        return granted
    }
}
