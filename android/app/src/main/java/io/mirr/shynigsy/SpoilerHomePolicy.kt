package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem

/** Home private rows belong to the account and grant under which they were published. */
internal class PrivateHomeGrantScope {
    private var owner: HomeContinueOwner? = null
    private var revision: Long? = null

    fun belongsTo(client: Any, token: String?): Boolean = owner?.matches(client, token) == true

    /** True means old private Home content must be removed before this scope is used. */
    fun advance(client: Any, token: String?, grantRevision: Long): Boolean {
        if (belongsTo(client, token) && revision == grantRevision) return false
        owner = HomeContinueOwner(client, token)
        revision = grantRevision
        return true
    }

    fun reset() { owner = null; revision = null }
}

/** Old private View IDs remain excluded from public rows until the account changes. */
internal fun homeLibrariesForGrant(
    ordinary: List<Library>, privateLibraries: List<Library>, knownPrivateIds: Set<Int>, grantCurrent: Boolean,
): List<Library> {
    val privateIds = knownPrivateIds + privateLibraries.map { it.id }
    return (ordinary.filterNot { it.isSpoiler || it.id in privateIds } +
        if (grantCurrent) privateLibraries.filter { it.isSpoiler } else emptyList()).distinctBy { it.id }
}

/** Continue rows must belong to an explicitly known public or unlocked private View. */
internal fun visibleContinueWatching(
    publicItems: List<MediaItem>,
    privateItems: List<MediaItem>,
    libraries: List<Library>,
    spoilerUnlocked: Boolean,
): List<MediaItem> {
    val privateViews = libraries.filter { it.isSpoiler }.map { it.id }.toSet()
    val publicViews = libraries.filterNot { it.isSpoiler }.map { it.id }.toSet() - privateViews
    val ordinary = publicItems.filter { !it.isSpoiler && it.libraryId in publicViews }
    val privateRows = if (spoilerUnlocked) privateItems.filter { it.libraryId in privateViews }
        .map { it.copy(isSpoiler = true) } else emptyList()
    return (ordinary + privateRows).distinctBy { listOf(it.libraryId, it.type, it.id, it.viewItemId) }
}
