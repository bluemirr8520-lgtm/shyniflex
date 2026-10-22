package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.LibraryContentType
import io.mirr.shynigsy.data.LibrarySort
import io.mirr.shynigsy.data.LibrarySortField

/** Display type and sort share the same compact controls on touch and TV. */
@Composable
internal fun PlexLibraryToolbar(
    library: Library,
    sort: LibrarySort,
    contentType: LibraryContentType,
    busy: Boolean,
    spoilerUnlocked: Boolean,
    onSort: (LibrarySort) -> Unit,
    onContentType: (LibraryContentType) -> Unit,
    modifier: Modifier = Modifier,
    firstControlModifier: Modifier = Modifier,
) {
    var menu by remember(library.id, library.isSpoiler, spoilerUnlocked) { mutableStateOf<LibraryMenu?>(null) }
    if (library.isSpoiler && !spoilerUnlocked) return
    fun select(changed: Boolean, action: () -> Unit) {
        menu = null
        if (!busy && changed) action()
    }
    FlowRow(modifier.testTag("library-toolbar"), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TvAction("${contentType.label} ▾", firstControlModifier.testTag("library-type"), enabled = !busy) { menu = LibraryMenu.TYPE }
            TvAction("${sort.field.label} ▾", Modifier.testTag("library-sort"), enabled = !busy) { menu = LibraryMenu.SORT }
            TvAction(if (sort.isAscending) "↑" else "↓", Modifier.testTag("library-sort-direction").semantics {
                contentDescription = if (sort.isAscending) "오름차순, 내림차순으로 변경" else "내림차순, 오름차순으로 변경"
            }, enabled = !busy) { onSort(sort.reversedDirection()) }
    }
    when (menu) {
        LibraryMenu.TYPE -> LibraryOptionsDialog("표시 종류",
            LibraryContentType.availableFor(library).map { LibraryOption(it.name, it.label) }, contentType.name, busy,
            onDismiss = { menu = null }) { value -> select(value != contentType.name) { onContentType(LibraryContentType.valueOf(value)) } }
        LibraryMenu.SORT -> LibraryOptionsDialog("정렬 기준",
            LibrarySortField.entries.map { LibraryOption(it.name, it.label) }, sort.field.name, busy,
            onDismiss = { menu = null }) { value -> select(value != sort.field.name) { onSort(LibrarySortField.valueOf(value).defaultSort) } }
        null -> Unit
    }
}

private enum class LibraryMenu { TYPE, SORT }
private data class LibraryOption(val key: String, val label: String)

@Composable
private fun LibraryOptionsDialog(title: String, options: List<LibraryOption>, selected: String, busy: Boolean,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val selectedIndex = options.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("library-options"),
        title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(Modifier.heightIn(max = 320.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(options, key = { it.key }) { option ->
                        TvAction((if (option.key == selected) "✓ " else "") + option.label,
                            Modifier.fillMaxWidth().testTag("library-option-${option.key}")
                                .semantics { this.selected = option.key == selected }
                                .then(if (option.key == options[selectedIndex].key) Modifier.focusRequester(focus) else Modifier),
                            enabled = !busy) { onSelect(option.key) }
                    }
                }
            }
        }, confirmButton = { TvAction("닫기", Modifier.testTag("library-options-close"), onClick = onDismiss) })
}
