package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The former mobile sidebar actions now open from a scrollable panel at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileMoreMenu(
    spoilerUnlocked: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAppearance: () -> Unit,
    onUpdate: () -> Unit,
    onLogout: () -> Unit,
    onSpoilerAccess: () -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag("mobile-more-sheet")) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = screenHeight * .72f)
            .testTag("mobile-more-actions"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "heading") {
                Text("더보기", Modifier.padding(horizontal = 10.dp, vertical = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item(key = "refresh") {
                MobileMoreAction("새로고침", Icons.Rounded.Refresh, "mobile-more-refresh") {
                    onDismiss(); onRefresh()
                }
            }
            item(key = "appearance") {
                MobileMoreAction("화면 색상", Icons.Rounded.Palette, "mobile-more-appearance") {
                    onDismiss(); onAppearance()
                }
            }
            item(key = "spoiler") {
                MobileMoreAction(if (spoilerUnlocked) "스포일러 잠그기" else "잠긴 라이브러리",
                    if (spoilerUnlocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "mobile-more-spoiler") {
                    onDismiss(); onSpoilerAccess()
                }
            }
            item(key = "update") {
                MobileMoreAction("업데이트", Icons.Rounded.SystemUpdate, "mobile-more-update") {
                    onDismiss(); onUpdate()
                }
            }
            item(key = "account") {
                MobileMoreAction("계정 / 로그아웃", Icons.Rounded.AccountCircle, "mobile-more-account") {
                    onDismiss(); onLogout()
                }
            }
        }
    }
}

@Composable
private fun MobileMoreAction(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    CompactMenuAction(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
