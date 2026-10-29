package io.mirr.shynigsy.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import io.mirr.shynigsy.data.PlaybackCodecInfo
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.mpv.MpvExoProbe
import io.mirr.shynigsy.player.subtitles.isEmbedded
import java.util.Locale

/** External timed text uses the same Shyni Canvas for both engines. */
private fun exoExternalSubtitleKind(format: String?): String? = when (format?.trim()?.lowercase(Locale.ROOT)) {
    "srt", "subrip" -> "srt"
    "vtt", "webvtt" -> "vtt"
    "ass", "ssa" -> "ass"
    "smi", "sami" -> "smi"
    "ttml", "dfxp" -> "ttml"
    "mov_text", "tx3g" -> "mov_text"
    else -> null
}

internal fun exoExternalSubtitleFormat(format: String?): Boolean =
    exoExternalSubtitleKind(format)?.let { it != "mov_text" } == true

internal fun exoEmbeddedSubtitle(subtitle: SubtitleTrack): Boolean = subtitle.isEmbedded &&
    exoExternalSubtitleKind(subtitle.format) == "mov_text" && subtitle.streamIndex?.let { it >= 0 } == true

/** A candidate still requires an independent, decoded MPV probe before Exo can start. */
internal fun isPotentialExoCandidate(spec: PlaybackSpec, info: PlaybackCodecInfo?, subtitles: List<SubtitleTrack>?,
    defaultLayout: Boolean): Boolean {
    if (info == null || spec.partId != info.partId || spec.isServerConversion || spec.preferSoftwareDecode ||
        spec.sourceItem?.isSpoiler != false || !defaultLayout || subtitles == null ||
        subtitles.any { !exoEmbeddedSubtitle(it) &&
            (it.source?.trim()?.lowercase(Locale.ROOT) != "external" || !exoExternalSubtitleFormat(it.format)) } ||
        subtitles.count(::exoEmbeddedSubtitle) > 1 ||
        (spec.selectedSubtitleId != null && subtitles.none { it.id == spec.selectedSubtitleId }) ||
        (spec.selectedAudioStreamOrdinal != null && spec.selectedAudioStreamOrdinal != 0)) return false
    fun normalize(s: String?) = s?.trim()?.lowercase(Locale.ROOT)
    val container = normalize(info.container)
    val video = normalize(info.videoCodec)
    val audio = normalize(info.audioCodec)
    val selectedAudio = normalize(info.selectedAudioCodec)
    val profile = normalize(info.videoProfile)
    val audioProfile = normalize(info.audioProfile)
    val hdr = normalize(info.hdrFormat)
    // A track reported by media-info but absent from the complete raw subtitle
    // response is unknown, even if the formats would otherwise be supported.
    val listedKinds = subtitles.mapNotNull { exoExternalSubtitleKind(it.format) }.toMutableList()
    for (format in info.subtitleFormats.orEmpty()) {
        val kind = exoExternalSubtitleKind(format) ?: return false
        if (!listedKinds.remove(kind)) return false
    }
    if (container !in setOf("mp4", "mov", "m4v") || video !in setOf("h264", "avc", "avc1") ||
        audio !in setOf("aac", "mp4a") || (selectedAudio != null && selectedAudio !in setOf("aac", "mp4a")) ||
        (profile != null && profile !in setOf("baseline", "constrained baseline", "main", "high")) ||
        (audioProfile != null && audioProfile !in setOf("lc", "aac lc", "aac-lc", "low complexity")) ||
        (hdr != null && hdr !in setOf("sdr", "standard dynamic range")) ||
        (info.videoBitDepth != null && info.videoBitDepth != 8) ||
        (info.audioChannels != null && info.audioChannels !in setOf(1, 2)) ||
        (info.width != null && info.width !in 1..3840) || (info.height != null && info.height !in 1..2160) ||
        (info.frameRate != null && (!info.frameRate.isFinite() || info.frameRate <= 0 || info.frameRate > 60))) return false
    return true
}

