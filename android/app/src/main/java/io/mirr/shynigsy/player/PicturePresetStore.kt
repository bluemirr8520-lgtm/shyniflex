package io.mirr.shynigsy.player

import android.content.SharedPreferences
import java.util.UUID

/** Device-local video preferences. No media identity, playback engine or subtitle settings are stored here. */
internal class PicturePresetStore(private val preferences: SharedPreferences) {
    fun readCurrent(): PictureSettings {
        val saved = preferences.all[CURRENT_KEY]
        if (saved == null) return defaultAndroidPicture
        return decodePictureSettings(saved as? String, upgradeOldDefault = !preferences.contains(SELECTED_KEY))
    }

    fun writeCurrent(value: PictureSettings) = synchronized(lock) {
        val normalized = value.normalized()
        val selected = selectedPreset(normalized)
        preferences.edit().putString(CURRENT_KEY, encodePictureSettings(normalized)).apply {
            if (selected == null) remove(SELECTED_KEY)
        }.apply()
    }

    /** Identity distinguishes neutral LG modes from Original without changing the color values. */
    fun currentPreset(value: PictureSettings = readCurrent()): PicturePreset? = synchronized(lock) {
        val colors = value.normalized().colorsOnly()
        selectedPreset(colors) ?: builtInPicturePresets().firstOrNull { it.settings == colors }
    }

    fun selectAppliedPreset(id: String, value: PictureSettings): Boolean = synchronized(lock) {
        val normalized = value.normalized()
        val colors = normalized.colorsOnly()
        val found = availablePresets().firstOrNull { it.id == id && it.settings == colors }
        if (found == null || readCurrent() != normalized) return@synchronized false
        preferences.edit().putString(SELECTED_KEY, found.id).apply()
        true
    }

    fun clearSelectedPreset() { preferences.edit().remove(SELECTED_KEY).apply() }

    private fun availablePresets() = builtInPicturePresets() + lgInspiredPicturePresets() + list()

    private fun selectedPreset(value: PictureSettings): PicturePreset? {
        val id = preferences.all[SELECTED_KEY] as? String ?: return null
        val colors = value.colorsOnly()
        return availablePresets().firstOrNull { it.id == id && it.settings == colors }
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
        if (next.size != previous.size) preferences.edit().putString(PRESETS_KEY, encodePicturePresets(next)).apply {
            if (preferences.all[SELECTED_KEY] == id) remove(SELECTED_KEY)
        }.apply()
    }

    private companion object {
        const val CURRENT_KEY = "picture_settings_v1"
        const val PRESETS_KEY = "picture_presets_v1"
        const val SELECTED_KEY = "picture_selected_preset_v1"
        val lock = Any()
    }
}
