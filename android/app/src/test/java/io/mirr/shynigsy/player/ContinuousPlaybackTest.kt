package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.*
import org.junit.Test

class ContinuousPlaybackTest {
    private val current = MediaItem(23, 9, "6화", "episode", seasonNumber = 1,
        episodeNumber = 6, viewItemId = 601, showId = 5)
    private val next = current.copy(id = 24, title = "7화", episodeNumber = 7,
        folderKey = "/series", navigationPartId = 91, playbackPartId = 91)
    private val spec = PlaybackSpec("시리즈", "https://example.test/video", itemId = 23,
        libraryId = 9, partId = 90, viewItemId = 601, sourceItem = current, markers = emptyList())

    @Test fun toggleUsesVerifiedNextWithoutNeedingCreditsMarkers() {
        val neighbors = PlaybackNeighbors(nextEpisode = next)
        assertNull(continuousPlaybackTarget(false, spec, neighbors))
        assertEquals(next, continuousPlaybackTarget(true, spec, neighbors))
        assertNull(continuousPlaybackTarget(true, spec, null))
    }

    @Test fun nextMustRetainVerifiedFolderViewAndPartEvidence() {
        for (bad in listOf(next.copy(folderKey = null), next.copy(viewItemId = 602),
            next.copy(showId = 6), next.copy(navigationPartId = 90), next.copy(isSpoiler = true))) {
            assertNull(continuousPlaybackTarget(true, spec, PlaybackNeighbors(nextEpisode = bad)))
        }
        assertNull(continuousPlaybackTarget(true, spec.copy(sourceItem = current.copy(type = "movie")),
            PlaybackNeighbors(nextEpisode = next)))
    }

    @Test fun duplicateNaturalEndEventsCannotStartConcurrentHandoffs() {
        val gate = ContinuousPlaybackGate()
        assertTrue(gate.begin()); assertTrue(gate.isActive()); assertFalse(gate.begin())
        gate.reset(); assertFalse(gate.isActive()); assertTrue(gate.begin())
    }

    @Test fun frameSeenDuringProbeFinishesOnlyAfterEngineSettles() {
        assertFalse(canFinishPlaybackHandoff(true, true, true))
        assertTrue(canFinishPlaybackHandoff(true, true, false))
        assertFalse(canFinishPlaybackHandoff(true, false, false))
        assertFalse(canFinishPlaybackHandoff(false, true, false))
    }
}
