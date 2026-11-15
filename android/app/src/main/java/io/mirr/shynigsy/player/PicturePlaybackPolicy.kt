package io.mirr.shynigsy.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks

internal enum class ExoPictureSource { UNKNOWN, SDR, HDR }

/** Wait for selected video metadata; an unselected HDR track cannot disable an SDR stream. */
internal fun classifyExoPictureSource(tracks: Tracks): ExoPictureSource {
    val transfers = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it).colorInfo?.colorTransfer }
    }
    return classifyPictureTransfers(transfers)
}

internal fun classifyPictureTransfers(selectedTransfers: List<Int?>): ExoPictureSource = when {
    selectedTransfers.isEmpty() -> ExoPictureSource.UNKNOWN
    selectedTransfers.any { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG } -> ExoPictureSource.HDR
    else -> ExoPictureSource.SDR
}

internal fun effectiveExoPictureSettings(settings: PictureSettings, source: ExoPictureSource): PictureSettings =
    if (source == ExoPictureSource.SDR) settings.normalized() else PictureSettings()

internal fun isPictureProcessingError(code: Int) = code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
    code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
