package io.mirr.shynigsy.player

import android.content.SharedPreferences
import java.util.UUID

/** Device-local video preferences. No media identity, playback engine or subtitle settings are stored here. */
internal class PicturePresetStore(private val preferences: SharedPreferences) {
    fun readCurrent(): PictureSettings = decodePictureSettings(preferences.all[CURRENT_KEY] as? String)

    fun writeCurrent(value: PictureSettings) {
        preferences.edit().putString(CURRENT_KEY, encodePictureSettings(value)).apply()
    }

    fun list(): List<PicturePreset> = synchronized(lock) {
        decodePicturePresets(preferences.all[PRESETS_KEY] as? String)
    }

    fun save(name: String, settings: PictureSettings, replaceId: String? = null): PicturePreset = synchronized(lock) {
        val next = updatedPicturePresets(list(), name, settings, replaceId, "custom_${UUID.randomUUID()}")
        preferences.edit().putString(PRESETS_KEY, encodePicturePresets(next)).apply()
        next.first { it.id == (replaceId ?: next.last().id) }
    }

    fun delete(id: String) = synchronized(lock) {
        val previous = list()
        val next = previous.filterNot { it.id == id }
        if (next.size != previous.size) preferences.edit().putString(PRESETS_KEY, encodePicturePresets(next)).apply()
    }

    private companion object {
        const val CURRENT_KEY = "picture_settings_v1"
        const val PRESETS_KEY = "picture_presets_v1"
        val lock = Any()
    }
}
