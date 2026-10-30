package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.*
import org.junit.Test

class PlaybackPresenceLifecycleTest {
    private val episode = PlaybackSpec("test", "https://example.test/stream", itemId = 42, libraryId = 8,
        partId = 90, viewItemId = 51, sessionId = "first", progressToken = "context")

    private class Connection : PlaybackPresenceConnection {
        val updates = mutableListOf<String>()
        val closes = mutableListOf<Boolean>()
        override fun update(positionMs: Long, durationMs: Long, state: String) { updates += "$positionMs:$durationMs:$state" }
        override fun close(endSession: Boolean) { closes += endSession }
    }

    @Test fun progressAndCompletionUseOneSessionAndCloseOnlyOnce() {
        val sockets = mutableListOf<Connection>()
        val lifecycle = PlaybackPresenceLifecycle(open = { _, _ -> Connection().also(sockets::add) })
        lifecycle.update(episode, 1_000, 10_000, "playing")
        lifecycle.update(episode, 2_000, 10_000, "paused")
        lifecycle.update(episode, 10_000, 10_000, "completed")
        assertEquals(1, sockets.size)
        assertEquals(listOf("1000:10000:playing", "2000:10000:paused"), sockets.single().updates)
        assertEquals(listOf(true), sockets.single().closes)
        lifecycle.close(endSession = true)
        assertEquals(listOf(true), sockets.single().closes)
    }

    @Test fun replacingSourceStopsOnlyOldSession() {
        val sockets = mutableListOf<Connection>()
        val lifecycle = PlaybackPresenceLifecycle(open = { _, _ -> Connection().also(sockets::add) })
        val next = episode.copy(partId = 91, itemId = 43, sessionId = "next")
        lifecycle.update(episode, 100, 1_000, "playing")
        lifecycle.update(next, 0, 1_000, "playing")
        lifecycle.update(episode, 100, 1_000, "stopped")
        assertEquals(2, sockets.size)
        assertEquals(listOf(true), sockets[0].closes)
        assertTrue(sockets[1].closes.isEmpty())
        lifecycle.update(next, 500, 1_000, "stopped")
        assertEquals(listOf(true), sockets[1].closes)
    }

    @Test fun unverifiedIdentityAndTransportFailureNeverAffectPlayback() {
        var attempts = 0
        val lifecycle = PlaybackPresenceLifecycle(open = { _, _ -> attempts++; throw IllegalStateException("fixture failure") })
        lifecycle.update(episode.copy(viewItemId = null), 1, 10, "playing")
        lifecycle.update(episode.copy(sessionId = null), 1, 10, "playing")
        assertEquals(0, attempts)
        lifecycle.update(episode, 1, 10, "playing")
        lifecycle.close(endSession = true)
        assertEquals(1, attempts)
        assertFalse(samePlaybackPresenceSource(episode, episode.copy(progressToken = "new context")))
        assertFalse(samePlaybackPresenceSource(episode, episode.copy(sessionId = "new session")))
    }

    @Test fun configurationOrAuthorityChangeClosesWithoutSendingStop() {
        val sockets = mutableListOf<Connection>()
        val lifecycle = PlaybackPresenceLifecycle(open = { _, _ -> Connection().also(sockets::add) })
        lifecycle.update(episode, 100, 1_000, "playing")
        lifecycle.close(endSession = false)
        assertEquals(listOf(false), sockets.single().closes)
        lifecycle.update(episode.copy(sessionId = "fresh", progressToken = "fresh context"), 100, 1_000, "playing")
        assertEquals(2, sockets.size)
    }

    @Test fun remoteStopIsAcceptedOnlyForItsCurrentConnection() {
        val callbacks = mutableListOf<(String?) -> Unit>()
        val received = mutableListOf<Pair<PlaybackSpec, Long>>()
        val lifecycle = PlaybackPresenceLifecycle(
            open = { _, callback -> callbacks += callback; Connection() },
            onForceStop = { source, revision, _ -> received += source to revision },
        )
        val next = episode.copy(sessionId = "next", partId = 91)
        lifecycle.update(episode, 100, 1_000, "playing")
        callbacks.single()(null)
        assertTrue(lifecycle.isCurrent(received.last().first, received.last().second))
        lifecycle.update(next, 100, 1_000, "playing")
        callbacks.first()(null)
        assertFalse(lifecycle.isCurrent(received.last().first, received.last().second))
        callbacks.last()(null)
        assertTrue(lifecycle.isCurrent(received.last().first, received.last().second))
        lifecycle.close(endSession = false)
        assertFalse(lifecycle.isCurrent(received.last().first, received.last().second))
    }
}
