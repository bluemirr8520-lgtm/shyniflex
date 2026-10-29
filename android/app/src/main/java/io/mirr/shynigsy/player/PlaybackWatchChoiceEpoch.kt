package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem

/** In-memory account/View/content identity. No titles, URLs or credentials are retained. */
internal data class WatchIdentity(
    val scope: String?, val library: Int?, val view: Int?, val id: Int?, val type: String?,
    val show: Int?, val season: Int?, val episode: Int?,
) {
    companion object {
        fun of(scope: String?, item: MediaItem?) = WatchIdentity(scope, item?.libraryId?.takeIf { it > 0 },
            item?.viewItemId?.takeIf { it > 0 }, item?.id?.takeIf { it > 0 }, item?.type,
            item?.showId?.takeIf { it > 0 }, item?.seasonNumber, item?.episodeNumber)
    }
}

/** A series/season choice conflicts with its episodes; unknown identity fails closed. */
internal fun watchIdentitiesConflict(a: WatchIdentity, b: WatchIdentity): Boolean {
    if (a.scope != null && b.scope != null && a.scope != b.scope) return false
    fun collection(x: WatchIdentity) = x.type in setOf("show", "tv", "season")
    // Shyni stores canonical content progress across Views and file versions.
    // Positive content identity therefore takes precedence over View boundaries.
    if (!collection(a) && !collection(b) && a.id != null && a.id == b.id &&
        (a.type == b.type || a.type == null || b.type == null)) return true
    if (a.type == "episode" && b.type == "episode" && a.show != null && a.show == b.show &&
        a.season != null && a.season == b.season && a.episode != null && a.episode == b.episode) return true
    fun show(x: WatchIdentity) = if (x.type in setOf("show", "tv")) x.id else x.show
    if ((collection(a) || collection(b)) && show(a) != null && show(a) == show(b)) {
        val seasonA = if (a.type == "season" || a.type == "episode") a.season else null
        val seasonB = if (b.type == "season" || b.type == "episode") b.season else null
        return seasonA == null || seasonB == null || seasonA == seasonB
    }
    if (a.library != null && b.library != null && a.library != b.library) return false
    fun collectionContains(c: WatchIdentity, other: WatchIdentity): Boolean {
        if (other.type == "movie") return false
        val collectionShow = if (c.type == "season") c.show else c.id
        val otherShow = if (other.type in setOf("show", "tv")) other.id else other.show
        if (collectionShow != null && otherShow != null && collectionShow != otherShow) return false
        if (c.type == "season" && c.season != null && other.season != null && c.season != other.season) return false
        return true
    }
    if (collection(a)) return collectionContains(a, b)
    if (collection(b)) return collectionContains(b, a)
    if (a.view != null && b.view != null && a.view != b.view) return false
    if (a.type == "movie" && b.type == "movie" && a.view != null && a.view == b.view) return true
    if (a.id == null || b.id == null || a.type == null || b.type == null) return true
    if (a.type != b.type) return false
    return a.id == b.id
}

/** Ignore stale UI confirmations only; server write ordering remains unchanged. */
internal class PlaybackWatchChoiceEpoch {
    private val lock = Any()
    private val choices = mutableMapOf<WatchIdentity, Long>()
    private var sequence = 0L

    fun snapshot(): Long = synchronized(lock) { sequence }
    fun record(scope: String?, item: MediaItem) = synchronized(lock) {
        choices[WatchIdentity.of(scope, item)] = ++sequence
    }
    fun isCurrent(scope: String?, item: MediaItem?, snapshot: Long): Boolean = synchronized(lock) {
        val identity = WatchIdentity.of(scope, item)
        choices.none { (chosen, revision) -> revision > snapshot && watchIdentitiesConflict(chosen, identity) }
    }
}
