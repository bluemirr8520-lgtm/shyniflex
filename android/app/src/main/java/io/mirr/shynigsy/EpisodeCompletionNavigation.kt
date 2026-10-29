package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem

/** Cached private series are valid only under the grant used to open playback. */
internal fun mayReuseCompletedSeries(episode: MediaItem, playbackGrant: Long?, currentGrant: Long): Boolean =
    !episode.isSpoiler || (playbackGrant != null && playbackGrant == currentGrant)

/** An exact View and privacy scope are required before restoring a series. */
internal fun completedEpisodeSeriesIndex(stack: List<MediaDetails>, episode: MediaItem): Int? {
    if (episode.type != "episode" || episode.id <= 0 || episode.libraryId <= 0 ||
        episode.viewItemId?.let { it > 0 } != true) return null
    return stack.indexOfLast { detail ->
        val series = detail.item
        if (series.type !in setOf("show", "tv") || series.id <= 0 || series.libraryId != episode.libraryId ||
            series.isSpoiler != episode.isSpoiler || (episode.showId != null && series.id != episode.showId)) false
        else {
            val seriesViewMatches = episode.showId == series.id && series.viewItemId == episode.viewItemId
            val exactChild = detail.children.any { child ->
                child.type == "episode" && child.id == episode.id && child.libraryId == episode.libraryId &&
                    child.viewItemId == episode.viewItemId && child.isSpoiler == episode.isSpoiler &&
                    (child.showId == null || child.showId == series.id)
            }
            seriesViewMatches || exactChild
        }
    }.takeIf { it >= 0 }
}

/** Do not infer an episode's show from title or a loosely matching server response. */
internal fun verifiedEpisodeInSeries(detail: MediaDetails, episode: MediaItem): Boolean {
    val show = detail.item
    if (episode.type != "episode" || episode.id <= 0 || episode.libraryId <= 0 ||
        episode.viewItemId?.let { it > 0 } != true || episode.showId?.let { it > 0 } != true ||
        show.type !in setOf("show", "tv") || show.id != episode.showId ||
        show.libraryId != episode.libraryId || show.isSpoiler != episode.isSpoiler) return false
    return detail.children.any { child ->
        child.type == "episode" && child.id == episode.id && child.libraryId == episode.libraryId &&
            child.viewItemId == episode.viewItemId && child.isSpoiler == episode.isSpoiler &&
            (child.showId == null || child.showId == episode.showId)
    }
}
