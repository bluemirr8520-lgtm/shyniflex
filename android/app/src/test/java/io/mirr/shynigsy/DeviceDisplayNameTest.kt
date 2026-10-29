package io.mirr.shynigsy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDisplayNameTest {
    @Test fun usesModelAndDeviceKindWithoutRepeatingManufacturer() {
        assertEquals("samsung SM-S918N (shyniflex)", deviceDisplayName("samsung", "SM-S918N", false))
        assertEquals("NVIDIA SHIELD Android TV (shyniflex TV)",
            deviceDisplayName("NVIDIA", "NVIDIA SHIELD Android TV", true))
    }

    @Test fun missingOrUnsafeBuildValuesUseSafeLabels() {
        assertEquals("Android (shyniflex)", deviceDisplayName("unknown", " \n ", false))
        assertEquals("Android TV (shyniflex TV)", deviceDisplayName(null, null, true))
        assertEquals("A B (shyniflex)", deviceDisplayName("A\u0000", "B\u007f", false))
    }

    @Test fun oversizedNamesStayWithinServerLimitWithoutSplittingUnicode() {
        val label = deviceDisplayName(null, "A".repeat(187) + "\uD83D\uDE00", false)
        assertTrue(label.length <= 200)
        assertFalse(label.any { it.isISOControl() })
        assertTrue(label.endsWith(" (shyniflex)"))
        assertFalse(label.substringBefore(" (shyniflex)").last().isHighSurrogate())
    }
}
