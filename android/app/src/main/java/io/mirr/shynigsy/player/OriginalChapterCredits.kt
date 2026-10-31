package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackMarker
import io.mirr.shynigsy.data.PlaybackSpec
import java.net.URI

/** Memory-only identity of the exact authorized original file and playback session. */
internal data class OriginalChapterSource(
    val libraryId: Int, val itemId: Int, val partId: Int, val viewItemId: Int?,
    val sessionId: String?, val progressToken: String?, val url: String, val isSpoiler: Boolean,
)

internal data class OriginalChapterCredits(val source: OriginalChapterSource, val marker: PlaybackMarker)

internal fun originalChapterSource(spec: PlaybackSpec): OriginalChapterSource? {
    if (spec.isServerConversion || spec.streamStartPositionMs != 0L || spec.libraryId <= 0 ||
        spec.itemId <= 0 || spec.partId <= 0 || spec.viewItemId?.let { it > 0 } != true ||
        spec.sessionId.isNullOrBlank()) return null
    val uri = runCatching { URI(spec.url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
    if (uri.host.isNullOrBlank() || uri.path != "/stream/${spec.partId}") return null
    return OriginalChapterSource(spec.libraryId, spec.itemId, spec.partId, spec.viewItemId,
        spec.sessionId, spec.progressToken, spec.url, spec.sourceItem?.isSpoiler == true)
}

internal fun sameOriginalChapterSource(spec: PlaybackSpec, source: OriginalChapterSource): Boolean =
    spec.conversionHeight == null && spec.streamStartPositionMs == 0L &&
        spec.libraryId == source.libraryId && spec.itemId == source.itemId && spec.partId == source.partId &&
        spec.viewItemId == source.viewItemId && spec.sessionId == source.sessionId &&
        spec.progressToken == source.progressToken && spec.url == source.url &&
        (spec.sourceItem?.isSpoiler == true) == source.isSpoiler

/** Bound retries to a single player and exact source, without delaying startup. */
internal class OriginalChapterCaptureGate(private val maxAttempts: Int = 3) {
    private var owner: Any? = null
    private var source: OriginalChapterSource? = null
    private var attempts = 0
    private var complete = false

    fun bind(candidate: Any, identity: OriginalChapterSource) {
        owner = candidate; source = identity; attempts = 0; complete = false
    }

    fun begin(candidate: Any, identity: OriginalChapterSource): Boolean {
        if (owner !== candidate || source != identity || complete || attempts >= maxAttempts) return false
        attempts++
        return true
    }

    fun canRetry(candidate: Any, identity: OriginalChapterSource): Boolean =
        owner === candidate && source == identity && !complete && attempts < maxAttempts

    fun finish(candidate: Any, identity: OriginalChapterSource) {
        if (owner === candidate && source == identity) complete = true
    }

    fun reset() { owner = null; source = null; attempts = 0; complete = false }
}

/** A server-supplied credits marker always takes precedence over the file chapter. */
internal fun markersWithOriginalChapter(spec: PlaybackSpec, evidence: OriginalChapterCredits?): List<PlaybackMarker> {
    if (evidence == null || !sameOriginalChapterSource(spec, evidence.source) ||
        spec.markers.any { it.type.equals("credits", ignoreCase = true) }) return spec.markers
    return spec.markers + evidence.marker
}
