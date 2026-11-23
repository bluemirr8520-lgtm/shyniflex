package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.subtitles.preferredSubtitle
import org.junit.Assert.*
import org.junit.Test

class SeriesSubtitlePreferencesTest {
    private val prefs = PicturePresetStoreTest.MemoryPreferences()
    private val server = "https://example.invalid"
    private fun user(name: String = "alice", token: String = "session-a", url: String = server) =
        SeriesSubtitlePreferences(prefs, subtitleAccountScope(url, name, token))

    private fun episode(id: Int, show: Int? = 30, library: Int = 2, country: String? = "KR",
                        type: String = "episode"): PlaybackSpec {
        val item = MediaItem(id, library, "Title", type, showId = show, productionCountries = listOfNotNull(country))
        return PlaybackSpec(item.title, "$server/$id", itemId = id, partId = id, libraryId = library,
            sourceItem = item, subtitles = listOf(SubtitleTrack(id, "한국어", "$server/sub/$id", language = "kor")))
    }

    @Test fun explicitOnSurvivesNextEpisodeAndReopeningUntilTurnedOff() {
        val first = episode(1)
        assertFalse(user().enabled(first))
        user().remember(first, null, true)
        val next = episode(2)
        assertTrue(user().enabled(next))
        assertEquals(2, preferredSubtitle(next)?.id)
        assertTrue(user(token = "renewed-session").enabled(next))
        user().remember(next, null, false)
        assertFalse(user().enabled(episode(3)))
    }

    @Test fun choicesCannotCrossLibrarySeriesServerAccountOrPrivateBoundary() {
        user().remember(episode(1), null, true)
        val ep = episode(2)
        assertFalse(user().enabled(episode(2, show = 31)))
        assertFalse(user().enabled(episode(2, library = 3)))
        assertFalse(user().enabled(episode(2, show = null)))
        assertFalse(user().enabled(episode(2, type = "movie")))
        assertFalse(user().enabled(ep.copy(sourceItem = ep.sourceItem!!.copy(isSpoiler = true))))
        assertFalse(user("bob").enabled(ep))
        assertFalse(user(url = "https://other.invalid").enabled(ep))
        user("Plex", "first-plex-account").remember(ep, null, true)
        assertFalse(user("Plex", "other-plex-account").enabled(ep))
    }

    @Test fun lateOriginOrKoreanAudioActivatesEarlyChoiceWithoutChangingForeignWorks() {
        val unknown = episode(1, country = null)
        user().remember(unknown, null, false)
        assertTrue(user().enabled(unknown))
        assertTrue(user().enabled(episode(2, country = "US"), "kor"))
        assertFalse(user().enabled(unknown, "kor"))
        assertFalse(user().enabled(episode(2, country = null)))
        assertTrue(user().enabled(episode(2, country = "US")))
    }

    @Test fun unavailableTracksDoNotClearTheSeriesChoice() {
        user().remember(episode(1), null, true)
        val missing = episode(2).copy(subtitles = emptyList(), subtitlesPending = true)
        assertTrue(user().enabled(missing))
        assertNull(preferredSubtitle(missing))
        assertTrue(user().enabled(episode(3)))
    }
}
