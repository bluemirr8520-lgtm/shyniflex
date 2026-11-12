package io.mirr.shynigsy.player

import io.mirr.shynigsy.player.mpv.MpvStreamDiagnostics
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun exoUsesItsOwnBufferMeasurementWithoutInventingNativeTelemetry() {
        val log = playbackDiagnosticLog("exo", 3, true, true, null, 12_500)
        assertTrue(log.contains("cache_s=12.50"))
        assertTrue(log.contains("input_mbps=확인 중"))
        assertTrue(log.contains("decoder=unknown"))
    }

    @Test fun missingInvalidAndUnboundedMeasurementsAreUnknown() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1e20))
            assertEquals("확인 중", diagnosticNumber(value))
        assertEquals("0.00", diagnosticNumber(0.0))
    }

    @Test fun transferBytesAreConvertedToBitsAndPrivateStringsNeverEnterLog() {
        val snapshot = MpvStreamDiagnostics(1_000_000.0, 10_000_000.0, null, 0.5, true,
            "https://private.example/ticket=secret", 2, 4)
        val log = playbackDiagnosticLog("https://private.example", 2, true, false, snapshot)
        assertTrue(log.contains("input_mbps=8.00"))
        assertTrue(log.contains("video_mbps=10.00"))
        assertTrue(log.contains("cache_pause=true"))
        assertFalse(log.contains("private"))
        assertFalse(log.contains("secret"))
        assertEquals("확인 중", diagnosticDecoder(snapshot.hardwareDecoder))
    }
}
