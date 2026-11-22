package io.mirr.shynigsy.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/** Used only for corrected Exo playback; neutral playback keeps the standard factory. */
@UnstableApi
internal class ExoPictureRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Match Media3 1.10.1's default renderer configuration. No extension renderer is
        // enabled by this factory; audio, text and metadata still use the superclass.
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .experimentalSetParseAv1SampleDependencies(true)
            .experimentalSetLateThresholdToDropDecoderInputUs(
                MediaCodecVideoRenderer.DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US)
            .setEnableDurationToProgressUs(false)
        // The builder and default factory both leave buffer-decode-only disabled.
        out.add(ReplayablePictureVideoRenderer(builder))
    }
}

/** Keep MediaCodec's renderer lifecycle; only opt its effect graph into paused redraws. */
@UnstableApi
private class ReplayablePictureVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper = PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
        .setEnablePlaylistMode(true)
        .setEnableReplayableCache(true)
        .experimentalSetLateThresholdToDropInputUs(DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US)
        .setClock(getClock())
        .build()
}
