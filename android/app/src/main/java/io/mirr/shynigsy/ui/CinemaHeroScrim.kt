package io.mirr.shynigsy.ui

internal const val CINEMA_SCRIM_MAX_ALPHA = .35f
internal const val CINEMA_SCRIM_FADE_STEPS = 32

/** Original title-protection opacity, separable into horizontal and vertical fades. */
internal fun cinemaScrimAlphaAt(horizontalFraction: Float, verticalFadeFraction: Float, wide: Boolean): Float {
    val x = horizontalFraction.coerceIn(0f, 1f)
    val y = verticalFadeFraction.coerceIn(0f, 1f)
    val horizontal = if (!wide || x <= .65f) 1f else (1f - x) / .35f
    return CINEMA_SCRIM_MAX_ALPHA * horizontal * (1f - y)
}

internal fun cinemaScrimBandMidpoint(index: Int): Float =
    (index.coerceIn(0, CINEMA_SCRIM_FADE_STEPS - 1) + .5f) / CINEMA_SCRIM_FADE_STEPS
