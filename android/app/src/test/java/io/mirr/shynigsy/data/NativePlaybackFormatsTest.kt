package io.mirr.shynigsy.data

import io.mirr.shynigsy.player.EngineStart
import io.mirr.shynigsy.player.PlaybackEnginePreference
import io.mirr.shynigsy.player.engineStart
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackFormatsTest {
    @Test fun knownLegacyContainersAndWindowsMediaCodecsStartNativeEvenWithExoSaved() {
        for (format in listOf("WMV", " asf ", "video/x-ms-wmv", "avi", "flv", "rmvb", "mpegts", "m2ts", "vob", "ogv")) {
            assertTrue(format, sourceNeedsNativeEngine(format, null, null))
            assertTrue(format, preferNativeOriginal("remux", format, null, null))
        }
        for (codec in listOf("wmv1", "wmv2", "wmv3", "VC1", "vc-1", "rv40"))
            assertTrue(codec, sourceNeedsNativeEngine(null, codec, null))
        for (codec in listOf("wmav1", "wmav2", "WMAPRO", "wmalossless", "wmavoice"))
            assertTrue(codec, sourceNeedsNativeEngine(null, null, codec))
        assertEquals(EngineStart.SAFE_MPV, engineStart(PlaybackEnginePreference.EXO, false, false,
            false, false, false, false, nativeFormat = true))
    }

    @Test fun unknownOrModernFormatsAndExplicitVideoConversionKeepExistingPolicy() {
        for (format in listOf(null, "", "mp4", "mov,mp4,m4a,3gp,3g2,mj2", "mkv", "webm", "movie.wmv"))
            assertFalse(sourceNeedsNativeEngine(format, "h264", "aac"))
        for (mode in listOf(null, "video", "music", "unknown"))
            assertFalse(preferNativeOriginal(mode, "wmv", "vc1", "wmapro"))
        assertFalse(preferNativeOriginal("audio", "mp4", "hevc", "eac3"))
    }

    @Test fun exactSourceFormatHintsSurviveRecreationWithoutChangingTheTicket() {
        val spec = PlaybackSpec("fixture", "https://fixture.invalid/stream/42?ticket=fixture",
            itemId = 1, libraryId = 2, partId = 42, sourceContainer = "wmv", sourceVideoCodec = "vc1",
            selectedAudioCodec = "wmapro")
        assertEquals(spec, PlaybackSpec.fromJson(spec.toJson()))
        val old = PlaybackSpec.fromJson(spec.copy(sourceContainer = null, sourceVideoCodec = null).toJson())
        assertNull(old.sourceContainer)
        assertNull(old.sourceVideoCodec)
    }
}
