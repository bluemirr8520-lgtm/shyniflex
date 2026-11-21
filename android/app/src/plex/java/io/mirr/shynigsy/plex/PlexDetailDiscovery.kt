package io.mirr.shynigsy.plex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.mirr.shynigsy.DetailDiscoveryState
import io.mirr.shynigsy.data.ActorWorks
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.MediaItem
import java.io.IOException

internal class PlexDiscoveryPresentation(
    val state: DetailDiscoveryState?,
    val selectActor: (CastMember) -> Unit,
    val retryRelated: () -> Unit,
)

@Composable
internal fun rememberPlexDiscovery(item: MediaItem, cast: List<CastMember>, model: PlexBrowseModel): PlexDiscoveryPresentation {
    val scope = model.artworkSession
    var frame by remember(scope, item.id) { mutableStateOf(DetailDiscoveryState(item)) }
    var selected by rememberSaveable(scope, item.id) { mutableStateOf<String?>(null) }
    var actorRetry by remember { mutableIntStateOf(0) }
    var relatedRetry by remember { mutableIntStateOf(0) }
    val cache = remember(scope, item.id) { mutableMapOf<String, ActorWorks>() }
    suspend fun works(actor: CastMember): ActorWorks = cache[actor.key] ?: model.actorWorks(actor).also {
        if (!it.incomplete) cache[actor.key] = it
    }
    LaunchedEffect(scope, item.id, cast, relatedRetry) {
        val leads = cast.filter { it.canLoadWorks }.take(3)
        if (leads.isEmpty()) return@LaunchedEffect
        frame = frame.copy(relatedLoading = true, relatedError = null)
        var related = emptyList<MediaItem>()
        var failed = false
        for (lead in leads) {
            try {
                val result = works(lead)
                related = result.items.filter { it.id != item.id && it.id != item.showId }
                if (related.isNotEmpty()) break
                failed = failed || result.incomplete
            } catch (_: IOException) { failed = true }
        }
        frame = frame.copy(relatedLoading = false, relatedLoaded = true, relatedItems = related,
            relatedError = if (related.isEmpty() && failed) "관련 작품을 불러오지 못했습니다." else null)
    }
    LaunchedEffect(scope, item.id, cast, selected, actorRetry) {
        val actor = cast.firstOrNull { it.key == selected } ?: return@LaunchedEffect
        frame = frame.copy(selectedActor = actor, actorItems = emptyList(), actorLoading = true, actorError = null,
            actorLoaded = false, actorIncomplete = false)
        try {
            val result = works(actor)
            frame = frame.copy(actorItems = result.items, actorLoading = false, actorLoaded = true, actorIncomplete = result.incomplete)
        } catch (_: IOException) {
            frame = frame.copy(actorLoading = false, actorError = "출연작을 불러오지 못했습니다.")
        }
    }
    return PlexDiscoveryPresentation(frame.takeIf { cast.isNotEmpty() }, { actor ->
        if (actor.key == selected) actorRetry++ else selected = actor.key
    }, { relatedRetry++ })
}
