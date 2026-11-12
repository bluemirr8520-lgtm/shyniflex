package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import java.net.URI

/** Music conversion has a separate route and must not change the video timeline. */
private fun PlaybackSpec.isMusicConversionLabel(): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return partId > 0 && (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
        !uri.host.isNullOrBlank() && uri.path?.trimEnd('/')?.endsWith("/transcode/music/$partId") == true
}

/** Identifies the server playback transport, not the device decoder. */
internal fun playbackModeLabel(spec: PlaybackSpec): String = when {
    !spec.isServerConversion && !spec.isMusicConversionLabel() -> "원본"
    spec.conversionHeight != null -> "변환 ${spec.conversionHeight}p"
    else -> "변환 자동"
}

/** Keep the action visible while also showing the current transport. */
internal fun playbackModeButtonLabel(spec: PlaybackSpec): String =
    "변환\n${playbackModeLabel(spec).removePrefix("변환 ")}"
