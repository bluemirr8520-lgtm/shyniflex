package io.mirr.shynigsy

import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import java.io.IOException

/** A failed request must not masquerade as the server's successful empty history. */
internal sealed interface HomeContinueLoad {
    data class Success(val items: List<MediaItem>) : HomeContinueLoad
    data class Failure(val transient: Boolean) : HomeContinueLoad
}

/** Provenance of the last published rows, not merely the request in flight. */
internal class HomeContinueOwner(private val client: Any, private val token: String?) {
    fun matches(currentClient: Any, currentToken: String?): Boolean = client === currentClient && token == currentToken
}

internal fun failedHomeContinueLoad(error: Exception): HomeContinueLoad.Failure {
    // Keep the existing account/session expiration path in AppViewModel.
    if (error is ApiException && error.status == 401) throw error
    val transient = when (error) {
        is ApiException -> (error.status == 0 && error.cause is IOException) ||
            error.status in setOf(408, 425, 429) || error.status in 500..599
        is IOException -> true
        else -> false
    }
    return HomeContinueLoad.Failure(transient)
}

internal fun homeContinueLoadError(publicLoad: HomeContinueLoad, privateLoad: HomeContinueLoad): String? =
    if (publicLoad is HomeContinueLoad.Failure || privateLoad is HomeContinueLoad.Failure)
        "이어보기를 불러오지 못했습니다. 새로고침해 주세요." else null

/** Reuse only this account's rows and only within the current View/grant scope. */
internal fun resolveHomeContinueWatching(
    publicLoad: HomeContinueLoad,
    privateLoad: HomeContinueLoad,
    previous: List<MediaItem>,
    libraries: List<Library>,
    spoilerUnlocked: Boolean,
    sameAccountScope: Boolean,
    privateGrantUnchanged: Boolean,
    previousOwnerMatches: Boolean,
): List<MediaItem> {
    if (!sameAccountScope) return emptyList()
    val retained = if (previousOwnerMatches) previous else emptyList()
    val publicRows = when (publicLoad) {
        is HomeContinueLoad.Success -> publicLoad.items
        is HomeContinueLoad.Failure -> if (publicLoad.transient) retained.filterNot { it.isSpoiler } else emptyList()
    }
    val privateRows = if (spoilerUnlocked && privateGrantUnchanged) when (privateLoad) {
        is HomeContinueLoad.Success -> privateLoad.items
        is HomeContinueLoad.Failure -> if (privateLoad.transient) retained.filter { it.isSpoiler } else emptyList()
    } else emptyList()
    return visibleContinueWatching(publicRows, privateRows, libraries, spoilerUnlocked && privateGrantUnchanged)
}
