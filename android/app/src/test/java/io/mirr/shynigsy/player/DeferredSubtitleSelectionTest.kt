package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredSubtitleSelectionTest {
    private val korean = SubtitleTrack(1, "한국어", "https://test.invalid/1", language = "ko", source = "external")
    private val english = SubtitleTrack(2, "English", "https://test.invalid/2", language = "en", source = "external")
    private val spec = PlaybackSpec("test", "https://test.invalid/video", itemId = 1, libraryId = 2, partId = 3,
        subtitles = listOf(english, korean))

    private fun choose(savedId: Int? = null, previous: SubtitleTrack? = null, enabled: Boolean = true,
        manual: Boolean = false, nativePending: Boolean = false, unchanged: Boolean = true) =
        deferredSubtitleSelection(spec, savedId, previous, enabled, manual, nativePending, unchanged)

    @Test fun defaultAndPreviousPreferenceSurviveDelayedList() {
        assertEquals(korean, choose())
        assertEquals(english, choose(previous = english.copy(id = 99)))
    }

    @Test fun manualSelectionAndDisableAreNotOverridden() {
        assertNull(choose(enabled = false))
        assertNull(choose(manual = true))
        assertNull(choose(nativePending = true))
        assertNull(choose(savedId = 1, unchanged = false))
    }

    @Test fun restoredServerSelectionWinsOverDefaultButMissingIdDoesNotGuess() {
        assertEquals(english, choose(savedId = 2, manual = true))
        assertNull(choose(savedId = 999, manual = true))
    }

    @Test fun oldResultsCannotCrossPlaybackOrAuthorizationChanges() {
        val pending = spec.copy(subtitlesPending = true, sessionId = "session", progressToken = "grant")
        assertTrue(sameDeferredSubtitleSource(pending, pending))
        for (changed in listOf(pending.copy(itemId = 10), pending.copy(partId = 10),
            pending.copy(libraryId = 10), pending.copy(viewItemId = 10),
            pending.copy(sessionId = "new-session"), pending.copy(progressToken = "new-grant"),
            pending.copy(url = "https://test.invalid/new"), pending.copy(subtitlesPending = false))) {
            assertFalse(sameDeferredSubtitleSource(pending, changed))
        }
    }
}
