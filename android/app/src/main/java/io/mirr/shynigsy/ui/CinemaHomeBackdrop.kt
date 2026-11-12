package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.AppViewModel
import io.mirr.shynigsy.R
import io.mirr.shynigsy.data.MediaItem
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/** Bundled original art is immediately available, including when the server has no resume rows. */
@Composable
internal fun CinemaHomeBackdrop(
    hero: MediaItem?,
    model: AppViewModel,
    heroHeight: Dp,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = LocalCinemaStyle.current
    val page = MaterialTheme.colorScheme.background
    Box(modifier.testTag("tv-continue-backdrop")) {
        // Local decoding uses the same bounded image loader, keeping it off the UI thread.
        AsyncImage(model = R.drawable.cinema_paradiso_backdrop, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd)
        if (hero != null && !(hero.backdrop ?: hero.poster).isNullOrBlank()) {
            // Keep account-scoped artwork headers/cache rules; no extra request or prefetch path.
            key(hero.libraryId, hero.id, hero.type, hero.isSpoiler, hero.backdrop ?: hero.poster) {
                Poster(hero, model, Modifier.fillMaxSize()
                    .testTag("cinema-hero-art-${hero.libraryId}-${hero.id}"), transparentBackground = true,
                    imageUrl = hero.backdrop ?: hero.poster, imageAlignment = Alignment.CenterEnd)
            }
        }
        if (style == CinemaStyle.LOUNGE) {
            // The lounge uses the user's page color behind text, including dark custom palettes.
            // No cached bitmap tint or offscreen blur is needed when switching styles.
            Box(Modifier.fillMaxSize().loungeHomeScrim(heroHeight, page, wide))
            if (wide) Box(Modifier.fillMaxWidth().height(80.dp).background(Brush.verticalGradient(
                0f to page, .7f to page, 1f to Color.Transparent)))
        } else {
        // Raise the artwork's transmission from 62% to 93% (1.5x), while
        // retaining the separate fades that protect the title and page color.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (hero != null) .07f else .38f)))
        Box(Modifier.fillMaxSize().continueSectionBackdropFade(
            heroHeight, MaterialTheme.colorScheme.background, wide, cinematic = hero != null,
            brightenArtwork = hero != null))
        // This is after the page-color fade: even a white custom page cannot wash out gold actions.
        if (hero != null) {
            // Fade the extra title protection through the hero's lower edge so
            // a light page color cannot reveal an abrupt horizontal seam.
            Box(Modifier.fillMaxWidth().height(heroHeight + 32.dp).cinemaHeroTextScrim(heroHeight, wide))
        } else {
            Box(Modifier.fillMaxWidth().height(heroHeight).background(if (wide) Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = .35f), .65f to Color.Black.copy(alpha = .35f),
                1f to Color.Transparent,
            ) else Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .35f)))))
        }
        // The header stays readable even over a very bright still, regardless of the UI color.
        Box(Modifier.fillMaxWidth().height(128.dp).background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .72f), Color.Transparent))))
        }
    }
}

/** A quiet paper-like title area fades into the original artwork and the poster shelf. */
private fun Modifier.loungeHomeScrim(heroHeight: Dp, background: Color, wide: Boolean): Modifier = drawWithCache {
    val hero = heroHeight.toPx().coerceAtMost(size.height).coerceAtLeast(1f)
    val horizontal = if (wide) Brush.horizontalGradient(
        0f to background, .62f to background, .82f to background.copy(alpha = .32f), 1f to Color.Transparent,
    ) else Brush.verticalGradient(
        // The title's own paper surface follows its measured text height. Only protect the header here.
        0f to background, (56.dp.toPx() / hero).coerceAtMost(.8f) to background,
        (80.dp.toPx() / hero).coerceAtMost(.9f) to Color.Transparent, 1f to Color.Transparent, endY = hero,
    )
    val bottom = Brush.verticalGradient(
        0f to Color.Transparent,
        ((hero - 48.dp.toPx()).coerceAtLeast(0f) / size.height.coerceAtLeast(1f)) to Color.Transparent,
        ((hero + 12.dp.toPx()).coerceAtMost(size.height) / size.height.coerceAtLeast(1f)) to background,
        1f to background,
    )
    onDrawBehind {
        drawRect(horizontal)
        drawRect(bottom)
    }
}

