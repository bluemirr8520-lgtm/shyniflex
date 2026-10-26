package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Bundled original art is immediately available, including when the server has no resume rows. */
@Composable
internal fun CinemaHomeBackdrop(
    hero: MediaItem?,
    model: AppViewModel,
    heroHeight: Dp,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("tv-continue-backdrop")) {
        // Local decoding uses the same bounded image loader, keeping it off the UI thread.
        AsyncImage(model = R.drawable.cinema_paradiso_backdrop, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd)
        if (hero != null && !(hero.backdrop ?: hero.poster).isNullOrBlank()) {
            // Keep account-scoped artwork headers/cache rules; no extra request or prefetch path.
            key(hero.libraryId, hero.id, hero.type, hero.isSpoiler, hero.backdrop ?: hero.poster) {
                Poster(hero, model, Modifier.fillMaxSize(), transparentBackground = true,
                    imageUrl = hero.backdrop ?: hero.poster, imageAlignment = Alignment.CenterEnd)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
        Box(Modifier.fillMaxSize().continueSectionBackdropFade(
            heroHeight, MaterialTheme.colorScheme.background, wide))
        // This is after the page-color fade: even a white custom page cannot wash out gold actions.
        Box(Modifier.fillMaxWidth().height(heroHeight).background(if (wide) Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = .35f), .65f to Color.Black.copy(alpha = .35f),
            1f to Color.Transparent,
        ) else Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .35f)))))
        // The header stays readable even over a very bright still, regardless of the UI color.
        Box(Modifier.fillMaxWidth().height(128.dp).background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .72f), Color.Transparent))))
    }
}

/** An empty history still gets a real home; illustrative art never invents a playable item. */
@Composable
internal fun CinemaHomeWelcome(compact: Boolean, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val ivory = Color(0xFFF4E8D1)
    val gold = Color(0xFFD8AC68)
    BoxWithConstraints(modifier.testTag("cinema-paradiso-welcome")) {
        val condensed = maxHeight / LocalDensity.current.fontScale < 240.dp
        Column(Modifier.fillMaxWidth(if (compact) 1f else .72f)
            .align(Alignment.CenterStart).padding(horizontal = if (compact) 22.dp else 32.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (condensed) 6.dp else 12.dp)) {
            Text("오늘 밤의 극장", color = gold, style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.sp)
            Text("당신의 작은 영화관", color = ivory, fontFamily = FontFamily.Serif,
                fontSize = if (compact) 30.sp else 38.sp, lineHeight = if (compact) 38.sp else 46.sp,
                maxLines = if (condensed) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!condensed) Text("영화가 끝나도, 이야기는 계속됩니다.", color = ivory,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onSearch, modifier = Modifier.tvOnlyFocus().testTag("cinema-welcome-search"),
                colors = ButtonDefaults.textButtonColors(contentColor = gold)) {
                Text("작품 찾기", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
