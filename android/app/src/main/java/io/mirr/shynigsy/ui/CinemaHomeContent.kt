package io.mirr.shynigsy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaItem

/** These foregrounds belong to the dark artwork scrim, independently of the user's page color. */
internal val cinemaHeroForeground: Color
    @Composable get() = when (LocalCinemaStyle.current) {
        CinemaStyle.CINEMA -> Color(0xFFF4E8D1)
        CinemaStyle.OTT -> Color.White
        CinemaStyle.LOUNGE -> MaterialTheme.colorScheme.onBackground
    }

internal val cinemaHeroAccent: Color
    @Composable get() = when (LocalCinemaStyle.current) {
        CinemaStyle.CINEMA -> Color(0xFFE5C28A)
        CinemaStyle.OTT -> Color(0xFF79DFC5)
        CinemaStyle.LOUNGE -> MaterialTheme.colorScheme.primary
    }

/** Descriptive enrichment cannot replace the exact row item or its current resume position. */
@Composable
internal fun CinemaHomeHeroContent(
    hero: MediaItem,
    source: MediaItem,
    compact: Boolean,
    busy: Boolean,
    onResume: (MediaItem) -> Unit,
    onResumeFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDetails: ((MediaItem) -> Unit)? = null,
) {
    val style = LocalCinemaStyle.current
    val foreground = cinemaHeroForeground
    val muted = if (style == CinemaStyle.LOUNGE) foreground else foreground.copy(alpha = .88f)
    val progress = continuePlaybackProgress(hero.durationMs, source.resumeMs)
    var resumeFocused by remember { mutableStateOf(false) }
    var detailsFocused by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.testTag("cinema-home-hero")) {
        val fontScale = LocalDensity.current.fontScale
        val condensed = maxHeight / fontScale < (if (compact) 290.dp else 330.dp)
        val veryShort = maxHeight / fontScale < 220.dp
        val widthFraction = if (compact) 1f else if (style == CinemaStyle.LOUNGE) .62f else .70f
        val sidePadding = if (compact) 24.dp else if (style == CinemaStyle.LOUNGE) 40.dp else 32.dp
        val stackActions = (maxWidth * widthFraction - sidePadding - 22.dp) / fontScale < 280.dp
        val loungeMobile = style == CinemaStyle.LOUNGE && compact
        val bottomPadding = if (condensed) 16.dp else 28.dp
        val titleSize = when (style) {
            CinemaStyle.CINEMA -> if (condensed) { if (compact) 28.sp else 36.sp } else { if (compact) 32.sp else 44.sp }
            CinemaStyle.OTT -> if (condensed) { if (compact) 30.sp else 38.sp } else { if (compact) 34.sp else 46.sp }
            CinemaStyle.LOUNGE -> if (condensed) { if (compact) 27.sp else 34.sp } else { if (compact) 31.sp else 42.sp }
        }
        Column(
            Modifier.fillMaxWidth(widthFraction).fillMaxHeight()
                .padding(start = sidePadding, end = 22.dp,
                    top = if (condensed) 12.dp else 28.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.Bottom,
        ) {
          Column(Modifier.fillMaxWidth().then(if (loungeMobile)
              Modifier.loungeHeroTextSurface(MaterialTheme.colorScheme.background, sidePadding, 22.dp, bottomPadding)
              else Modifier), verticalArrangement = Arrangement.spacedBy(
                  if (condensed) 6.dp else if (style == CinemaStyle.LOUNGE && !compact) 14.dp else 10.dp)) {
            if (style != CinemaStyle.OTT && !loungeMobile && !(style == CinemaStyle.LOUNGE && condensed))
                Text(if (style == CinemaStyle.LOUNGE) "당신을 위한 한 편" else "오늘 밤의 극장", color = muted,
                style = MaterialTheme.typography.labelLarge, letterSpacing = if (style == CinemaStyle.LOUNGE) 2.sp else 1.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hero.showTitle ?: hero.title, color = foreground,
                fontFamily = if (style == CinemaStyle.OTT) FontFamily.SansSerif else FontFamily.Serif,
                fontSize = titleSize, lineHeight = titleSize * 1.2f,
                fontWeight = when (style) { CinemaStyle.OTT -> FontWeight.Bold; CinemaStyle.LOUNGE -> FontWeight.Normal; else -> FontWeight.Medium },
                maxLines = if (condensed) 1 else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-hero-title"))
            Text(continueHeroMetadata(hero), color = muted,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("cinema-hero-meta"))
            if (!veryShort && !loungeMobile && hero.summary.isNotBlank()) Text(hero.summary,
                color = muted, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (condensed || style == CinemaStyle.LOUNGE) 1 else if (compact || style == CinemaStyle.OTT) 2 else 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-hero-summary"))

            val resumeAction: @Composable () -> Unit = {
                CinemaHeroAction(label = "이어서 재생", accent = true, enabled = !busy,
                    onClick = { onResume(source) }, onFocus = {
                        resumeFocused = it
                        onResumeFocus(resumeFocused || detailsFocused)
                    }, modifier = Modifier.testTag("cinema-hero-resume").semantics {
                        contentDescription = "${source.showTitle ?: source.title}, 이어보기"
                    })
            }
            val detailsAction: @Composable () -> Unit = {
                onDetails?.let { open ->
                    CinemaHeroAction(label = "작품 정보", accent = false, enabled = !busy,
                        onClick = { open(source) }, onFocus = {
                            detailsFocused = it
                            onResumeFocus(resumeFocused || detailsFocused)
                        }, modifier = Modifier.testTag("cinema-hero-details"))
                }
            }
            if (stackActions && onDetails != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    resumeAction()
                    detailsAction()
                }
            } else Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                resumeAction()
                detailsAction()
            }
            if (progress != null && !veryShort) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { progress.fraction }, color = cinemaHeroAccent,
                    trackColor = foreground.copy(alpha = .24f),
                    modifier = Modifier.width(if (style == CinemaStyle.OTT) 80.dp else 64.dp).height(2.dp).testTag("cinema-hero-progress"))
                Text("${progress.remainingMinutes}분 남음", color = muted,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-hero-remaining"))
            }
          }
        }
    }
}

