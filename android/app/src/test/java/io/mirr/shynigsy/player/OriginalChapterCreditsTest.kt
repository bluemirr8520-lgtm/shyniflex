package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackMarker
import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.*
import org.junit.Test

class OriginalChapterCreditsTest {
    private val source = PlaybackSpec("episode", "https://example.test/stream/9?ticket=local-test",
        itemId = 7, libraryId = 8, partId = 9, viewItemId = 10, sessionId = "session", progressToken = "context")
    private val chapter = PlaybackMarker("credits", 2_068_000, 2_424_096)
    private val evidence = OriginalChapterCredits(requireNotNull(originalChapterSource(source)), chapter)

    @Test fun chapterSurvivesEmptyServerRefreshForTheExactSource() {
        assertEquals(listOf(chapter), markersWithOriginalChapter(source.copy(markers = emptyList()), evidence))
        val intro = PlaybackMarker("intro", 10_000, 20_000)
        assertEquals(listOf(intro, chapter), markersWithOriginalChapter(source.copy(markers = listOf(intro)), evidence))
    }

    @Test fun authoritativeServerCreditsAlwaysWin() {
        val server = PlaybackMarker("credits", 2_060_000, 2_410_000)
        assertEquals(listOf(server), markersWithOriginalChapter(source.copy(markers = listOf(server)), evidence))
    }

    @Test fun extractedCreditsAppearsOnlyInsideItsActualChapterInterval() {
        val markers = markersWithOriginalChapter(source, evidence)
        assertNull(currentSkipMarker(markers, 2_067_999, 2_424_096))
        assertEquals(chapter, currentSkipMarker(markers, 2_068_000, 2_424_096))
        assertNull(currentSkipMarker(markers, 2_424_096, 2_424_096))
    }

    @Test fun staleFileSessionUrlAndConversionCannotReuseEvidence() {
        val variants = listOf(source.copy(partId = 10), source.copy(itemId = 8), source.copy(libraryId = 9),
            source.copy(viewItemId = 11), source.copy(sessionId = "other"), source.copy(progressToken = "other"),
            source.copy(url = "https://example.test/stream/9?ticket=other"), source.copy(streamStartPositionMs = 1),
            source.copy(conversionHeight = 720), source.copy(url = "https://example.test/transcode/9"))
        variants.forEach { assertTrue(markersWithOriginalChapter(it, evidence).isEmpty()) }
    }

    @Test fun unverifiedOrMissingPlaybackIdentityCannotProduceOriginalSource() {
        assertNull(originalChapterSource(source.copy(sessionId = null)))
        assertNull(originalChapterSource(source.copy(viewItemId = null)))
        assertNull(originalChapterSource(source.copy(url = "https://example.test/stream/10")))
        assertNull(originalChapterSource(source.copy(url = "file:///tmp/test")))
    }

    @Test fun delayedChapterReadinessRetriesOnlyThreeTimesWithoutBlocking() {
        val owner = Any()
        val gate = OriginalChapterCaptureGate()
        gate.bind(owner, evidence.source)
        assertTrue(gate.begin(owner, evidence.source))
        assertTrue(gate.canRetry(owner, evidence.source))
        assertTrue(gate.begin(owner, evidence.source))
        assertTrue(gate.begin(owner, evidence.source))
        assertFalse(gate.canRetry(owner, evidence.source))
        assertFalse(gate.begin(owner, evidence.source))
    }

    @Test fun changingPlayerOrSourceCancelsOldCaptureAndSuccessfulCaptureStopsRetries() {
        val previous = Any()
        val replacement = Any()
        val updated = requireNotNull(originalChapterSource(source.copy(sessionId = "new-session")))
        val gate = OriginalChapterCaptureGate()
        gate.bind(previous, evidence.source)
        assertTrue(gate.begin(previous, evidence.source))
        gate.bind(replacement, updated)
        assertFalse(gate.begin(previous, evidence.source))
        assertFalse(gate.canRetry(previous, evidence.source))
        assertTrue(gate.begin(replacement, updated))
        gate.finish(replacement, updated)
        assertFalse(gate.canRetry(replacement, updated))
        assertFalse(gate.begin(replacement, updated))
        gate.reset()
        assertFalse(gate.begin(replacement, updated))
    }
}
