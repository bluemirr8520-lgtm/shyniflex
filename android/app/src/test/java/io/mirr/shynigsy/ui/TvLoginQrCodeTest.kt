package io.mirr.shynigsy.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mirr.shynigsy.data.tvLoginQrUrl

class TvLoginQrCodeTest {
    @Test fun publicApprovalUrlRoundTripsThroughARealQrDecoder() {
        val url = tvLoginQrUrl("https://server.example.test:8443", NONCE)
        assertEquals(url, decodeRendered(url))
    }

    @Test fun unicodeServerNameSurvivesCanonicalApprovalQrEncoding() {
        val url = tvLoginQrUrl("https://영화.example.test:8443", NONCE)
        assertEquals(url, decodeRendered(url))
    }

    @Test fun fourModuleQuietZoneAndBlackFinderPatternsRemainPresent() {
        val matrix = encodeTvLoginQr(tvLoginQrUrl("https://server.example.test", NONCE))
        assertEquals(matrix.width, matrix.height)
        for (offset in 0 until 4) for (index in 0 until matrix.width) {
            assertFalse(matrix[index, offset])
            assertFalse(matrix[offset, index])
            assertFalse(matrix[index, matrix.height - 1 - offset])
            assertFalse(matrix[matrix.width - 1 - offset, index])
        }
        assertTrue(matrix[4, 4])
        assertTrue(matrix[10, 4])
        assertTrue(matrix[4, 10])
    }

    @Test fun credentialAddressesCleartextAndInvalidPayloadsCannotBecomeQrCodes() {
        for (value in listOf("", "/pair", "http://server.example.test/pair", "https://user:password@server.example.test/pair",
            "shyniflex://tv-login?server=http%3A%2F%2Fserver.example.test&nonce=$NONCE",
            "shyniflex://tv-login?server=https%3A%2F%2Fuser%3Apassword%40server.example.test&nonce=$NONCE",
            tvLoginQrUrl("https://server.example.test", NONCE) + "&claim_secret=should-never-be-rendered",
            "shyniflex://tv-login?" + "a".repeat(4096))) {
            assertTrue("Invalid approval addresses must fail before rendering", runCatching { encodeTvLoginQr(value) }.isFailure)
        }
    }

    @Test fun modulePlacementUsesWholePixelsAndCentersWithoutClipping() {
        val placement = tvQrPlacement(653, 607, 57)!!
        assertEquals(10, placement.modulePixels)
        assertEquals(570, placement.sizePixels)
        assertEquals(41, placement.left)
        assertEquals(18, placement.top)
        assertTrue(placement.left + placement.sizePixels <= 653)
        assertTrue(placement.top + placement.sizePixels <= 607)
    }

    @Test fun anUndersizedCanvasCannotClipAndMisrepresentTheQr() {
        assertNull(tvQrPlacement(40, 100, 57))
        assertNull(tvQrPlacement(100, 40, 57))
        assertNull(tvQrPlacement(100, 100, 0))
        assertNull(tvQrPlacement(-1, 100, 57))
    }

    private fun decodeRendered(url: String): String {
        val matrix = encodeTvLoginQr(url)
        val scale = 5
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { index ->
            if (matrix[index % side / scale, index / side / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return MultiFormatReader().decode(image).text
    }

    companion object { private const val NONCE = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE" }
}
