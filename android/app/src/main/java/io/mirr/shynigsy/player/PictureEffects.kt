package io.mirr.shynigsy.player

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix

/** A single GPU matrix changes only decoded video pixels, not controls or captions. */
@UnstableApi
internal fun pictureVideoEffects(settings: PictureSettings): List<Effect> {
    val value = settings.normalized()
    return if (value.isNeutral) emptyList() else listOf(PictureVideoEffect(value))
}

/**
 * Media3's standard Brightness and HSL effects reject HDR. This matrix preserves HDR
 * samples even when a format change reaches the render thread before the UI knows it.
 * Hosts should show the HDR bypass status rather than claim these SDR values apply.
 */
@UnstableApi
internal class PictureVideoEffect(settings: PictureSettings = PictureSettings()) : RgbMatrix {
    // The main thread publishes complete immutable matrices; the GL thread never
    // observes partially written coefficients during a held remote-key gesture.
    @Volatile private var sdr = pictureColorMatrix(settings, useHdr = false)
    private val hdr = pictureColorMatrix(PictureSettings(), useHdr = true)

    /** Keep this effect in the same pipeline; replacing effects re-registers the video stream. */
    fun update(settings: PictureSettings) {
        sdr = pictureColorMatrix(settings, useHdr = false)
    }

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = if (useHdr) hdr else sdr
    // Even a neutral value can change later. Do not let Media3 optimize away this live matrix.
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/** Column-major affine RGB matrix; alpha and geometry are unchanged. */
internal fun pictureColorMatrix(settings: PictureSettings, useHdr: Boolean): FloatArray {
    val value = settings.normalized()
    if (useHdr || value.isNeutral) return floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
    val contrast = value.contrast / 100f
    val gain = (1f + contrast) / (1f - contrast)
    val saturation = 1f + value.saturation / 100f
    val desaturated = 1f - saturation
    // Linear RGB BT.709 luminance coefficients used by Media3's SDR effect pipeline.
    val red = .2126f * desaturated
    val green = .7152f * desaturated
    val blue = .0722f * desaturated
    val offset = value.brightness / 100f + (1f - gain) * .5f
    return floatArrayOf(
        gain * (red + saturation), gain * red, gain * red, 0f,
        gain * green, gain * (green + saturation), gain * green, 0f,
        gain * blue, gain * blue, gain * (blue + saturation), 0f,
        offset, offset, offset, 1f,
    )
}
