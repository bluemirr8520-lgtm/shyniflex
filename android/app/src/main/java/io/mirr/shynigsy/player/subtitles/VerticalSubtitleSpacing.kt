package io.mirr.shynigsy.player.subtitles

internal fun normalizedVerticalSubtitleSpacingPercent(value: Int): Int = value.coerceIn(50, 200)

/** Keep drawing, wrapping and drag bounds on the same scaled vertical cell advance. */
internal fun verticalSubtitleGlyphAdvancePx(baseAdvancePx: Float, spacingPercent: Int): Float =
    baseAdvancePx * (normalizedVerticalSubtitleSpacingPercent(spacingPercent) / 100f)
