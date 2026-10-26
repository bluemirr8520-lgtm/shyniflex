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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The surrounding hero always supplies a dark scrim, even with a light custom page color. */
@Composable
internal fun CinemaHomeHeader(
    compact: Boolean,
    onAppearance: () -> Unit,
    onSearch: () -> Unit,
    onMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ivory = Color(0xFFF4E8D1)
    Row(modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 4.dp)
        .testTag("cinema-home-header"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
        if (onMenu != null) CinemaHeaderAction(Icons.Rounded.Menu, "메뉴", onMenu, "cinema-home-menu")
        Text("SHYNIFLEX", Modifier.weight(1f).padding(start = if (onMenu == null) 4.dp else 0.dp),
            color = ivory, fontSize = if (compact) 13.sp else 14.sp,
            letterSpacing = if (compact) 2.sp else 4.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!compact) TvHomeClock(Modifier.padding(end = 8.dp), foreground = ivory)
        CinemaHeaderAction(Icons.Rounded.Palette, "화면 색상", onAppearance, "cinema-home-appearance")
        CinemaHeaderAction(Icons.Rounded.Search, "검색", onSearch, "cinema-home-search")
    }
}

@Composable
private fun CinemaHeaderAction(icon: ImageVector, label: String, onClick: () -> Unit, tag: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val gold = Color(0xFFD8AC68)
    IconButton(onClick = onClick, interactionSource = interaction,
        modifier = Modifier.size(48.dp).border(if (focused) 2.dp else 0.dp,
            if (focused) gold else Color.Transparent, CircleShape).testTag(tag)) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (focused || pressed) gold else Color(0xFFF4E8D1))
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
) {
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
        .testTag("cinema-mobile-navigation"), horizontalArrangement = Arrangement.SpaceEvenly) {
        CinemaNavigationItem("홈", Icons.Rounded.Home, homeSelected, onHome,
            Modifier.weight(1f).testTag("cinema-mobile-home"))
        CinemaNavigationItem("검색", Icons.Rounded.Search, searchSelected, onSearch,
            Modifier.weight(1f).testTag("cinema-mobile-search"))
        CinemaNavigationItem("라이브러리", Icons.Rounded.Folder, librarySelected, onLibraries,
            Modifier.weight(1f).testTag("cinema-mobile-libraries"))
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
        Icon(icon, null, Modifier.size(23.dp), tint = foreground)
        Text(label, style = MaterialTheme.typography.labelMedium, color = foreground,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.width(if (focused) 26.dp else 16.dp).height(2.dp)
            .background(if (active) colors.primary else Color.Transparent))
    }
}
