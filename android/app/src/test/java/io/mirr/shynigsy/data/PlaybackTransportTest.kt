package io.mirr.shynigsy.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransportTest {
    private fun spec(url: String, height: Int? = null) = PlaybackSpec("test", url,
        itemId = 1, libraryId = 1, partId = 42, conversionHeight = height)

    @Test fun allAutomaticProgressiveModesUseTheConversionClock() {
        for (mode in listOf("audio", "remux", "video")) {
            assertTrue(spec("https://test.invalid/transcode/42?ticket=opaque&mode=$mode").isServerConversion)
        }
    }

    @Test fun manualConversionHeightExplicitlyMarksTheTransport() {
        assertTrue(spec("https://test.invalid/stream/42?ticket=opaque", height = 720).isServerConversion)
    }

    @Test fun directOriginalStreamRemainsDirect() {
        assertFalse(spec("https://test.invalid/stream/42?ticket=opaque").isServerConversion)
    }

    @Test fun reverseProxyPrefixAndRedirectingTrailingSlashAreSupported() {
        assertTrue(spec("http://test.invalid:8080/shyni/api/transcode/42/?mode=audio").isServerConversion)
    }

    @Test fun queryAndFragmentTextNeverDetermineTheTransport() {
        assertFalse(spec("https://test.invalid/stream/42?return=/transcode/42#transcode/42").isServerConversion)
    }

    @Test fun onlyTheExactCurrentPartRouteMatches() {
        assertFalse(spec("https://test.invalid/transcode/142?mode=audio").isServerConversion)
        assertFalse(spec("https://test.invalid/transcode/41?mode=audio").isServerConversion)
        assertFalse(spec("https://test.invalid/not-transcode/42?mode=audio").isServerConversion)
        assertFalse(spec("https://test.invalid/transcode/42/hls").isServerConversion)
        assertFalse(spec("https://test.invalid/transcode/hls/session/index.m3u8").isServerConversion)
    }

    @Test fun invalidOrNonHttpUrlsAreNotMistakenForServerConversion() {
        assertFalse(spec("not a valid URL").isServerConversion)
        assertFalse(spec("file:///transcode/42").isServerConversion)
        assertFalse(spec("/transcode/42?mode=audio").isServerConversion)
    }

    @Test fun automaticConversionIsRecognizedAfterActivityStateRoundTrip() {
        val original = spec("https://test.invalid/transcode/42?ticket=opaque&mode=remux")
        assertTrue(PlaybackSpec.fromJson(original.toJson()).isServerConversion)
    }
}
