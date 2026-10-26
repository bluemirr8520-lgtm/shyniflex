package io.mirr.shynigsy.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaItem

/** These foregrounds belong to the dark artwork scrim, independently of the user's page color. */
private val HeroIvory = Color(0xFFF4E8D1)
private val HeroChampagne = Color(0xFFD8AC68)

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
    val progress = continuePlaybackProgress(hero.durationMs, source.resumeMs)
    var resumeFocused by remember { mutableStateOf(false) }
    var detailsFocused by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.testTag("cinema-home-hero")) {
        val fontScale = LocalDensity.current.fontScale
        val condensed = maxHeight / fontScale < (if (compact) 290.dp else 330.dp)
        val veryShort = maxHeight / fontScale < 220.dp
        val widthFraction = if (compact) 1f else .70f
        val sidePadding = if (compact) 22.dp else 32.dp
        val stackActions = (maxWidth * widthFraction - sidePadding - 22.dp) / fontScale < 300.dp
        Column(
            Modifier.fillMaxWidth(widthFraction).fillMaxHeight()
                .padding(start = sidePadding, end = 22.dp,
                    top = if (condensed) 16.dp else 28.dp, bottom = if (condensed) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(
                if (condensed) 5.dp else if (compact) 9.dp else 11.dp, Alignment.Bottom),
        ) {
            Text("오늘 밤의 극장", color = HeroIvory.copy(alpha = .92f),
                style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hero.showTitle ?: hero.title, color = HeroIvory,
                fontFamily = FontFamily.Serif,
                fontSize = if (condensed) { if (compact) 28.sp else 36.sp } else { if (compact) 32.sp else 44.sp },
                lineHeight = if (condensed) { if (compact) 36.sp else 44.sp } else { if (compact) 40.sp else 52.sp },
                fontWeight = FontWeight.Medium, maxLines = if (condensed) 1 else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-hero-title"))
            Text(continueHeroMetadata(hero), color = HeroIvory.copy(alpha = .86f),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("cinema-hero-meta"))
            if (!veryShort && hero.summary.isNotBlank()) Text(hero.summary,
                color = HeroIvory.copy(alpha = .90f), style = MaterialTheme.typography.bodyMedium,
                maxLines = if (condensed) 1 else if (compact) 2 else 3,
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    resumeAction()
                    detailsAction()
                }
            } else Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp)) {
                resumeAction()
                detailsAction()
            }
            if (progress != null && !veryShort) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { progress.fraction }, color = HeroChampagne,
                    trackColor = HeroIvory.copy(alpha = .24f),
                    modifier = Modifier.width(64.dp).height(2.dp).testTag("cinema-hero-progress"))
                Text("${progress.remainingMinutes}분 남음", color = HeroIvory.copy(alpha = .86f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-hero-remaining"))
            }
        }
    }
}

/** No filled menu rectangle: a strong underline identifies the remote's actual focus. */
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
    val foreground = if (accent || focused || pressed) HeroChampagne else HeroIvory.copy(alpha = .88f)
    TextButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = RectangleShape, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent,
            contentColor = foreground, disabledContainerColor = Color.Transparent,
            disabledContentColor = HeroIvory.copy(alpha = .38f)),
        modifier = modifier.heightIn(min = 48.dp).onFocusChanged { onFocus(it.isFocused) }
            .drawBehind {
                if (focused || pressed) drawLine(HeroChampagne,
                    Offset(6.dp.toPx(), size.height - 2.dp.toPx()),
                    Offset(size.width - 6.dp.toPx(), size.height - 2.dp.toPx()),
                    strokeWidth = 3.dp.toPx())
            }) {
        if (accent) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(27.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