/** Its height follows real text wrapping; the image above remains vivid on every phone size. */
internal fun Modifier.loungeHeroTextSurface(background: Color, start: Dp, end: Dp, bottom: Dp): Modifier = drawWithCache {
    val left = -start.toPx()
    val width = size.width + start.toPx() + end.toPx()
    val fade = 48.dp.toPx()
    val brush = Brush.verticalGradient(listOf(Color.Transparent, background), startY = -fade, endY = 0f)
    onDrawBehind {
        drawRect(brush, topLeft = Offset(left, -fade), size = Size(width, fade))
        drawRect(background, topLeft = Offset(left, 0f), size = Size(width, size.height + bottom.toPx()))
    }
}

/** One pill marks the primary action; remote focus adds a single clear outline. */
@Composable
private fun CinemaHeroAction(
    label: String,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val foreground = if (accent) colors.onPrimary else cinemaHeroForeground
    Button(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = if (accent) 18.dp else 14.dp, vertical = 8.dp),
        border = BorderStroke(if (focused || pressed) 2.dp else 1.dp,
            if (focused || pressed) cinemaHeroForeground else if (accent) colors.primary else cinemaHeroForeground.copy(alpha = .38f)),
        colors = ButtonDefaults.buttonColors(containerColor = if (accent) colors.primary else Color.Transparent,
            contentColor = foreground, disabledContainerColor = if (accent) colors.primary.copy(alpha = .35f) else Color.Transparent,
            disabledContentColor = foreground.copy(alpha = .45f)),
        modifier = modifier.heightIn(min = 48.dp).onFocusChanged { onFocus(it.isFocused) }) {
        if (accent) {
            CinemaIcon(CinemaGlyph.Play, null, Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
