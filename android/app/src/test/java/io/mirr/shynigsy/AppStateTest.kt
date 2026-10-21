package io.mirr.shynigsy

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test fun firstLaunchHasNoPresetServer() {
        assertEquals("", AppState().server)
        assertEquals("", initialServerAddress(null, null))
        assertEquals("", initialServerAddress("  ", null))
    }

    @Test fun savedUserAddressIsRetained() {
        val saved = "https://saved.example.test:8443"
        assertEquals(saved, initialServerAddress(saved, null))
        assertEquals(saved, initialServerAddress(saved, "https://older.example.test"))
    }

    @Test fun existingSessionAddressSurvivesMissingSettings() {
        val session = "https://session.example.test:8443"
        assertEquals(session, initialServerAddress(null, session))
        assertEquals(session, initialServerAddress("", session))
    }
}
