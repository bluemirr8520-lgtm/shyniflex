package io.mirr.shynigsy.ui

import java.util.Collections
import java.util.IdentityHashMap

/** Tracks visibility using opaque owner tokens, without retaining Activities. */
internal class ArtworkBrowsingState {
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Synchronized fun started(owner: Any) { owners.add(owner) }
    @Synchronized fun stopped(owner: Any) { owners.remove(owner) }
    @Synchronized fun isBrowsing(): Boolean = owners.isNotEmpty()

    companion object {
        val process = ArtworkBrowsingState()
    }
}