private fun Modifier.cinemaHeroTextScrim(heroHeight: Dp, wide: Boolean): Modifier = drawWithCache {
    val fadeStart = (heroHeight.toPx() - 32.dp.toPx()).roundToInt().toFloat().coerceIn(0f, size.height)
    val fadeHeight = (size.height - fadeStart).coerceAtLeast(0f)
    val topBrush = cinemaScrimHorizontalBrush(CINEMA_SCRIM_MAX_ALPHA)
    // Small horizontal bands approximate the product of the two fades without
    // allocating a screen-sized offscreen layer on the TV GPU.
    val bandBrushes = if (wide) List(CINEMA_SCRIM_FADE_STEPS) { index ->
        cinemaScrimHorizontalBrush(cinemaScrimAlphaAt(0f, cinemaScrimBandMidpoint(index), wide = true))
    } else emptyList()
    val mobileBrush = if (!wide) Brush.verticalGradient(
        0f to Color.Black.copy(alpha = CINEMA_SCRIM_MAX_ALPHA),
        (fadeStart / size.height.coerceAtLeast(1f)) to Color.Black.copy(alpha = CINEMA_SCRIM_MAX_ALPHA),
        1f to Color.Transparent,
    ) else null
    onDrawBehind {
        if (!wide) {
            drawRect(requireNotNull(mobileBrush))
        } else {
            if (fadeStart > 0f) drawRect(topBrush, size = Size(size.width, fadeStart))
            if (fadeHeight > 0f) bandBrushes.forEachIndexed { index, brush ->
                // Align shared band edges to physical pixels to avoid translucent seams.
                val top = (fadeStart + fadeHeight * index / CINEMA_SCRIM_FADE_STEPS).roundToInt().toFloat()
                val bottom = (fadeStart + fadeHeight * (index + 1) / CINEMA_SCRIM_FADE_STEPS).roundToInt().toFloat()
                drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
            }
        }
    }
}

private fun cinemaScrimHorizontalBrush(alpha: Float): Brush = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = alpha), .65f to Color.Black.copy(alpha = alpha),
    1f to Color.Transparent,
)

/** An empty history still gets a real home; illustrative art never invents a playable item. */
@Composable
internal fun CinemaHomeWelcome(compact: Boolean, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val style = LocalCinemaStyle.current
    val ivory = cinemaHeroForeground
    val gold = cinemaHeroAccent
    BoxWithConstraints(modifier.testTag("cinema-paradiso-welcome")) {
        val condensed = maxHeight / LocalDensity.current.fontScale < 240.dp
        val lounge = style == CinemaStyle.LOUNGE
        val sidePadding = if (compact) 22.dp else 32.dp
        Column(Modifier.fillMaxWidth(if (compact) 1f else if (lounge) .62f else .72f)
            .align(if (lounge && compact) Alignment.BottomStart else Alignment.CenterStart)
            .padding(horizontal = sidePadding, vertical = 16.dp)
            .then(if (lounge && compact) Modifier.loungeHeroTextSurface(MaterialTheme.colorScheme.background,
                sidePadding, sidePadding, 16.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(if (condensed) 6.dp else 12.dp)) {
            Text(if (style == CinemaStyle.LOUNGE) "당신을 위한 한 편" else "오늘 밤의 극장", color = gold, style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.sp)
            Text("당신의 작은 영화관", color = ivory,
                fontFamily = if (style == CinemaStyle.OTT) FontFamily.SansSerif else FontFamily.Serif,
                fontSize = if (compact) 30.sp else 38.sp, lineHeight = if (compact) 38.sp else 46.sp,
                maxLines = if (condensed) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!condensed) Text("영화가 끝나도, 이야기는 계속됩니다.", color = ivory,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp).tvOnlyFocus().testTag("cinema-welcome-search"),
                colors = ButtonDefaults.textButtonColors(contentColor = gold)) {
                Text("작품 찾기", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
