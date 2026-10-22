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
import androidx.compose.material3.MaterialTheme
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
import io.mirr.shynigsy.data.LibraryWatchFilter

/** Filter, display type and sort share the same compact controls on touch and TV. */
@Composable
internal fun PlexLibraryToolbar(
    library: Library,
    sort: LibrarySort,
    contentType: LibraryContentType,
    watchFilter: LibraryWatchFilter,
    genre: String?,
    year: Int?,
    decade: Int?,
    genres: List<String>,
    years: List<Int>,
    busy: Boolean,
    spoilerUnlocked: Boolean,
    onSort: (LibrarySort) -> Unit,
    onContentType: (LibraryContentType) -> Unit,
    onWatchFilter: (LibraryWatchFilter) -> Unit,
    onGenre: (String?) -> Unit,
    onYear: (Int?) -> Unit,
    onDecade: (Int?) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    firstControlModifier: Modifier = Modifier,
) {
    var menu by remember(library.id, library.isSpoiler, spoilerUnlocked) { mutableStateOf<LibraryMenu?>(null) }
    if (library.isSpoiler && !spoilerUnlocked) return
    val filterCount = listOfNotNull(genre, year, decade).size + if (watchFilter == LibraryWatchFilter.ALL) 0 else 1
    val decades = remember(years) { years.map { it / 10 * 10 }.distinct().sortedDescending() }
    fun select(changed: Boolean = true, action: () -> Unit) {
        menu = null
        if (!busy && changed) action()
    }
    Column(modifier.testTag("library-toolbar"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TvAction(if (filterCount == 0) "전체 ▾" else "필터 · $filterCount ▾",
                firstControlModifier.testTag("library-filter"), enabled = !busy) { menu = LibraryMenu.FILTER }
            TvAction("${contentType.label} ▾", Modifier.testTag("library-type"), enabled = !busy) { menu = LibraryMenu.TYPE }
            TvAction("${sort.field.label} ▾", Modifier.testTag("library-sort"), enabled = !busy) { menu = LibraryMenu.SORT }
            TvAction(if (sort.isAscending) "↑" else "↓", Modifier.testTag("library-sort-direction").semantics {
                contentDescription = if (sort.isAscending) "오름차순, 내림차순으로 변경" else "내림차순, 오름차순으로 변경"
            }, enabled = !busy) { onSort(sort.reversedDirection()) }
        }
        if (filterCount > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (watchFilter != LibraryWatchFilter.ALL) TvAction("${watchFilter.label} ×",
                Modifier.testTag("library-remove-watch").semantics { contentDescription = "${watchFilter.label} 필터 제거" },
                enabled = !busy) { onWatchFilter(LibraryWatchFilter.ALL) }
            if (genre != null) TvAction("장르 · $genre ×",
                Modifier.testTag("library-remove-genre").semantics { contentDescription = "장르 $genre 필터 제거" },
                enabled = !busy) { onGenre(null) }
            if (year != null) TvAction("${year}년 ×",
                Modifier.testTag("library-remove-year").semantics { contentDescription = "${year}년 필터 제거" },
                enabled = !busy) { onYear(null) }
            if (decade != null) TvAction("${decade}년대 ×",
                Modifier.testTag("library-remove-decade").semantics { contentDescription = "${decade}년대 필터 제거" },
                enabled = !busy) { onDecade(null) }
            TvAction("필터 초기화", Modifier.testTag("library-clear-filters"), enabled = !busy, onClick = onClearFilters)
        }
    }
    when (menu) {
        LibraryMenu.FILTER -> LibraryOptionsDialog("필터",
            LibraryWatchFilter.entries.map { LibraryOption("watch-${it.name}", it.label) } + listOf(
                LibraryOption("genre", "장르 · ${genre ?: "전체"}"),
                LibraryOption("year", "연도 · ${year?.let { "${it}년" } ?: "전체"}"),
                LibraryOption("decade", "연대 · ${decade?.let { "${it}년대" } ?: "전체"}"),
                LibraryOption("clear", "필터 초기화")), "watch-${watchFilter.name}", busy,
            onDismiss = { menu = null }) { value ->
            when (value) {
                "genre" -> menu = LibraryMenu.GENRE
                "year" -> menu = LibraryMenu.YEAR
                "decade" -> menu = LibraryMenu.DECADE
                "clear" -> select(filterCount > 0, onClearFilters)
                else -> select(value != "watch-${watchFilter.name}") { onWatchFilter(LibraryWatchFilter.valueOf(value.removePrefix("watch-"))) }
            }
        }
        LibraryMenu.TYPE -> LibraryOptionsDialog("표시 종류",
            LibraryContentType.availableFor(library).map { LibraryOption(it.name, it.label) }, contentType.name, busy,
            onDismiss = { menu = null }) { value -> select(value != contentType.name) { onContentType(LibraryContentType.valueOf(value)) } }
        LibraryMenu.SORT -> LibraryOptionsDialog("정렬 기준",
            LibrarySortField.entries.map { LibraryOption(it.name, it.label) }, sort.field.name, busy,
            onDismiss = { menu = null }) { value -> select(value != sort.field.name) { onSort(LibrarySortField.valueOf(value).defaultSort) } }
        LibraryMenu.GENRE -> LibraryOptionsDialog("장르",
            listOf(LibraryOption("all", "전체")) + genres.map { LibraryOption("genre-$it", it) },
            genre?.let { "genre-$it" } ?: "all", busy,
            emptyHint = if (genres.isEmpty()) "서버가 제공한 장르 정보가 없습니다." else null,
            onDismiss = { menu = null }) { value ->
            val next = if (value == "all") null else value.removePrefix("genre-")
            select(next != genre) { onGenre(next) }
        }
        LibraryMenu.YEAR -> LibraryOptionsDialog("제작 연도",
            listOf(LibraryOption("all", "전체")) + years.distinct().sortedDescending().map { LibraryOption("year-$it", "${it}년") },
            year?.let { "year-$it" } ?: "all", busy,
            emptyHint = if (years.isEmpty()) "서버가 제공한 연도 정보가 없습니다." else null,
            onDismiss = { menu = null }) { value ->
            val next = if (value == "all") null else value.removePrefix("year-").toInt()
            select(next != year) { onYear(next) }
        }
        LibraryMenu.DECADE -> LibraryOptionsDialog("연대",
            listOf(LibraryOption("all", "전체")) + decades.map { LibraryOption("decade-$it", "${it}년대") },
            decade?.let { "decade-$it" } ?: "all", busy,
            emptyHint = if (decades.isEmpty()) "서버가 제공한 연도 정보가 없습니다." else null,
            onDismiss = { menu = null }) { value ->
            val next = if (value == "all") null else value.removePrefix("decade-").toInt()
            select(next != decade) { onDecade(next) }
        }
        null -> Unit
    }
}

private enum class LibraryMenu { FILTER, TYPE, SORT, GENRE, YEAR, DECADE }
private data class LibraryOption(val key: String, val label: String)

@Composable
private fun LibraryOptionsDialog(title: String, options: List<LibraryOption>, selected: String, busy: Boolean,
    emptyHint: String? = null, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val selectedIndex = options.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("library-options"),
        title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                emptyHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
