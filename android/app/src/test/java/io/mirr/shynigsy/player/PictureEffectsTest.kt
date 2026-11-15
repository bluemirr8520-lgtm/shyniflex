package io.mirr.shynigsy.player

import androidx.media3.effect.RgbMatrix
import org.junit.Assert.*
import org.junit.Test

class PictureEffectsTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun apply(settings: PictureSettings, rgb: FloatArray): FloatArray {
        val matrix = pictureColorMatrix(settings, useHdr = false)
        return FloatArray(3) { row -> matrix[row] * rgb[0] + matrix[4 + row] * rgb[1] +
            matrix[8 + row] * rgb[2] + matrix[12 + row] }
    }

    @Test fun originalHasNoEffectsAndAnExactIdentity() {
        assertTrue(pictureVideoEffects(PictureSettings()).isEmpty())
        assertArrayEquals(identity, pictureColorMatrix(PictureSettings(), false), 0f)
    }

    @Test fun brightnessChangesVideoValuesAndLeavesColorDifferences() {
        assertArrayEquals(floatArrayOf(.3f, .5f, .7f),
            apply(PictureSettings(brightness = 10), floatArrayOf(.2f, .4f, .6f)), .00001f)
    }

    @Test fun desaturationProducesEqualBt709LuminanceChannels() {
        val red = apply(PictureSettings(saturation = -100), floatArrayOf(1f, 0f, 0f))
        assertArrayEquals(floatArrayOf(.2126f, .2126f, .2126f), red, .00001f)
        assertArrayEquals(floatArrayOf(.4f, .4f, .4f),
            apply(PictureSettings(saturation = 100), floatArrayOf(.4f, .4f, .4f)), .00001f)
    }

    @Test fun contrastKeepsTheMidpointWhileSeparatingDarkAndLightPixels() {
        val settings = PictureSettings(contrast = 50)
        assertArrayEquals(floatArrayOf(-.25f, .5f, 1.25f), apply(settings, floatArrayOf(.25f, .5f, .75f)), .00001f)
    }

    @Test fun unsafeInputIsBoundedAndHdrNeverUsesSdrTransforms() {
        assertArrayEquals(pictureColorMatrix(PictureSettings(50, -50, 100), false),
            pictureColorMatrix(PictureSettings(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE), false), 0f)
        assertArrayEquals(identity, pictureColorMatrix(PictureSettings(50, 50, 100), true), 0f)
        val effect = pictureVideoEffects(PictureSettings(10, 20, -30)).single() as RgbMatrix
        assertArrayEquals(identity, effect.getMatrix(0, true), 0f)
        assertFalse(effect.isNoOp(1920, 1080))
    }

    @Test fun combinedAdjustmentsUseOneMatrixAndNeverAlterAlpha() {
        assertEquals(1, pictureVideoEffects(PictureSettings(10, 10, 10)).size)
        val matrix = pictureColorMatrix(PictureSettings(-50, 50, -100), false)
        assertEquals(0f, matrix[3], 0f); assertEquals(0f, matrix[7], 0f)
        assertEquals(0f, matrix[11], 0f); assertEquals(1f, matrix[15], 0f)
        assertTrue(matrix.all { it.isFinite() })
    }

    @Test fun updatingAnInstalledEffectChangesValuesWithoutReplacingItOrMutatingAnOlderFrame() {
        val effect = PictureVideoEffect(PictureSettings(brightness = 10))
        val oldFrame = effect.getMatrix(100, false)
        val originalFrame = oldFrame.copyOf()
        effect.update(PictureSettings(contrast = 20, saturation = -50))
        assertArrayEquals(originalFrame, oldFrame, 0f)
        assertArrayEquals(pictureColorMatrix(PictureSettings(contrast = 20, saturation = -50), false),
            effect.getMatrix(200, false), 0f)
        effect.update(PictureSettings())
        assertArrayEquals(identity, effect.getMatrix(300, false), 0f)
        assertFalse(effect.isNoOp(1920, 1080))
        effect.update(PictureSettings(50, 50, 100))
        assertArrayEquals(identity, effect.getMatrix(400, true), 0f)
    }
}
