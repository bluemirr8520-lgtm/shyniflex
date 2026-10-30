package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackSpec

/** Only use a recovered current episode after matching the complete playback identity. */
internal fun verifiedCreditsNextEpisode(spec: PlaybackSpec, neighbors: PlaybackNeighbors?): MediaItem? {
    val source = spec.sourceItem?.takeIf {
        it.type == "episode" && it.id == spec.itemId && it.id > 0 &&
            it.libraryId == spec.libraryId && it.viewItemId == spec.viewItemId &&
            it.viewItemId?.let { view -> view > 0 } == true
    } ?: return null
    val target = neighbors?.nextEpisode ?: return null
    val current = if (source.seasonNumber != null && source.episodeNumber != null) source else {
        val verified = neighbors.currentEpisode ?: return null
        if (verified.type != "episode" || verified.id != source.id || verified.libraryId != source.libraryId ||
            verified.viewItemId != source.viewItemId || verified.isSpoiler != source.isSpoiler ||
            verified.showId?.let { it > 0 } != true ||
            (source.showId != null && verified.showId != source.showId) ||
            (source.seasonNumber != null && verified.seasonNumber != source.seasonNumber) ||
            (source.episodeNumber != null && verified.episodeNumber != source.episodeNumber)) return null
        verified
    }
    val season = current.seasonNumber ?: return null
    val episode = current.episodeNumber ?: return null
    val targetSeason = target.seasonNumber ?: return null
    val targetEpisode = target.episodeNumber ?: return null
    return target.takeIf {
        it.type == "episode" && it.id != source.id && it.libraryId == source.libraryId &&
            it.viewItemId == source.viewItemId && it.isSpoiler == source.isSpoiler && it.showId != null &&
            (current.showId == null || it.showId == current.showId) &&
            season >= 0 && episode >= 0 && targetSeason >= 0 && targetEpisode >= 0 &&
            (targetSeason > season || targetSeason == season && targetEpisode > episode) &&
            !it.folderKey.isNullOrBlank() && (it.navigationPartId ?: 0) > 0 &&
            it.playbackPartId == it.navigationPartId && it.navigationPartId != spec.partId
    }
}
