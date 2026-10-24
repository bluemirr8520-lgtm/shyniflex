package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaItem

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
) {
    val progress = continuePlaybackProgress(hero.durationMs, source.resumeMs)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.testTag("cinema-home-hero")) {
        val condensed = maxHeight / LocalDensity.current.fontScale < 280.dp
        Column(Modifier.fillMaxWidth(if (compact) 1f else .66f).fillMaxHeight()
            .padding(start = if (compact) 24.dp else 32.dp, end = 24.dp,
                top = if (condensed) 24.dp else 32.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (condensed) 6.dp else if (compact) 9.dp else 11.dp, Alignment.CenterVertically)) {
            Text("이어서 보는 작품", color = Color.White.copy(alpha = .94f),
                style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
            Text(hero.showTitle ?: hero.title, color = Color.White,
                fontSize = if (compact) 26.sp else 34.sp,
                lineHeight = if (compact) 32.sp else 40.sp,
                fontWeight = FontWeight.Bold, maxLines = if (condensed) 1 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("cinema-hero-title"))
            Text(continueHeroMetadata(hero), color = Color.White.copy(alpha = .94f),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("cinema-hero-meta"))
            if (hero.summary.isNotBlank()) Text(hero.summary, color = Color.White.copy(alpha = .94f),
                style = MaterialTheme.typography.bodyMedium, maxLines = if (compact || condensed) 2 else 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-hero-summary"))
            Button(onClick = { onResume(source) }, enabled = !busy, shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
                modifier = Modifier.heightIn(min = 48.dp).onFocusChanged { onResumeFocus(it.isFocused) }
                    .testTag("cinema-hero-resume").semantics {
                        contentDescription = "${source.showTitle ?: source.title}, 이어보기"
                    }) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("이어보기", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress != null) {
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LinearProgressIndicator(progress = { progress.fraction }, color = colors.onPrimary,
                            trackColor = colors.onPrimary.copy(alpha = .22f),
                            modifier = Modifier.width(68.dp).height(3.dp).testTag("cinema-hero-progress"))
                        Text("${progress.remainingMinutes}분 남음", style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("cinema-hero-remaining"))
                    }
                }
            }
        }
    }
}
