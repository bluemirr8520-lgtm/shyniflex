package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.*
import org.junit.Test

class LocalSubtitleOwnerTest {
    @Test fun isolatesAccountsAndPartsButKeepsRenewedTickets() {
        val spec = PlaybackSpec("test", "https://host.invalid/stream?ticket=first", itemId = 2, libraryId = 3, partId = 4, viewItemId = 5)
        val owner = localSubtitleOwner("https://HOST.invalid:443/", "secret", spec)!!
        assertEquals(64, owner.length)
        assertEquals(owner, localSubtitleOwner("https://host.invalid", "secret", spec.copy(url = "https://host.invalid/stream?ticket=renewed", sessionId = "new")))
        assertEquals(owner, localSubtitleOwner("https://host.invalid", "secret", spec.copy(itemId = 200, viewItemId = 500)))
        assertNotEquals(owner, localSubtitleOwner("https://host.invalid", "secret", spec.copy(libraryId = 30)))
        assertNotEquals(owner, localSubtitleOwner("https://host.invalid", "other", spec))
        assertNotEquals(owner, localSubtitleOwner("https://host.invalid", "secret", spec.copy(partId = 6)))
        assertNotEquals(owner, localSubtitleOwner("https://other.invalid", "secret", spec))
        assertNotEquals(localSubtitleOwner("https://host.invalid", "secret", spec.copy(partId = 0)),
            localSubtitleOwner("https://host.invalid", "secret", spec.copy(partId = 0, url = "https://host.invalid/other")))
        assertNull(localSubtitleOwner("https://user:pass@host.invalid", "secret", spec))
    }
}
