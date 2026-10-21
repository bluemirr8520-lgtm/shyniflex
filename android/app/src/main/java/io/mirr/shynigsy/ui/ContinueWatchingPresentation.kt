package io.mirr.shynigsy.ui

import io.mirr.shynigsy.data.MediaItem

internal data class ContinuePlaybackProgress(val fraction: Float, val remainingMinutes: Long)

/** Unknown/invalid positions do not imply either a watched percentage or remaining runtime. */
internal fun continuePlaybackProgress(durationMs: Long, positionMs: Long): ContinuePlaybackProgress? {
    if (durationMs <= 0 || positionMs <= 0 || positionMs > durationMs) return null
    val remainingMs = durationMs - positionMs
    val minutes = remainingMs / 60_000 + if (remainingMs % 60_000 != 0L) 1 else 0
    return ContinuePlaybackProgress((positionMs.toDouble() / durationMs.toDouble()).toFloat(), minutes)
}

/** The server may already include S/E in its subtitle; do not repeat those prefixes. */
internal fun continueEpisodeCaption(item: MediaItem): String {
    if (item.type != "episode") return continuePlaybackProgress(item.durationMs, item.resumeMs)
        ?.let { "${it.remainingMinutes}분 남음" } ?: "재생"
    val number = listOfNotNull(item.seasonNumber?.let { "S$it" }, item.episodeNumber?.let { "E$it" }).joinToString(" ")
    val supplied = item.displaySubtitle?.trim().orEmpty()
    val episodeTitle = item.title.trim().takeIf { it.isNotBlank() && it != item.showTitle?.trim() }
    val title = episodeTitle ?: supplied
    if (number.isBlank()) return title.ifBlank { "회차" }
    if (title.isBlank()) return number
    // Preserve a server-formatted caption such as "S2 E1 · Episode", including episode 0.
    val prefixed = Regex("^S\\s*${item.seasonNumber ?: "\\d+"}\\s*E\\s*${item.episodeNumber ?: "\\d+"}(?=\\s|[·:—–-]|$)", RegexOption.IGNORE_CASE)
    return if (prefixed.containsMatchIn(title)) title else "$number · $title"
}
