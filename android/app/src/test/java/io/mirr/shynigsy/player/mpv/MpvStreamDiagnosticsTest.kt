package io.mirr.shynigsy.player.mpv

import org.junit.Assert.*
import org.junit.Test

class MpvStreamDiagnosticsTest {
    @Test fun nativeDoubleIsPreferredWithoutASecondNativeQuery() {
        assertEquals(123.5, readMpvDiagnosticNumber("cache-speed", { 123.5 },
            { error("String fallback must not be queried for a valid native number") })!!, 0.0)
        assertEquals(0.0, readMpvDiagnosticNumber("cache-speed", { 0.0 },
            { error("Zero is measured, not missing") })!!, 0.0)
    }

    @Test fun integerAndDecimalStringsKeepRatesLargerThanSignedInt() {
        for (raw in listOf("4294967296", "4294967296.0", "4.294967296e9")) {
            assertEquals(4_294_967_296.0,
                readMpvDiagnosticNumber("cache-speed", { null }, { raw })!!, 0.0)
        }
        assertEquals(1.25, readMpvDiagnosticNumber("demuxer-cache-duration", { null }, { "1.25" })!!, 0.0)
    }

    @Test fun missingOrInvalidRatesStayUnknownInsteadOfBecomingZero() {
        for (native in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1e20)) {
            for (raw in listOf(null, "", "NaN", "Infinity", "-1", "1e20", "1 Mbps",
                "https://private.example/ticket=secret", "1".repeat(65))) {
                assertNull(readMpvDiagnosticNumber("video-bitrate", { native }, { raw }))
            }
        }
    }

}