/** Unknown metadata is not SDR: the native decoder must positively report 8-bit BT.709 SDR. */
internal fun isSafeExoProbe(info: PlaybackCodecInfo, probe: MpvExoProbe,
    hardwareDecoderSupported: (Int, Int, Double, String) -> Boolean,
    subtitles: List<SubtitleTrack> = emptyList()): Boolean {
    fun normalize(s: String?) = s?.trim()?.lowercase(Locale.ROOT)
    val tracks = probe.tracks
    // Media3 may omit unsupported tracks: require a unique server stream index
    // to agree with the independently observed MPV track before handoff.
    val embedded = subtitles.filter(::exoEmbeddedSubtitle)
    if (embedded.size > 1 || tracks.subtitleStreamIndex != embedded.singleOrNull()?.streamIndex) return false
    val profile = normalize(tracks.videoProfile) ?: return false
    val audioProfile = normalize(tracks.audioProfile)
    val serverProfile = normalize(info.videoProfile)
    val serverAudioProfile = normalize(info.audioProfile)
    if (profile !in setOf("baseline", "constrained baseline", "main", "high") ||
        (audioProfile != null && audioProfile !in setOf("lc", "aac lc", "aac-lc", "low complexity")) ||
        (serverProfile != null && serverProfile != profile) ||
        (serverAudioProfile != null && serverAudioProfile !in setOf("lc", "aac lc", "aac-lc", "low complexity"))) return false
    val pixel = normalize(probe.pixelFormat)
    val underlyingPixel = normalize(probe.hardwarePixelFormat)
    val actualPixel = if (pixel in setOf("mediacodec", "mediacodec_v", "drm_prime")) underlyingPixel else pixel
    if (actualPixel !in setOf("yuv420p", "yuvj420p", "nv12", "nv21") ||
        normalize(probe.primaries) != "bt.709" || normalize(probe.gamma) !in setOf("bt.1886", "srgb", "gamma2.2", "gamma2.4")) return false
    val width = probe.decodedWidth?.takeIf { it in 1..3840 } ?: return false
    val height = probe.decodedHeight?.takeIf { it in 1..2160 } ?: return false
    val fps = (tracks.frameRate ?: probe.frameRate)?.takeIf { it.isFinite() && it > 0 && it <= 60 } ?: return false
    if ((info.width != null && info.width != width) || (info.height != null && info.height != height) ||
        (info.audioChannels != null && tracks.audioChannels != null && info.audioChannels != tracks.audioChannels) ||
        (tracks.audioChannels != null && tracks.audioChannels !in setOf(1, 2)) ||
        (info.frameRate != null && kotlin.math.abs(info.frameRate - fps) > 0.1)) return false
    return hardwareDecoderSupported(width, height, fps, profile)
}

internal fun hasAvcHardwareDecoder(width: Int, height: Int, fps: Double, profile: String): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val expectedProfile = when (profile) {
        "baseline", "constrained baseline" -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        "main" -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        "high" -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        else -> return false
    }
    return runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            !codec.isEncoder && codec.isHardwareAccelerated && codec.supportedTypes.any { it.equals("video/avc", true) } &&
                runCatching {
                    val caps = codec.getCapabilitiesForType("video/avc")
                    caps.profileLevels.any { it.profile == expectedProfile } &&
                        (caps.videoCapabilities?.areSizeAndRateSupported(width, height, fps) == true)
                }.getOrDefault(false)
        }
    }.getOrDefault(false)
}

/** Validate actual extraction before starting: do not discard tracks or subtitle rendering. */
internal data class ExtractedTrack(val type: Int, val mime: String?, val supported: Boolean, val selected: Boolean,
    val codecs: String? = null, val channels: Int? = null)

internal fun exoMovTextMime(mime: String?, codecs: String?): Boolean =
    mime == MimeTypes.APPLICATION_TX3G ||
        (mime == MimeTypes.APPLICATION_MEDIA3_CUES && codecs == MimeTypes.APPLICATION_TX3G)

internal fun extractedTracksSafe(tracks: List<ExtractedTrack>, embeddedExpected: Boolean = false): Boolean {
    if (tracks.any { it.type == C.TRACK_TYPE_IMAGE || it.type == C.TRACK_TYPE_UNKNOWN }) return false
    val text = tracks.filter { it.type == C.TRACK_TYPE_TEXT }
    if (text.size != (if (embeddedExpected) 1 else 0)) return false
    if (text.any { !it.supported || !exoMovTextMime(it.mime, it.codecs) }) return false
    val video = tracks.filter { it.type == C.TRACK_TYPE_VIDEO }
    val audio = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (video.size != 1 || audio.size != 1) return false
    val v = video.single(); val a = audio.single()
    return v.supported && a.supported && v.selected && a.selected &&
        v.mime == MimeTypes.VIDEO_H264 && a.mime == MimeTypes.AUDIO_AAC &&
        a.codecs?.trim()?.lowercase(Locale.ROOT) == "mp4a.40.2" && a.channels in setOf(1, 2)
}

internal fun exoTracksSafe(tracks: Tracks, embeddedExpected: Boolean = false): Boolean = extractedTracksSafe(tracks.groups.flatMap { group ->
    (0 until group.length).map { index ->
        val format = group.getTrackFormat(index)
        ExtractedTrack(group.type, format.sampleMimeType, group.isTrackSupported(index), group.isTrackSelected(index),
            format.codecs, format.channelCount.takeIf { it > 0 })
    }
}, embeddedExpected)

/** Network, permission and TLS errors are never treated as codec failures. */
internal fun shouldFallbackFromExo(errorCode: Int): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

internal fun exoFallbackPosition(previous: Long, current: Long, validated: Boolean, firstFrame: Boolean): Long =
    if (validated && firstFrame && current >= 0) current else previous
