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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

internal const val DEFAULT_CINEMA_BACKGROUND = 0xFF0B0C10.toInt()

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

internal fun cinemaColorScheme(background: Color): ColorScheme {
    val base = background.copy(alpha = 1f)
    val foreground = foregroundOn(base)
    val dark = foreground == Color.White
    fun safeSurface(amount: Float): Color = lerp(base, foreground, amount).let {
        if (cinemaContrastRatio(foreground, it) >= 4.5f) it else base
    }
    val surface = safeSurface(.025f)
    val panel = safeSurface(.05f)
    fun acrossSurfaces(preferred: Color): Color = if (listOf(base, surface, panel).all {
        cinemaContrastRatio(preferred, it) >= 4.5f
    }) preferred else foreground
    val primary = acrossSurfaces(if (dark) Color(0xFFC5A771) else Color(0xFF765019))
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

/** Only a solid UI color is stored here. Account data and playback/subtitle settings are independent. */
private class CinemaAppearancePreferences(context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("cinema_appearance", Context.MODE_PRIVATE)
    fun read(): Int = runCatching { preferences.getInt("background_argb", DEFAULT_CINEMA_BACKGROUND) }
        .getOrDefault(DEFAULT_CINEMA_BACKGROUND) or 0xFF000000.toInt()
    fun save(argb: Int) { preferences.edit().putInt("background_argb", argb or 0xFF000000.toInt()).apply() }
}

@Composable
internal fun CinemaAppearanceTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { CinemaAppearancePreferences(context) }
    var background by remember(preferences) { mutableIntStateOf(preferences.read()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "background_argb") background = preferences.read()
        }
        preferences.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val colors = remember(background) { cinemaColorScheme(Color(background)) }
    val controller = CinemaAppearanceController(background) { color ->
        background = color or 0xFF000000.toInt()
        preferences.save(background)
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
    CompositionLocalProvider(LocalCinemaAppearance provides controller) {
        MaterialTheme(colorScheme = colors, typography = CompactTypography, shapes = CompactShapes, content = content)
    }
}

private tailrec fun Context.appearanceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.appearanceActivity() else null
    else -> null
}
