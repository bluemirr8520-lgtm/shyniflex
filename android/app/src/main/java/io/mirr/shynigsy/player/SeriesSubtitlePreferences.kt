package io.mirr.shynigsy.player

import android.content.SharedPreferences
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.hasKnownMediaOrigin
import io.mirr.shynigsy.data.libraryOrderScope

/** Plex's stored display name can be shared by different accounts. Use its credential scope in that case. */
internal fun subtitleAccountScope(server: String, user: String, token: String): String? {
    val principal = when {
        user.isNotBlank() && user !in setOf("Plex", "사용자") -> "user:$user"
        token.isNotBlank() -> "session:$token"
        else -> return null
    }
    return libraryOrderScope(server, principal)
}

internal fun seriesSubtitleKey(scope: String?, spec: PlaybackSpec): String? {
    val item = spec.sourceItem ?: return null
    val show = item.showId ?: return null
    if (scope == null || item.type != "episode" || show <= 0 || item.libraryId <= 0 || item.libraryId != spec.libraryId) return null
    return "$scope:${item.libraryId}:$show:${item.isSpoiler}"
}

internal class SeriesSubtitlePreferences(private val prefs: SharedPreferences, private val account: String?) {
    fun sameSeries(previous: PlaybackSpec, next: PlaybackSpec): Boolean {
        val previousKey = seriesSubtitleKey(account, previous) ?: return false
        return previousKey == seriesSubtitleKey(account, next)
    }

    fun enabled(spec: PlaybackSpec, audio: String? = null): Boolean {
        val default = defaultSubtitlesEnabled(spec, audio)
        val key = seriesSubtitleKey(account, spec) ?: return default
        if (!prefs.contains(key)) return default
        val confirmed = prefs.getBoolean("korean:$key", false)
        if (!default && !confirmed) prefs.edit().putBoolean("korean:$key", true).apply()
        if (!default || (confirmed && spec.sourceItem?.hasKnownMediaOrigin() == false))
            return prefs.getBoolean(key, default)
        return default
    }

    fun remember(spec: PlaybackSpec, audio: String?, enabled: Boolean) {
        val key = seriesSubtitleKey(account, spec) ?: return
        val korean = !defaultSubtitlesEnabled(spec, audio)
        if (!korean && spec.sourceItem?.hasKnownMediaOrigin() == true) return
        // An early manual choice may arrive before audio/country metadata. It becomes a
        // series override only after the existing policy identifies a Korean episode.
        val edit = prefs.edit().putBoolean(key, enabled)
        if (korean) edit.putBoolean("korean:$key", true)
        edit.apply()
    }
}
