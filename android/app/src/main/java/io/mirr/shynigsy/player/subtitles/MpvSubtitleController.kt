package io.mirr.shynigsy.player.subtitles

import androidx.media3.common.C
import androidx.media3.common.Player
import java.util.Locale

/** The original Shyni Canvas renders text; mpv retains bitmap subtitle decoding. */
internal data class MpvSubtitleRenderRoute(
    val showNative: Boolean,
    val showOverlay: Boolean,
)

/** Codec names can come from libmpv, FFprobe, or Media3's original MIME type. */
internal fun isMpvBitmapSubtitle(codec: String?): Boolean {
    val value = codec?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim() ?: return false
    return value in setOf(
        "hdmv_pgs_subtitle", "pgs", "pgssub", "application/pgs",
        "dvd_subtitle", "dvdsub", "vobsub", "application/vobsub",
        "dvb_subtitle", "dvbsub", "dvb_sub", "application/dvbsubs",
        "xsub", "application/x-xsub",
    )
}

internal fun mpvSubtitleRenderRoute(
    enabled: Boolean,
    externalSelected: Boolean,
    selectedCodecs: List<String?>,
    hasTextCues: Boolean = false,
): MpvSubtitleRenderRoute {
    if (!enabled) return MpvSubtitleRenderRoute(showNative = false, showOverlay = false)
    // A downloaded sidecar owns the sole text timeline, even while loading. Keep
    // the embedded decoder from drawing a second subtitle underneath the Canvas.
    if (externalSelected) return MpvSubtitleRenderRoute(showNative = false, showOverlay = true)
    if (selectedCodecs.any(::isMpvBitmapSubtitle)) {
        return MpvSubtitleRenderRoute(showNative = true, showOverlay = false)
    }
    return MpvSubtitleRenderRoute(
        showNative = false,
        showOverlay = selectedCodecs.isNotEmpty() || hasTextCues,
    )
}

/**
 * Bridge mpv's selected tracks to the existing Shyni/Plex Play Canvas views.
 * The callback sets mpv `sub-visibility`, leaving `sid` and decoding active so
 * `sub-text` keeps supplying embedded text for horizontal and vertical writing.
 * All calls must be made on the activity's main thread, as with Media3 Player.
 */
internal class MpvSubtitleController(
    private val setNativeVisibility: (Boolean) -> Unit,
) {
    private var lastNativeVisibility: Boolean? = null

    fun update(
        player: Player,
        enabled: Boolean,
        externalSelected: Boolean,
        hasTextCues: Boolean = false,
    ): MpvSubtitleRenderRoute {
        val codecs = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group ->
                (0 until group.length).filter { group.isTrackSelected(it) }.flatMap { index ->
                    val format = group.getTrackFormat(index)
                    // Media3's APPLICATION_MEDIA3_CUES wrapper stores the original
                    // subtitle codec in Format.codecs, as the old Shyni app does.
                    listOf(format.sampleMimeType, format.codecs)
                }
            }
        return updateRoute(mpvSubtitleRenderRoute(enabled, externalSelected, codecs, hasTextCues))
    }

    /** Call after replacing/reinitializing the mpv instance, before update(). */
    fun reset() {
        lastNativeVisibility = null
    }

    private fun updateRoute(route: MpvSubtitleRenderRoute): MpvSubtitleRenderRoute {
        if (lastNativeVisibility != route.showNative) {
            setNativeVisibility(route.showNative)
            lastNativeVisibility = route.showNative
        }
        return route
    }
}
