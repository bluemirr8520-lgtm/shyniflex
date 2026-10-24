package io.mirr.shynigsy.data

/** Provider IDs are navigation identities; a displayed name alone is never one. */
data class CastMember(
    val name: String,
    val role: String = "",
    val portrait: String? = null,
    val tmdbId: Int? = null,
    val ppkey: String? = null,
) {
    private val lookupPpkey: String? get() = ppkey?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= 200 && it.none { character -> character.isISOControl() }
    }
    val key: String get() = when {
        tmdbId != null && tmdbId > 0 -> "tmdb:$tmdbId"
        lookupPpkey != null -> "ppkey:$lookupPpkey"
        else -> "label:$name\u0000$role\u0000${portrait.orEmpty()}"
    }
    val canLoadWorks: Boolean get() = (tmdbId != null && tmdbId > 0) || lookupPpkey != null
}

/** ppkey browsing is supported only by the selected TV Library View. */
data class ActorWorks(val items: List<MediaItem>, val currentLibraryOnly: Boolean = false)
