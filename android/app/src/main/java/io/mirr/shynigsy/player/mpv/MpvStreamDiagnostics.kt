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

/** The JNI's Int getter narrows int64_t to jint. Do not use it for byte/rate counters. */
internal fun readMpvDiagnosticNumber(property: String, doubleReader: (String) -> Double?,
    stringReader: (String) -> String?): Double? {
    fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() && it in 0.0..1e12 }
    return valid(doubleReader(property)) ?: valid(stringReader(property)
        ?.takeIf { it.length <= 64 }?.trim()?.toDoubleOrNull())
}
