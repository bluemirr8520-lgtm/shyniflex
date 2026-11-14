package io.mirr.shynigsy.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.data.RecentLibraryStatus
import io.mirr.shynigsy.isTelevision

/** Keep the authorized library visible even before its first successful recent-items response. */
@Composable
internal fun HomeRecentLibraryStatus(
    title: String,
    status: RecentLibraryStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    television: Boolean = LocalContext.current.isTelevision(),
) {
    // D-pad users must be able to scroll through pending or empty libraries as well as posters.
    Column(modifier.fillMaxWidth().then(if (television) Modifier.tvFocus().focusable() else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("최근 추가된 작품 · $title", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text(when (status) {
            RecentLibraryStatus.LOADING -> "불러오는 중입니다"
            RecentLibraryStatus.EMPTY -> "최근 추가된 작품이 없습니다"
            // READY with no visible items must not claim the server returned an empty library.
            RecentLibraryStatus.READY, RecentLibraryStatus.ERROR -> "목록을 불러오지 못했습니다"
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == RecentLibraryStatus.ERROR || status == RecentLibraryStatus.READY) {
            TvAction("다시 시도", modifier = Modifier.testTag("home-recent-retry"), onClick = onRetry)
        }
    }
}

@Composable
internal fun HomeRecentLibraryRetry(onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("목록을 새로 불러오지 못했습니다", Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TvAction("다시 시도", modifier = Modifier.testTag("home-recent-retry"), onClick = onRetry)
    }
}
