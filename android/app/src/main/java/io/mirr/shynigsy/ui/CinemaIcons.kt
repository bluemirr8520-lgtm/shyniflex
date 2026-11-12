package io.mirr.shynigsy.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A single 24 dp optical grid, with open interiors and round caps throughout the app. */
internal enum class CinemaGlyph {
    Home, Search, Library, More, Palette, Settings,
    Play, Pause, Next, Previous, Subtitles, Rewind, FastForward, Fit, Fill, Zoom,
    Movie, Refresh, Update, Account, Lock, Unlock, ArrowBack, QrCode, Check,
}

/** Style changes line weight, never the action's position, hit target or semantics. */
internal fun cinemaIconStrokeWidth(style: CinemaStyle): Float = when (style) {
    CinemaStyle.CINEMA -> 1.8f
    CinemaStyle.OTT -> 2f
    CinemaStyle.LOUNGE -> 1.65f
}

@Composable
internal fun CinemaIcon(
    glyph: CinemaGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(cinemaIcon(glyph), contentDescription, modifier, tint = tint)
}

/** Use this adapter for existing menu APIs that accept an ImageVector. Tint stays caller-owned. */
@Composable
internal fun cinemaIcon(glyph: CinemaGlyph): ImageVector {
    val style = LocalCinemaStyle.current
    return remember(glyph, style) { cinemaIconVector(glyph, style) }
}

internal fun cinemaIconVector(glyph: CinemaGlyph, style: CinemaStyle): ImageVector =
    ImageVector.Builder(
        name = "Cinema.${style.name}.${glyph.name}",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(cinemaIconPath(glyph, style)).toNodes(),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = cinemaIconStrokeWidth(style),
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/** Paths are also used by the native player vectors; no font or external icon pack is required. */
private fun cinemaIconPath(glyph: CinemaGlyph, style: CinemaStyle): String = when (glyph) {
    CinemaGlyph.Home ->
        "M3.5,10L12,3L20.5,10V19.5Q20.5,21 19,21H15V14H9V21H5Q3.5,21 3.5,19.5Z"
    CinemaGlyph.Search ->
        "M10.5,3.5A7,7 0,1 1,10.5 17.5A7,7 0,1 1,10.5 3.5M15.5,15.5L21,21"
    CinemaGlyph.Library -> if (style == CinemaStyle.CINEMA)
        "M6,3.5H18M5,7H19Q21,7 21,9V19Q21,21 19,21H5Q3,21 3,19V9Q3,7 5,7ZM10,11L15,14L10,17Z"
    else
        "M3,4H7V20H3ZM7,4H11V20H7ZM13,4L17,3.5L20.5,19.5L16.5,20Z"
    CinemaGlyph.More -> "M5,12H5.05M12,12H12.05M19,12H19.05"
    CinemaGlyph.Palette ->
        "M12,3A9,9 0,1 0,12 21H13Q15,21 15,19Q15,18 14,17Q13,15 15,14H17Q21,14 21,11Q21,3 12,3Z" +
            "M7.5,10H7.55M10,6.5H10.05M15,6.5H15.05M17.5,10H17.55"
    CinemaGlyph.Settings ->
        "M10,2.5H14L14.7,5.3L17,6.6L19.8,5.8L21.8,9.2L19.7,11.2V12.8L21.8,14.8L19.8,18.2L17,17.4L14.7,18.7L14,21.5H10L9.3,18.7L7,17.4L4.2,18.2L2.2,14.8L4.3,12.8V11.2L2.2,9.2L4.2,5.8L7,6.6L9.3,5.3Z" +
            "M15.5,12A3.5,3.5 0,1 1,8.5 12A3.5,3.5 0,1 1,15.5 12Z"
    CinemaGlyph.Play -> "M7,4.5L19,12L7,19.5Z"
    CinemaGlyph.Pause -> "M8,5V19M16,5V19"
    CinemaGlyph.Next -> "M5,5L15,12L5,19ZM19,5V19"
    CinemaGlyph.Previous -> "M19,5L9,12L19,19ZM5,5V19"
    CinemaGlyph.Subtitles -> if (style == CinemaStyle.LOUNGE)
        "M5,4H19Q21,4 21,6V16Q21,18 19,18H15L12,21L9,18H5Q3,18 3,16V6Q3,4 5,4ZM7,9H17M7,13H17"
    else
        "M5,4.5H19Q21,4.5 21,6.5V17.5Q21,19.5 19,19.5H5Q3,19.5 3,17.5V6.5Q3,4.5 5,4.5ZM7,10H17M7,14H13M16,14H17"
    CinemaGlyph.Rewind -> "M11,5L3,12L11,19ZM21,5L13,12L21,19Z"
    CinemaGlyph.FastForward -> "M3,5L11,12L3,19ZM13,5L21,12L13,19Z"
    CinemaGlyph.Fit ->
        "M5,4H19Q21,4 21,6V18Q21,20 19,20H5Q3,20 3,18V6Q3,4 5,4ZM7.5,8H16.5Q18,8 18,9.5V14.5Q18,16 16.5,16H7.5Q6,16 6,14.5V9.5Q6,8 7.5,8Z"
    CinemaGlyph.Fill ->
        "M5,4H19Q21,4 21,6V18Q21,20 19,20H5Q3,20 3,18V6Q3,4 5,4ZM4,12H20M7,9L4,12L7,15M17,9L20,12L17,15"
    CinemaGlyph.Zoom ->
        "M8,3H3V8M3,3L9,9M16,3H21V8M21,3L15,9M3,16V21H8M3,21L9,15M21,16V21H16M21,21L15,15"
    CinemaGlyph.Movie ->
        "M5,3H19Q21,3 21,5V19Q21,21 19,21H5Q3,21 3,19V5Q3,3 5,3ZM7,3V21M17,3V21M3,8H7M3,16H7M17,8H21M17,16H21M7,12H17"
    CinemaGlyph.Refresh ->
        "M20,10A8,8 0,0 0,6.3 6.3L3,9M3,4V9H8M4,14A8,8 0,0 0,17.7 17.7L21,15M16,15H21V20"
    CinemaGlyph.Update ->
        "M5,8V19Q5,21 7,21H17Q19,21 19,19V8M12,3V15M8,11L12,15L16,11"
    CinemaGlyph.Account ->
        "M21,12A9,9 0,1 1,3 12A9,9 0,1 1,21 12ZM15,9A3,3 0,1 1,9 9A3,3 0,1 1,15 9ZM6,18.5Q7.5,14.5 12,14.5Q16.5,14.5 18,18.5"
    CinemaGlyph.Lock ->
        "M7,10V7A5,5 0,0 1,17 7V10M6,10H18Q20,10 20,12V19Q20,21 18,21H6Q4,21 4,19V12Q4,10 6,10ZM12,14V17"
    CinemaGlyph.Unlock ->
        "M7,10V7A5,5 0,0 1,16.5 4.8M6,10H18Q20,10 20,12V19Q20,21 18,21H6Q4,21 4,19V12Q4,10 6,10ZM12,14V17"
    CinemaGlyph.ArrowBack -> "M20,12H4M10,6L4,12L10,18"
    CinemaGlyph.QrCode ->
        "M3,7V3H7M17,3H21V7M21,17V21H17M7,21H3V17M7,7H10V10H7ZM14,7H17V10H14ZM7,14H10V17H7ZM14,14H17V17H14Z"
    CinemaGlyph.Check -> "M5,12L10,17L20,7"
}
