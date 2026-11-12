package io.mirr.shynigsy.player.mpv

/** Local measurements only. Never include paths, titles, tickets or request headers. */
data class MpvStreamDiagnostics(
    val inputBytesPerSecond: Double?,
    val videoBitsPerSecond: Double?,
    val audioBitsPerSecond: Double?,
    val cacheSeconds: Double?,
    val cachePaused: Boolean,
    val hardwareDecoder: String?,
    val droppedFrames: Long?,
    val rebufferTargetSeconds: Int,
)
