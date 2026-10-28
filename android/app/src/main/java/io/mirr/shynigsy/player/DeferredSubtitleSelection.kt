package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.subtitles.preferredSubtitle

/** A late result must never be attached to another playback or authorization context. */
internal fun sameDeferredSubtitleSource(current: PlaybackSpec, candidate: PlaybackSpec): Boolean =
    candidate.subtitlesPending && candidate.itemId == current.itemId && candidate.partId == current.partId &&
        candidate.libraryId == current.libraryId && candidate.viewItemId == current.viewItemId &&
        candidate.sessionId == current.sessionId && candidate.progressToken == current.progressToken &&
        candidate.url == current.url

/** Do not override a user's choice made while the server list was loading. */
internal fun deferredSubtitleSelection(
    spec: PlaybackSpec,
    savedId: Int?,
    previous: SubtitleTrack?,
    enabled: Boolean,
    manuallySelected: Boolean,
    nativePreferencePending: Boolean,
    selectionUnchanged: Boolean,
): SubtitleTrack? {
    if (!enabled || !selectionUnchanged) return null
    if (savedId != null) return spec.subtitles.firstOrNull { it.id == savedId }
    if (manuallySelected || nativePreferencePending) return null
    return replacementSubtitle(previous, spec.subtitles) ?: preferredSubtitle(spec)
}
