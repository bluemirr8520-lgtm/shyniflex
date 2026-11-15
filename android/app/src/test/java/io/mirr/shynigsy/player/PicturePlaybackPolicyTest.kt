package io.mirr.shynigsy.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PicturePlaybackPolicyTest {
    @Test fun metadataWaitAndHdrKeepTheSavedPresetWithoutApplyingIt() {
        val saved = PictureSettings(10, 20, 30)
        assertEquals(ExoPictureSource.UNKNOWN, classifyPictureTransfers(emptyList()))
        assertEquals(PictureSettings(), effectiveExoPictureSettings(saved, ExoPictureSource.UNKNOWN))
        for (transfer in listOf(C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_ST2084)) {
            val hdr = classifyPictureTransfers(listOf(transfer))
            assertEquals(ExoPictureSource.HDR, hdr)
            assertEquals(PictureSettings(), effectiveExoPictureSettings(saved, hdr))
        }
        assertEquals(saved, effectiveExoPictureSettings(saved, ExoPictureSource.SDR))
    }

    @Test fun sdrAndUnspecifiedTransferUseBoundedSettings() {
        for (transfer in listOf(null, C.COLOR_TRANSFER_SDR)) {
            assertEquals(ExoPictureSource.SDR, classifyPictureTransfers(listOf(transfer)))
        }
        assertEquals(PictureSettings(50, -50, 100),
            effectiveExoPictureSettings(PictureSettings(400, -200, 900), ExoPictureSource.SDR))
    }

    @Test fun onlyVideoEffectErrorsSelectTheEffectRecoveryPath() {
        assertTrue(isPictureProcessingError(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED))
        assertTrue(isPictureProcessingError(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED))
        assertFalse(isPictureProcessingError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(isPictureProcessingError(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }
}
