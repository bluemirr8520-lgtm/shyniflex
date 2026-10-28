package io.mirr.shynigsy.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeFocusPolicyTest {
    @Test fun initialFocusRequiresARealAttachedShelf() {
        assertFalse(shouldFocusInitialHomeShelf(true, false, false, false, false))
        assertTrue(shouldFocusInitialHomeShelf(true, true, false, false, false))
        assertFalse(shouldFocusInitialHomeShelf(false, true, false, false, false))
    }

    @Test fun lateContentCannotStealUserNavigationOrFocusAgain() {
        assertFalse(shouldFocusInitialHomeShelf(true, true, false, true, false))
        assertFalse(shouldFocusInitialHomeShelf(true, true, false, false, true))
        assertFalse(shouldFocusInitialHomeShelf(true, true, true, false, false))
    }
}
