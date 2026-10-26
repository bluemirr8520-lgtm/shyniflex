package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import org.junit.Assert.*
import org.junit.Test

class SpoilerUnlockPromptTest {
    @Test fun anUnavailableOrTimedOutOptionalStatusStillAllowsExplicitPinEntry() {
        val publicLibrary = Library(1, "Public", "movie")
        val before = AppState(loggedIn = true, spoilerAvailable = false, libraries = listOf(publicLibrary))
        val prompt = spoilerUnlockPromptState(before, hasClient = true)
        assertTrue(prompt.spoilerPromptOpen)
        assertNull(prompt.spoilerError)
        assertFalse("Opening a prompt must never grant access", prompt.spoilerUnlocked)
        assertFalse(prompt.spoilerAvailable)
        assertFalse(prompt.spoilerBusy)
        assertEquals(before.libraries, prompt.libraries)
        assertEquals(before.continueItems, prompt.continueItems)
    }

    @Test fun explicitlyDisabledPinShowsConfigurationFeedbackInsteadOfDisappearing() {
        val before = AppState(loggedIn = true, spoilerPinEnabled = false)
        val prompt = spoilerUnlockPromptState(before, hasClient = true)
        assertTrue(prompt.spoilerPromptOpen)
        assertFalse(prompt.spoilerPinEnabled)
        assertTrue(prompt.spoilerError.orEmpty().contains("먼저 설정"))
        assertFalse(prompt.spoilerUnlocked)
    }

    @Test fun signedOutMissingClientAndAlreadyPendingRequestsCannotOpenAnotherPrompt() {
        val signedOut = AppState()
        assertSame(signedOut, spoilerUnlockPromptState(signedOut, hasClient = true))
        val missingClient = AppState(loggedIn = true)
        assertSame(missingClient, spoilerUnlockPromptState(missingClient, hasClient = false))
        val busy = AppState(loggedIn = true, spoilerBusy = true)
        assertSame(busy, spoilerUnlockPromptState(busy, hasClient = true))
    }
}
