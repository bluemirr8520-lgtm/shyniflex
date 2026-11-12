package io.mirr.shynigsy.player

import io.mirr.shynigsy.player.mpv.MpvStreamDiagnostics
import java.util.Locale

internal fun diagnosticNumber(value: Double?): String = value
    ?.takeIf { it.isFinite() && it >= 0 && it <= 1e12 }
    ?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "확인 중"

internal fun diagnosticDecoder(value: String?): String = when (value) {
    "no" -> "소프트웨어"
    "mediacodec", "mediacodec-copy" -> "하드웨어 ($value)"
    else -> "확인 중"
}

/** Closed fields prevent native/server strings from escaping into diagnostics. */
internal fun playbackDiagnosticLog(engine: String, state: Int, firstFrame: Boolean,
    playing: Boolean, snapshot: MpvStreamDiagnostics?, bufferedDurationMs: Long? = null): String =
    "engine=${if (engine == "mpv") "mpv" else "exo"} state=${state.coerceIn(1, 4)} " +
        "frame=$firstFrame playing=$playing " +
        "cache_s=${diagnosticNumber(snapshot?.cacheSeconds ?: bufferedDurationMs?.takeIf { it >= 0 }?.div(1000.0))} " +
        "cache_pause=${snapshot?.cachePaused == true} " +
        "input_mbps=${diagnosticNumber(snapshot?.inputBytesPerSecond?.times(8)?.div(1_000_000))} " +
        "video_mbps=${diagnosticNumber(snapshot?.videoBitsPerSecond?.div(1_000_000))} " +
        "wait_s=${snapshot?.rebufferTargetSeconds?.coerceIn(1, 6) ?: 1} " +
        "decoder=${when (snapshot?.hardwareDecoder) { "no" -> "sw"; "mediacodec", "mediacodec-copy" -> "hw"; else -> "unknown" }} " +
        "drops=${snapshot?.droppedFrames?.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: -1}"
