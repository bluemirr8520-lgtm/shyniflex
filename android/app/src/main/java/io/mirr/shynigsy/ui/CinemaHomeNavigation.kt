package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.isTelevision

/** Dark artwork headers use ivory; the Lounge header shares its readable page foreground. */
@Composable
internal fun CinemaHomeHeader(
    compact: Boolean,
    onAppearance: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    television: Boolean = LocalContext.current.isTelevision(),
) {
    val style = LocalCinemaStyle.current
    val foreground = when (style) {
        CinemaStyle.CINEMA -> Color(0xFFF4E8D1)
        CinemaStyle.OTT -> Color.White
        CinemaStyle.LOUNGE -> MaterialTheme.colorScheme.onBackground
    }
    Row(modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 4.dp)
        .testTag("cinema-home-header"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
        Text("SHYNIFLEX", Modifier.weight(1f).padding(start = 4.dp),
            color = foreground, fontSize = if (compact) 13.sp else 14.sp,
            letterSpacing = if (style == CinemaStyle.OTT) .6.sp else if (compact) 2.sp else 3.sp,
            fontFamily = if (style == CinemaStyle.OTT) FontFamily.SansSerif else FontFamily.Serif,
            fontWeight = if (style == CinemaStyle.OTT) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Device type owns navigation. A wide phone/tablet still keeps every action at the bottom.
        if (television) {
            if (!compact) TvHomeClock(Modifier.padding(end = 8.dp), foreground = foreground)
            CinemaHeaderAction(cinemaIcon(CinemaGlyph.Palette), "화면 스타일", onAppearance, "cinema-home-appearance", foreground)
            CinemaHeaderAction(cinemaIcon(CinemaGlyph.Search), "검색", onSearch, "cinema-home-search", foreground)
        }
    }
}

@Composable
private fun CinemaHeaderAction(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, foreground: Color) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    // A user-selected light page may have a dark primary. Its focus circle preserves contrast
    // on the artwork's dark scrim while still using the exact shared primary color.
    val focusedBackground = if (focused || pressed) MaterialTheme.colorScheme.background else Color.Transparent
    IconButton(onClick = onClick, interactionSource = interaction,
        modifier = Modifier.size(48.dp).background(focusedBackground, CircleShape).border(if (focused) 2.dp else 0.dp,
            if (focused) accent else Color.Transparent, CircleShape).testTag(tag)) {
        Icon(icon, label, Modifier.size(24.dp), tint = if (focused || pressed) accent else foreground)
    }
}

/** Page chrome uses the user's palette; selection is one short underline, never a filled tile. */
@Composable
internal fun CinemaHomeBottomNavigation(
    homeSelected: Boolean,
    searchSelected: Boolean,
    librarySelected: Boolean,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onLibraries: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: () -> Unit = {},
    moreSelected: Boolean = false,
) {
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
        .testTag("cinema-mobile-navigation"), horizontalArrangement = Arrangement.SpaceEvenly) {
        CinemaNavigationItem("홈", cinemaIcon(CinemaGlyph.Home), homeSelected, onHome,
            Modifier.weight(1f).testTag("cinema-mobile-home"))
        CinemaNavigationItem("검색", cinemaIcon(CinemaGlyph.Search), searchSelected, onSearch,
            Modifier.weight(1f).testTag("cinema-mobile-search"))
        CinemaNavigationItem("라이브러리", cinemaIcon(CinemaGlyph.Library), librarySelected, onLibraries,
            Modifier.weight(1f).testTag("cinema-mobile-libraries"))
        CinemaNavigationItem("더보기", cinemaIcon(CinemaGlyph.More), moreSelected, onMore,
            Modifier.weight(1f).testTag("cinema-mobile-more"))
    }
}

@Composable
private fun CinemaNavigationItem(
    label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val active = selected || focused || pressed
    val foreground = if (active) colors.primary else colors.onSurfaceVariant
    Column(modifier.heightIn(min = 64.dp).semantics { this.selected = selected }
        .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = foreground)
        Text(label, style = MaterialTheme.typography.labelMedium, color = foreground,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(if (focused) 26.dp else 16.dp).height(2.dp)
            .background(if (active) colors.primary else Color.Transparent))
    }
}
