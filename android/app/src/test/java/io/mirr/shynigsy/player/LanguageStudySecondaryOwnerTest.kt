package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.player.subtitles.localSubtitleOwner
import org.junit.Assert.*
import org.junit.Test

class LanguageStudySecondaryOwnerTest {
    private val source = PlaybackSpec("합성 영상", "https://media.invalid/stream?ticket=first",
        itemId = 2, libraryId = 3, partId = 4, viewItemId = 5)

    @Test fun keepsTheAdditionalFileSeparateFromThePrimaryFile() {
        val primary = localSubtitleOwner("https://media.invalid", "fixture-account", source)!!
        val secondary = languageStudySecondaryOwner(primary)!!
        assertTrue(secondary.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(primary, secondary)
        assertEquals(secondary, languageStudySecondaryOwner(primary))
        assertFalse(secondary.contains("fixture-account"))
        assertFalse(secondary.contains("media.invalid"))
    }

    @Test fun isolatesAccountsVideosLibrariesAndServers() {
        fun owner(server: String = "https://media.invalid", account: String = "fixture-one",
            spec: PlaybackSpec = source) = languageStudySecondaryOwner(localSubtitleOwner(server, account, spec))!!
        val original = owner()
        assertNotEquals(original, owner(account = "fixture-two"))
        assertNotEquals(original, owner(spec = source.copy(partId = 50)))
        assertNotEquals(original, owner(spec = source.copy(libraryId = 30)))
        assertNotEquals(original, owner(server = "https://another.invalid"))
    }

    @Test fun retainsTheSameAdditionalFileWhenOnlyThePlaybackTicketChanges() {
        val original = languageStudySecondaryOwner(localSubtitleOwner("https://media.invalid", "fixture-one", source))
        val renewed = source.copy(url = "https://media.invalid/stream?ticket=renewed", sessionId = "renewed-session")
        assertNotNull(original)
        assertEquals(original, languageStudySecondaryOwner(localSubtitleOwner("https://media.invalid", "fixture-one", renewed)))
    }

    @Test fun aMissingOwnerCannotCreateASharedSubtitleFile() {
        assertNull(languageStudySecondaryOwner(null))
        assertNull(languageStudySecondaryOwner(""))
        assertNull(languageStudySecondaryOwner(" \t\n"))
    }
}
