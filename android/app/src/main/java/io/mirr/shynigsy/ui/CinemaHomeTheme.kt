package io.mirr.shynigsy.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

internal const val DEFAULT_CINEMA_BACKGROUND = 0xFF211914.toInt()

internal fun parseHomeBackgroundColor(hex: String): Int? {
    val digits = hex.trim().removePrefix("#")
    if (!Regex("[0-9a-fA-F]{6}").matches(digits)) return null
    return (0xFF000000L or digits.toLong(16)).toInt()
}

internal fun cinemaContrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + .05f) /
        (minOf(firstLuminance, secondLuminance) + .05f)
}

private fun foregroundOn(background: Color): Color =
    if (cinemaContrastRatio(Color.White, background) >= cinemaContrastRatio(Color.Black, background))
        Color.White else Color.Black

/** An accent may become ordinary black/white when that is needed to keep custom colors readable. */
internal fun cinemaReadableColor(preferred: Color, background: Color): Color =
    if (cinemaContrastRatio(preferred, background) >= 4.5f) preferred else foregroundOn(background)

internal fun cinemaColorScheme(background: Color, style: CinemaStyle = CinemaStyle.CINEMA): ColorScheme {
    val base = background.copy(alpha = 1f)
    val contrastForeground = foregroundOn(base)
    val dark = contrastForeground == Color.White
    val ivory = Color(0xFFF4E8D1)
    // Preserve the user's hue; soften dark themes to ivory only where contrast allows it.
    val foreground = if (style == CinemaStyle.CINEMA && dark && cinemaContrastRatio(ivory, base) >= 4.5f)
        ivory else contrastForeground
    fun safeSurface(amount: Float): Color = lerp(base, foreground, amount).let {
        if (cinemaContrastRatio(foreground, it) >= 4.5f) it else base
    }
    val surface = safeSurface(.025f)
    val panel = safeSurface(.05f)
    fun acrossSurfaces(preferred: Color): Color = if (listOf(base, surface, panel).all {
        cinemaContrastRatio(preferred, it) >= 4.5f
    }) preferred else foreground
    val preferredAccent = when (style) {
        CinemaStyle.CINEMA -> if (dark) Color(0xFFD8AC68) else Color(0xFF765019)
        CinemaStyle.OTT -> Color(0xFF78DCC3)
        CinemaStyle.LOUNGE -> Color(0xFFAB8A54)
    }
    // Darken/lighten the style accent towards the readable foreground while retaining its hue.
    val primary = (0..100).asSequence().map { lerp(preferredAccent, foreground, it / 100f) }
        .first { accent -> listOf(base, surface, panel).all { cinemaContrastRatio(accent, it) >= 4.5f } }
    val primaryContainer = lerp(base, primary, .14f)
    val mutedCandidate = lerp(foreground, base, .22f)
    val muted = if (cinemaContrastRatio(mutedCandidate, base) >= 4.5f &&
        cinemaContrastRatio(mutedCandidate, panel) >= 4.5f) mutedCandidate else foreground
    val error = acrossSurfaces(if (dark) Color(0xFFFFB4AB) else Color(0xFF9C202C))
    val errorContainer = lerp(base, error, .16f)
    val defaults = if (dark) darkColorScheme() else lightColorScheme()
    return defaults.copy(
        primary = primary, onPrimary = foregroundOn(primary),
        primaryContainer = primaryContainer, onPrimaryContainer = foregroundOn(primaryContainer),
        secondary = primary, onSecondary = foregroundOn(primary),
        secondaryContainer = primaryContainer, onSecondaryContainer = foregroundOn(primaryContainer),
        tertiary = primary, onTertiary = foregroundOn(primary),
        tertiaryContainer = primaryContainer, onTertiaryContainer = foregroundOn(primaryContainer),
        background = base, onBackground = foreground,
        surface = surface, onSurface = foreground,
        surfaceVariant = panel, onSurfaceVariant = muted,
        surfaceContainer = panel, surfaceContainerLow = surface,
        surfaceContainerLowest = base, surfaceContainerHigh = panel, surfaceContainerHighest = panel,
        surfaceBright = panel, surfaceDim = base,
        outline = lerp(base, foreground, .55f), outlineVariant = lerp(base, foreground, .20f),
        inverseSurface = foreground, inverseOnSurface = base,
        inversePrimary = foregroundOn(foreground), surfaceTint = primary,
        error = error, onError = foregroundOn(error), errorContainer = errorContainer,
        onErrorContainer = foregroundOn(errorContainer),
    )
}

internal val CinemaHomeColors = cinemaColorScheme(Color(DEFAULT_CINEMA_BACKGROUND))

internal data class CinemaAppearanceController(val backgroundArgb: Int, val save: (Int) -> Unit)

internal val LocalCinemaAppearance = staticCompositionLocalOf {
    CinemaAppearanceController(DEFAULT_CINEMA_BACKGROUND) { }
}

@Composable
internal fun CinemaAppearanceTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { CinemaAppearancePreferences(context) }
    var appearance by remember(preferences) { mutableStateOf(preferences.read()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (preferences.ownsKey(key)) appearance = preferences.read()
        }
        preferences.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val colors = remember(appearance) { cinemaColorScheme(Color(appearance.backgroundArgb), appearance.style) }
    val controller = CinemaAppearanceController(appearance.backgroundArgb) { color ->
        preferences.saveCurrentBackground(color)
        appearance = preferences.read()
    }
    val styleController = CinemaStyleController(appearance.style, appearance.backgrounds) { style, backgrounds ->
        preferences.apply(style, backgrounds)
        appearance = preferences.read()
    }
    val activity = remember(context) { context.appearanceActivity() }
    val darkIcons = colors.onBackground == Color.Black
    // IME edits may recompose the dialog repeatedly; the window chrome only changes with its contrast.
    DisposableEffect(activity, darkIcons) {
        activity?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
        onDispose { }
    }
    CompositionLocalProvider(LocalCinemaAppearance provides controller, LocalCinemaStyle provides appearance.style,
        LocalCinemaStyleController provides styleController) {
        MaterialTheme(colorScheme = colors, typography = CompactTypography, shapes = CompactShapes, content = content)
    }
}

private tailrec fun Context.appearanceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.appearanceActivity() else null
    else -> null
}
