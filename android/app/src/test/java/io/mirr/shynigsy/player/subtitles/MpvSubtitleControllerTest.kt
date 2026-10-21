package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSubtitleControllerTest {
    @Test
    fun bitmapTracksStayInMpvInsteadOfAnEmptyTextOverlay() {
        listOf("hdmv_pgs_subtitle", "application/pgs", "dvd_subtitle", "vobsub", "dvb_subtitle", "xsub")
            .forEach { codec ->
                val route = mpvSubtitleRenderRoute(true, false, listOf(codec))
                assertTrue("Bitmap codec $codec must stay native", route.showNative)
                assertFalse(route.showOverlay)
            }
    }

    @Test
    fun wrappedBitmapMimeStillUsesItsOriginalCodec() {
        val route = mpvSubtitleRenderRoute(true, false, listOf("application/x-media3-cues", "hdmv_pgs_subtitle"))
        assertTrue(route.showNative)
        assertFalse(route.showOverlay)
    }

    @Test
    fun everyPlainTextFormatUsesTheOriginalShyniRenderer() {
        listOf("ass", "ssa", "subrip", "srt", "webvtt", "sami", "ttml", "mov_text", "application/x-subrip", "text/vtt")
            .forEach { codec ->
                val route = mpvSubtitleRenderRoute(true, false, listOf(codec))
                assertFalse("Text codec $codec must not appear twice", route.showNative)
                assertTrue(route.showOverlay)
            }
    }

    @Test
    fun externalSidecarOverridesTheEmbeddedBitmapRenderer() {
        val route = mpvSubtitleRenderRoute(true, true, listOf("hdmv_pgs_subtitle"))
        assertFalse(route.showNative)
        assertTrue(route.showOverlay)
    }

    @Test
    fun disablingSubtitlesHidesBothRenderers() {
        listOf(emptyList(), listOf("ass"), listOf("hdmv_pgs_subtitle")).forEach { codecs ->
            assertEquals(MpvSubtitleRenderRoute(false, false), mpvSubtitleRenderRoute(false, true, codecs, true))
        }
    }

    @Test
    fun noSelectionIsHiddenButLateDecodedTextCanStillReachTheOverlay() {
        assertEquals(MpvSubtitleRenderRoute(false, false), mpvSubtitleRenderRoute(true, false, emptyList()))
        assertEquals(MpvSubtitleRenderRoute(false, true), mpvSubtitleRenderRoute(true, false, emptyList(), true))
    }

    @Test
    fun bitmapMatchingIsCaseInsensitiveAndDoesNotTreatUnknownTextAsBitmap() {
        assertTrue(isMpvBitmapSubtitle(" APPLICATION/PGS; charset=binary "))
        assertTrue(isMpvBitmapSubtitle("DVD_SUBTITLE"))
        assertFalse(isMpvBitmapSubtitle("ass"))
        assertFalse(isMpvBitmapSubtitle(null))
    }
}
