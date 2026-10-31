package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackSpec

/** Natural EOF, rather than a percentage or credits marker, is the only trigger. */
internal fun continuousPlaybackTarget(enabled: Boolean, spec: PlaybackSpec, neighbors: PlaybackNeighbors?): MediaItem? =
    if (enabled) verifiedCreditsNextEpisode(spec, neighbors) else null

/** A frame observed during MPV probing counts only once that same engine settles. */
internal fun canFinishPlaybackHandoff(hasRestore: Boolean, firstFrame: Boolean, probing: Boolean): Boolean =
    hasRestore && firstFrame && !probing

/** Activity callbacks may independently report the same native EOF. */
internal class ContinuousPlaybackGate {
    private var active = false
    fun begin(): Boolean {
        if (active) return false
        active = true
        return true
    }
    fun isActive(): Boolean = active
    fun reset() { active = false }
}
