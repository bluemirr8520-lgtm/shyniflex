package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.*
import org.junit.Test

class PlaybackReturnHomeTest {
    private val item = MediaItem(23, 8, "시청한 영상", "movie", viewItemId = 501)

    @Test fun endedPlaybackClearsLibraryDetailVersionAndSearchNavigation() {
        val state = AppState(library = Library(8, "영화", "movie"), items = listOf(item), total = 45,
            query = "검색어", detailStack = listOf(MediaDetails(item)),
            versions = listOf(MediaVersion(90, "선택한 파일")), selectedPartId = 90,
            playback = PlaybackSpec(item.title, "https://example.invalid/video", itemId = item.id,
                libraryId = item.libraryId, partId = 90, sourceItem = item),
            continueDetails = item, busy = true, error = "이전 화면의 오류")
        val home = homeAfterPlayback(state)
        assertNull(home.library)
        assertTrue(home.items.isEmpty())
        assertEquals(0, home.total)
        assertEquals("", home.query)
        assertTrue(home.detailStack.isEmpty())
        assertTrue(home.versions.isEmpty())
        assertNull(home.selectedPartId)
        assertNull(home.playback)
        assertNull(home.continueDetails)
        assertFalse(home.busy)
        assertNull(home.error)
    }

    @Test fun returningHomeRetainsTheAccountAndAnExistingSpoilerGrantState() {
        val library = Library(8, "라이브러리", "movie", isSpoiler = true)
        val privateItem = item.copy(isSpoiler = true)
        val state = AppState(loggedIn = true, server = "https://example.invalid", userName = "사용자",
            libraries = listOf(library), recentItems = listOf(privateItem), continueItems = listOf(privateItem),
            library = library, spoilerAvailable = true, spoilerPinEnabled = true, spoilerUnlocked = true)
        val home = homeAfterPlayback(state)
        assertTrue(home.loggedIn)
        assertEquals(state.server, home.server)
        assertEquals(state.userName, home.userName)
        assertEquals(state.libraries, home.libraries)
        assertEquals(state.recentItems, home.recentItems)
        assertEquals(state.continueItems, home.continueItems)
        assertTrue(home.spoilerUnlocked)
        assertTrue(home.spoilerAvailable)
        assertTrue(home.spoilerPinEnabled)
    }
}
