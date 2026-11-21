package io.mirr.shynigsy.plex

import android.content.Context
import io.mirr.shynigsy.data.SessionStore

/** Plex account credentials are kept separate from the PMS session used by the player. */
internal class PlexAccountStore(context: Context) {
    private val sessions = SessionStore(context, "plex_account_grant")
    private val key = "https://plex.tv"

    fun load() = sessions.load(key)
    fun save(token: String, selectedServerId: String = "") = sessions.save(key, token, selectedServerId)
    fun rememberServer(id: String) { load()?.let { save(it.token, id) } }
    fun clear() = sessions.clear(key)
}
