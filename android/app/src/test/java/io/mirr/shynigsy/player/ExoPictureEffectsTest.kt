package io.mirr.shynigsy.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test

class ExoPictureEffectsTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun apply(settings: PictureSettings, rgb: FloatArray): FloatArray {
        val matrix = exoPictureColorMatrix(settings)
        return FloatArray(3) { row -> matrix[row] * rgb[0] + matrix[row + 4] * rgb[1] +
            matrix[row + 8] * rgb[2] + matrix[row + 12] }
    }

    @Test fun neutralLeavesPixelsAndAlphaUnchanged() {
        assertArrayEquals(identity, exoPictureColorMatrix(PictureSettings()), 0f)
        val color = floatArrayOf(.13f, .42f, .81f)
        assertArrayEquals(color, apply(PictureSettings(), color), 0f)
        val combined = exoPictureColorMatrix(PictureSettings(-20, 35, -70))
        assertEquals(0f, combined[3], 0f)
        assertEquals(0f, combined[7], 0f)
        assertEquals(0f, combined[11], 0f)
        assertEquals(1f, combined[15], 0f)
    }

    @Test fun brightnessLiftsChannelsAndContrastPreservesTheMidpoint() {
        assertArrayEquals(floatArrayOf(.3f, .5f, .7f),
            apply(PictureSettings(brightness = 10), floatArrayOf(.2f, .4f, .6f)), .00001f)
        assertArrayEquals(floatArrayOf(-.25f, .5f, 1.25f),
            apply(PictureSettings(contrast = 50), floatArrayOf(.25f, .5f, .75f)), .00001f)
    }

    @Test fun zeroSaturationProducesGrayscaleWithBt709Luminance() {
        assertArrayEquals(floatArrayOf(.2126f, .2126f, .2126f),
            apply(PictureSettings(saturation = -100), floatArrayOf(1f, 0f, 0f)), .00001f)
        assertArrayEquals(floatArrayOf(.7152f, .7152f, .7152f),
            apply(PictureSettings(saturation = -100), floatArrayOf(0f, 1f, 0f)), .00001f)
        assertArrayEquals(floatArrayOf(.4f, .4f, .4f),
            apply(PictureSettings(saturation = 100), floatArrayOf(.4f, .4f, .4f)), .00001f)
    }

    @Test fun liveUpdatesDoNotMutateAnOlderFrameAndNeutralRemainsUpdateable() {
        val effect = ExoPictureEffect(PictureSettings(brightness = 10))
        val previousFrame = effect.getMatrix(100, false)
        val previousValues = previousFrame.copyOf()
        val next = PictureSettings(contrast = 20, saturation = -50)
        effect.update(next)
        assertArrayEquals(previousValues, previousFrame, 0f)
        assertArrayEquals(exoPictureColorMatrix(next), effect.getMatrix(200, false), 0f)
        effect.update(PictureSettings())
        assertArrayEquals(identity, effect.getMatrix(300, false), 0f)
        assertFalse(effect.isNoOp(1920, 1080))
    }

    @Test fun hdrIsTemporarilyUnchangedAndOutOfRangeSettingsAreBounded() {
        val bounded = PictureSettings(50, -50, 100)
        val oversized = PictureSettings(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
        assertArrayEquals(exoPictureColorMatrix(bounded), exoPictureColorMatrix(oversized), 0f)
        assertTrue(exoPictureColorMatrix(oversized).all(Float::isFinite))
        assertArrayEquals(identity, exoPictureColorMatrix(oversized, useHdr = true), 0f)
        val effect = ExoPictureEffect(oversized)
        assertArrayEquals(identity, effect.getMatrix(0, true), 0f)
        effect.update(PictureSettings(50, 50, 100))
        assertArrayEquals(identity, effect.getMatrix(1, true), 0f)
    }

    @Test fun onlyFrameProcessingFailuresUseThePictureRecoveryPath() {
        assertTrue(isExoPictureProcessingError(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED))
        assertTrue(isExoPictureProcessingError(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED))
        assertFalse(isExoPictureProcessingError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(isExoPictureProcessingError(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
    }

    @Test fun displayAspectUsesPixelRatioAndQuarterTurnRotation() {
        val landscape = Format.Builder().setWidth(1920).setHeight(1080).build()
        assertEquals(16f / 9f, exoPictureAspectRatio(landscape)!!, .00001f)
        for (rotation in listOf(90, 270, -90)) {
            assertEquals(9f / 16f, exoPictureAspectRatio(landscape.buildUpon().setRotationDegrees(rotation).build())!!, .00001f)
        }
        assertEquals(16f / 9f, exoPictureAspectRatio(landscape.buildUpon().setRotationDegrees(180).build())!!, .00001f)
        val anamorphic = Format.Builder().setWidth(720).setHeight(576).setPixelWidthHeightRatio(16f / 15f).build()
        assertEquals(4f / 3f, exoPictureAspectRatio(anamorphic)!!, .00001f)
        assertEquals(3f / 4f, exoPictureAspectRatio(anamorphic.buildUpon().setRotationDegrees(90).build())!!, .00001f)
    }

    @Test fun unknownOrInvalidGeometryDoesNotInventAnAspectRatio() {
        assertNull(exoPictureAspectRatio(Format.Builder().build()))
        val valid = Format.Builder().setWidth(1920).setHeight(1080).build()
        assertNull(exoPictureAspectRatio(valid.buildUpon().setWidth(0).build()))
        assertNull(exoPictureAspectRatio(valid.buildUpon().setHeight(-1).build()))
        assertNull(exoPictureAspectRatio(valid.buildUpon().setRotationDegrees(45).build()))
        // Format canonicalizes NO_VALUE (-1) to square pixels before the helper sees it.
        assertEquals(16f / 9f,
            exoPictureAspectRatio(valid.buildUpon().setPixelWidthHeightRatio(Format.NO_VALUE.toFloat()).build())!!,
            .00001f)
        for (pixelRatio in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(exoPictureAspectRatio(valid.buildUpon().setPixelWidthHeightRatio(pixelRatio).build()))
        }
    }
}
