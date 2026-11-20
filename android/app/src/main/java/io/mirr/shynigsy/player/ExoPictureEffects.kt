package io.mirr.shynigsy.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import java.util.Locale

/**
 * Install once before ExoPlayer.prepare(), only for a playback that needs correction.
 * Updates retain the same processing stream. A paused player may request REDRAW separately.
 */
@UnstableApi
internal class ExoPictureEffect(settings: PictureSettings) : RgbMatrix {
    // Publish complete arrays: the GL thread cannot see half-written slider values.
    @Volatile private var sdrMatrix = exoPictureColorMatrix(settings)
    private val hdrIdentity = exoPictureColorMatrix(PictureSettings())

    fun update(settings: PictureSettings) {
        sdrMatrix = exoPictureColorMatrix(settings)
    }

    // A format change may reach GL before its application-thread callback. The identity
    // guard leaves HDR values alone until the host changes to its supported HDR route.
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
        if (useHdr) hdrIdentity else sdrMatrix

    // Neutral can later change; Media3 must not optimize this installed live effect away.
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/** Affine, column-major RGB matrix for Media3's linear BT.709 SDR working space. */
internal fun exoPictureColorMatrix(settings: PictureSettings, useHdr: Boolean = false): FloatArray {
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
    val red = .2126f * (1f - saturation)
    val green = .7152f * (1f - saturation)
    val blue = .0722f * (1f - saturation)
    val offset = value.brightness / 100f + (1f - gain) * .5f
    return floatArrayOf(
        gain * (red + saturation), gain * red, gain * red, 0f,
        gain * green, gain * (green + saturation), gain * green, 0f,
        gain * blue, gain * blue, gain * (blue + saturation), 0f,
        offset, offset, offset, 1f,
    )
}

internal enum class ExoPictureUnsupportedReason { HDR, DOLBY_VISION, DRM }

/** The effect sink in Media3 1.10.1 suppresses video-size events; keep its workaround intact. */
internal fun exoPictureAspectRatio(tracks: Tracks): Float? = tracks.groups.firstNotNullOfOrNull { group ->
    if (group.type != C.TRACK_TYPE_VIDEO) null else (0 until group.length).firstNotNullOfOrNull { index ->
        if (group.isTrackSelected(index)) exoPictureAspectRatio(group.getTrackFormat(index)) else null
    }
}

/** Display geometry, including decoder-applied rotation and non-square source pixels. */
internal fun exoPictureAspectRatio(format: Format): Float? {
    if (format.width <= 0 || format.height <= 0 || !format.pixelWidthHeightRatio.isFinite() ||
        format.pixelWidthHeightRatio <= 0f) return null
    val rotation = ((format.rotationDegrees % 360) + 360) % 360
    val unrotated = format.width.toDouble() * format.pixelWidthHeightRatio / format.height
    val ratio = when (rotation) {
        0, 180 -> unrotated
        90, 270 -> 1.0 / unrotated
        else -> return null
    }.toFloat()
    return ratio.takeIf { it.isFinite() && it > 0f }
}

/** Only selected video matters. DRM must not be treated as permission to switch engines. */
internal fun exoPictureUnsupportedReason(tracks: Tracks): ExoPictureUnsupportedReason? {
    val reasons = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter(group::isTrackSelected)
            .mapNotNull { exoPictureUnsupportedReason(group.getTrackFormat(it)) }
    }
    return when {
        ExoPictureUnsupportedReason.DRM in reasons -> ExoPictureUnsupportedReason.DRM
        ExoPictureUnsupportedReason.DOLBY_VISION in reasons -> ExoPictureUnsupportedReason.DOLBY_VISION
        ExoPictureUnsupportedReason.HDR in reasons -> ExoPictureUnsupportedReason.HDR
        else -> null
    }
}

internal fun exoPictureUnsupportedReason(format: Format): ExoPictureUnsupportedReason? {
    val codec = format.codecs?.trim()?.lowercase(Locale.ROOT)?.substringBefore('.')
    return when {
        format.drmInitData != null -> ExoPictureUnsupportedReason.DRM
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || codec in setOf("dvhe", "dvh1", "dvav", "dva1") ->
            ExoPictureUnsupportedReason.DOLBY_VISION
        format.colorInfo?.colorTransfer in setOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG) ||
            format.colorInfo?.hdrStaticInfo != null -> ExoPictureUnsupportedReason.HDR
        else -> null
    }
}

internal fun isExoPictureProcessingError(code: Int): Boolean =
    code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
        code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
