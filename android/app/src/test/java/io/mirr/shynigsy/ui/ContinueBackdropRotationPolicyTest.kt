package io.mirr.shynigsy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueBackdropRotationPolicyTest {
    @Test fun rotationUsesTheCurrentShelfOrderAndWrapsAtTheLastWork() {
        val keys = listOf("7:movie:21", "8:episode:21", "7:movie:32")
        assertEquals(keys[1], nextContinueBackdropKey(keys, keys[0]))
        assertEquals(keys[2], nextContinueBackdropKey(keys, keys[1]))
        assertEquals(keys[0], nextContinueBackdropKey(keys, keys[2]))
    }

    @Test fun anExpiredOrRemovedSelectionFallsBackToTheFirstPermittedWork() {
        assertEquals("public", nextContinueBackdropKey(listOf("public", "another"), "locked-private"))
    }

    @Test fun anEmptyOrSingleWorkShelfNeverStartsARotation() {
        assertNull(nextContinueBackdropKey(emptyList(), "removed"))
        assertNull(nextContinueBackdropKey(listOf("only"), "only"))
    }
}
