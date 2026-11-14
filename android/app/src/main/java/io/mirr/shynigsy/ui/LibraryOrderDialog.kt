package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.isTelevision
import kotlinx.coroutines.launch

/** Uses the same controls on touch and D-pad devices; changes are saved as each row moves. */
@Composable
internal fun LibraryOrderDialog(
    libraries: List<Library>,
    spoilerUnlocked: Boolean,
    hasCustomOrder: Boolean,
    onMove: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    television: Boolean = LocalContext.current.isTelevision(),
) {
    val visible = libraries.filter { spoilerUnlocked || !it.isSpoiler }.distinctBy { it.id }
    val focus = remember(visible.map { it.id }.toSet()) {
        visible.associate { it.id to (FocusRequester() to FocusRequester()) }
    }
    val closeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(television, visible.map { it.id }) {
        if (!television || initialFocusRequested) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            if (visible.size > 1) focus.getValue(visible.first().id).second.requestFocus() else closeFocus.requestFocus()
        }
        initialFocusRequested = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("library-order-dialog"),
        title = { Text("라이브러리 순서") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("위·아래 버튼으로 옮기면 이 기기에 바로 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (visible.isEmpty()) Text("표시할 라이브러리가 없습니다", style = MaterialTheme.typography.bodyMedium)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("library-order-list"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(visible, key = { _, library -> library.id }) { index, library ->
                        val (up, down) = focus.getValue(library.id)
                        fun move(direction: Int) {
                            onMove(library.id, direction)
                            if (television) scope.launch {
                                withFrameNanos { }
                                // At an edge keep focus on the moved row's remaining enabled action.
                                val nextIndex = index + direction
                                val target = when {
                                    nextIndex == 0 -> down
                                    nextIndex == visible.lastIndex -> up
                                    direction < 0 -> up
                                    else -> down
                                }
                                if (!runCatching { target.requestFocus() }.getOrDefault(false)) {
                                    // A row can leave the composed window after a move near its lower edge.
                                    listState.scrollToItem(nextIndex.coerceIn(visible.indices))
                                    withFrameNanos { }
                                    runCatching { target.requestFocus() }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().testTag("library-order-row-${library.id}")
                            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                            Text(library.title, Modifier.weight(1f), fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            OrderArrow(Icons.Rounded.ArrowUpward, "${library.title} 위로 이동", index > 0,
                                Modifier.focusRequester(up).testTag("library-order-up-${library.id}")) { move(-1) }
                            OrderArrow(Icons.Rounded.ArrowDownward, "${library.title} 아래로 이동", index < visible.lastIndex,
                                Modifier.focusRequester(down).testTag("library-order-down-${library.id}")) { move(1) }
                        }
                    }
                }
            }
        },
        dismissButton = {
            // Dialog actions keep their height when the scrollable list is constrained on a TV.
            TextButton(onClick = {
                onReset()
                if (television) scope.launch {
                    withFrameNanos { }
                    runCatching { closeFocus.requestFocus() }
                }
            }, enabled = hasCustomOrder,
                modifier = Modifier.tvOnlyFocus().testTag("library-order-reset")) { Text("서버 기본 순서로") }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).tvOnlyFocus()
                .testTag("library-order-close")) { Text("닫기") }
        },
    )
}

@Composable
private fun OrderArrow(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.size(48.dp)
            .background(if (focused) colors.primary.copy(alpha = .18f) else Color.Transparent, CircleShape)
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.primary else Color.Transparent, CircleShape)) {
        Icon(icon, label, Modifier.size(22.dp))
    }
}
