package io.mirr.shynigsy.player.mpv

import org.junit.Assert.*
import org.junit.Test

class MpvPlaybackProfileTest {
    private fun device(
        sdk: Int = 35, memoryClass: Int = 256, total: Long = 4096, available: Long = 2048,
        lowRam: Boolean = false, pressure: Boolean = false, cores: Int = 8,
        mimes: Set<String> = setOf("video/avc", "video/hevc", "video/av01")
    ) = MpvPlaybackProfile.Device(sdk, memoryClass, total, available, lowRam, pressure, cores, mimes)

    @Test fun lowRamDevicesKeepDemuxerBudgetSmall() {
        val profile = MpvPlaybackProfile.choose(device(lowRam = true))
        assertEquals(20, profile.forwardCacheMiB + profile.backCacheMiB)
        assertEquals(4, profile.softwareThreads)
        assertEquals("auto", profile.mpvOptions()["cache"])
    }

    @Test fun availableSystemMemoryCanReduceAnOtherwiseRoomyBudget() {
        val normal = MpvPlaybackProfile.choose(device(memoryClass = 512, total = 8192, available = 4096))
        val pressured = MpvPlaybackProfile.choose(device(memoryClass = 512, total = 8192, available = 128, pressure = true))
        assertEquals(120, normal.forwardCacheMiB + normal.backCacheMiB)
        assertTrue(pressured.forwardCacheMiB + pressured.backCacheMiB <= 8)
        assertTrue(pressured.forwardCacheMiB > 0 && pressured.backCacheMiB > 0)
    }

    @Test fun budgetsStayBoundedEvenForBadOrHugeDeviceReports() {
        for (memoryClass in listOf(0, 16, 128, 256, 512, Int.MAX_VALUE)) {
            for (available in listOf(0L, 16L, 128L, 4096L, Long.MAX_VALUE)) {
                val profile = MpvPlaybackProfile.choose(device(memoryClass = memoryClass, total = Long.MAX_VALUE, available = available, cores = 128))
                assertTrue(profile.forwardCacheMiB + profile.backCacheMiB in 4..120)
                assertTrue(profile.softwareThreads in 1..8)
            }
        }
    }

    @Test fun onlyReportedKnownHardwareCodecsAreProbed() {
        val profile = MpvPlaybackProfile.choose(device(mimes = setOf("video/avc", "video/x-unknown")))
        assertEquals(listOf("h264"), profile.hardwareCodecs)
        assertEquals("mediacodec-copy", profile.hardwareDecoder)
        assertEquals("3", profile.mpvOptions()["hwdec-software-fallback"])
        assertEquals("yes", profile.mpvOptions()["vd-lavc-check-hw-profile"])
        assertFalse(profile.mpvOptions().values.contains("all"))
    }

    @Test fun softwareHintAndMissingHardwareKeepBundledSoftwareDecoderAvailable() {
        assertEquals("no", MpvPlaybackProfile.choose(device(), preferSoftwareDecode = true).hardwareDecoder)
        val missing = MpvPlaybackProfile.choose(device(mimes = emptySet()))
        assertEquals("no", missing.hardwareDecoder)
        assertFalse(missing.mpvOptions().containsKey("hwdec-codecs"))
    }

    @Test fun oldAndroidDoesNotProbeAv1HardwareAndCodecOrderIsStable() {
        val old = MpvPlaybackProfile.choose(device(sdk = 28))
        assertEquals(listOf("h264", "hevc"), old.hardwareCodecs)
        val modern = MpvPlaybackProfile.choose(device(mimes = linkedSetOf("video/av01", "video/hevc", "video/avc")))
        assertEquals(listOf("h264", "hevc", "av1"), modern.hardwareCodecs)
    }
}
