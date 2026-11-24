package io.mirr.shynigsy.player.mpv

import kotlin.math.min

/** The render target may be letterboxed. Use the video scale, not the window's total pixel count. */
internal data class ShaderFrameSize(val width: Int, val height: Int, val outputWidth: Int, val outputHeight: Int) {
    private val scale get() = min(outputWidth.toDouble() / width, outputHeight.toDouble() / height)
    private val large get() = maxOf(width, height) >= 1800 || maxOf(outputWidth, outputHeight) >= 3200

    /** Every pass of these two families is conditional on upscaling. Skip even reading their CNNs. */
    fun allPassesInactive(id: String?, mayCropOrZoom: Boolean): Boolean =
        !mayCropOrZoom && outputWidth in 1..width && outputHeight in 1..height &&
            (id == PLEZY_NVSCALER_ID || id?.startsWith("plezy/artcnn_") == true)

    fun realtimeCandidate(id: String): Boolean {
        if (minOf(width, height, outputWidth, outputHeight) <= 0 || !large) return false
        return when {
            id == PLEZY_NVSCALER_ID -> scale > 1.0
            id.startsWith("plezy/artcnn_") -> scale > 1.3
            // Mode C only runs its upscaler when the target exceeds the input threshold.
            id.endsWith("_modeC") -> scale > 1.2
            else -> id.startsWith("plezy/anime4k_")
        }
    }
}

/** Measure sustained VO loss on unprofiled GPUs. Ignore shader compilation, seeks and cache stalls.
 * Once engaged, keep the lower-cost path for this preset and geometry instead of switching back and forth. */
internal class MpvShaderPerformance(private val shieldTegra: Boolean) {
    private var selection: Pair<String, ShaderFrameSize>? = null
    private val slow = mutableSetOf<Pair<String, ShaderFrameSize>>()
    private val unsupported = mutableSetOf<Pair<String, ShaderFrameSize>>()
    private var previousDrops: Int? = null
    private var since = 0L
    private var lateWindows = 0

    fun select(id: String?, size: ShaderFrameSize, now: Long): Boolean {
        val key = id?.takeIf { size.realtimeCandidate(it) }?.let { it to size }
        if (selection != key) {
            selection = key
            restartSample(now)
        }
        return key != null && key !in unsupported && (shieldTegra || key in slow)
    }

    val needsSamples: Boolean get() = selection != null && !shieldTegra && selection !in slow && selection !in unsupported

    fun rejectRealtimePath() { selection?.let(unsupported::add) }

    fun restartSample(now: Long) {
        previousDrops = null
        since = now
        lateWindows = 0
    }

    fun observe(now: Long, drops: Int?, fps: Double): Boolean {
        val key = selection ?: return false
        if (!needsSamples || drops == null || !fps.isFinite() || fps <= 0) return false
        val elapsed = now - since
        if (elapsed < 1500) return false
        val previous = previousDrops
        previousDrops = drops
        since = now
        if (previous == null || drops < previous || elapsed > 5000) {
            lateWindows = 0
            return false
        }
        val expected = fps * elapsed / 1000.0
        lateWindows = if (drops - previous >= maxOf(3.0, expected * 0.08)) lateWindows + 1 else 0
        if (lateWindows < 2) return false
        slow += key
        return true
    }
}

/** Bound the number of reads to five per source pixel. mpv scales the result once to the display.
 * Denoise modes blend small luminance differences, restore/sharpen modes reinforce edges.
 * Adjusting only luminance preserves the original chroma and leaves flat areas unchanged. */
internal fun realtimeShaderSource(id: String): String {
    val denoise = id.endsWith("_dn") || id.endsWith("_ds")
    val detail = when {
        id.endsWith("_dn") -> "0.0"
        id.endsWith("_ds") -> "0.38"
        id == PLEZY_NVSCALER_ID -> "0.32"
        id.endsWith("_modeAA") || id.endsWith("_modeBB") || id.endsWith("_modeCA") -> "0.44"
        id.endsWith("_modeA") -> "0.38"
        else -> "0.30"
    }
    val correction = if (denoise)
        "mix(-0.60 * delta, $detail * delta, smoothstep(0.025, 0.10, hi - lo))"
    else "$detail * delta"
    return """//!HOOK MAIN
        |//!BIND HOOKED
        |//!DESC Android realtime luminance detail
        |float lum(vec3 rgb) { return dot(rgb, vec3(0.2126, 0.7152, 0.0722)); }
        |vec4 hook() {
        |    vec4 c = HOOKED_tex(HOOKED_pos);
        |    float y = lum(c.rgb);
        |    float l = lum(HOOKED_texOff(vec2(-1.0, 0.0)).rgb);
        |    float r = lum(HOOKED_texOff(vec2( 1.0, 0.0)).rgb);
        |    float u = lum(HOOKED_texOff(vec2(0.0, -1.0)).rgb);
        |    float d = lum(HOOKED_texOff(vec2(0.0,  1.0)).rgb);
        |    float lo = min(y, min(min(l,r), min(u,d)));
        |    float hi = max(y, max(max(l,r), max(u,d)));
        |    float delta = y - 0.25 * (l + r + u + d);
        |    float adjust = clamp($correction, -0.04, 0.04);
        |    return vec4(clamp(c.rgb + vec3(adjust), 0.0, 1.0), c.a);
        |}
    """.trimMargin()
}
