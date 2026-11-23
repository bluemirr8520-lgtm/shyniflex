package io.mirr.shynigsy.player

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class PicturePresetStoreTest {
    @Test fun freshAndroidPlaybackUsesBrighterDefaultsWithLessRedUntilTheUserSavesAnAdjustment() {
        val preferences = MemoryPreferences()
        val store = PicturePresetStore(preferences)
        assertEquals(PictureSettings(brightness = 8, redTone = -8), store.readCurrent())
        assertTrue(preferences.all.isEmpty())
        val filmmaker = lgInspiredPicturePresets().first { it.id == "lg_filmmaker" }
        store.writeCurrent(filmmaker.settings)
        assertTrue(store.selectAppliedPreset(filmmaker.id, filmmaker.settings))
        assertEquals(0, PicturePresetStore(preferences).readCurrent().brightness)
        assertEquals(filmmaker, PicturePresetStore(preferences).currentPreset())
        val custom = PictureSettings(-7, 2, 10)
        store.writeCurrent(custom)
        assertEquals(custom, PicturePresetStore(preferences).readCurrent())
    }

    @Test fun previousDefaultMigratesWithItsShaderWhileCustomValuesAndNamedPresetsAreKept() {
        val prefs = MemoryPreferences()
        val store = PicturePresetStore(prefs)
        val old = """{"schema":1,"brightness":5,"contrast":0,"saturation":0,"libraryShaderId":"plezy/nvscaler"}"""
        prefs.edit().putString("picture_settings_v1", old).apply()
        assertEquals(defaultAndroidPicture.copy(libraryShaderId = "plezy/nvscaler"), store.readCurrent())
        val previousLook = PictureSettings(brightness = 5, libraryShaderId = "plezy/nvscaler")
        val saved = store.save("내 설정", previousLook)
        prefs.edit().putString("picture_selected_preset_v1", saved.id).apply()
        assertEquals(previousLook, store.readCurrent())
        store.clearSelectedPreset()
        store.writeCurrent(previousLook)
        assertEquals(previousLook, store.readCurrent())
        prefs.edit().putString("picture_settings_v1", old.replace("\"brightness\":5", "\"brightness\":4")).apply()
        assertEquals(previousLook.copy(brightness = 4), store.readCurrent())
    }

    @Test fun currentVideoSettingsSurviveANewStoreWithoutTouchingPlaybackOrSubtitleSettings() {
        val preferences = MemoryPreferences()
        preferences.edit().putString("subtitle_font", "system").putFloat("playback_speed", 1.5f)
            .putInt("resize_mode", 4).putString("playback_engine", "exo").apply()
        val expected = PictureSettings(14, -12, 25)
        PicturePresetStore(preferences).writeCurrent(expected)
        assertEquals(expected, PicturePresetStore(preferences).readCurrent())
        assertEquals("system", preferences.getString("subtitle_font", null))
        assertEquals(1.5f, preferences.getFloat("playback_speed", 0f), 0f)
        assertEquals(4, preferences.getInt("resize_mode", -1))
        assertEquals("exo", preferences.getString("playback_engine", null))
        assertEquals(5, preferences.all.size)
    }

    @Test fun selectedColorPresetKeepsItsIdentityAcrossShaderTogglesAndStoreReopen() {
        val preferences = MemoryPreferences()
        val store = PicturePresetStore(preferences)
        val active = PictureSettings(8, 2, -1, libraryShaderId = "plezy/artcnn_c4f16_dn")
        val saved = store.save("테스트", active)
        assertEquals(PictureSettings(8, 2, -1), saved.settings)
        store.writeCurrent(active)
        assertTrue(store.selectAppliedPreset(saved.id, active))
        val reopened = PicturePresetStore(preferences)
        assertEquals(active, reopened.readCurrent())
        assertEquals(saved, reopened.currentPreset())
        for (id in listOf("plezy/nvscaler", null)) {
            val changed = active.copy(libraryShaderId = id)
            reopened.writeCurrent(changed)
            assertEquals(saved, reopened.currentPreset())
            assertEquals(changed, PicturePresetStore(preferences).readCurrent())
        }
    }

    @Test fun customSaveReplaceAndDeletePersistAcrossStoreInstances() {
        val preferences = MemoryPreferences()
        val first = PicturePresetStore(preferences).save("밤 감상", PictureSettings(2, 5, -8))
        val second = PicturePresetStore(preferences).save("스포츠", PictureSettings(8, 10, 30))
        assertTrue(first.id.startsWith("custom_"))
        assertNotEquals(first.id, second.id)
        val reopened = PicturePresetStore(preferences)
        assertEquals(listOf(first, second), reopened.list())
        val replaced = reopened.save("밤 감상", PictureSettings(4, -3, -8), first.id)
        assertEquals(first.id, replaced.id)
        assertEquals(listOf(replaced, second), PicturePresetStore(preferences).list())
        PicturePresetStore(preferences).delete(second.id)
        assertEquals(listOf(replaced), PicturePresetStore(preferences).list())
        PicturePresetStore(preferences).delete("builtin_original")
        assertEquals(listOf(replaced), PicturePresetStore(preferences).list())
    }

    @Test fun failedSaveLeavesCurrentSettingsAndAllExistingPresetsUntouched() {
        val preferences = MemoryPreferences()
        val store = PicturePresetStore(preferences)
        store.writeCurrent(PictureSettings(5, 6, 7))
        val first = store.save("저장됨", PictureSettings(1, 2, 3))
        val before = preferences.all
        assertThrows(IllegalArgumentException::class.java) { store.save("저장됨", PictureSettings(40, 50, 60)) }
        assertThrows(IllegalArgumentException::class.java) { store.save(" ", PictureSettings()) }
        assertEquals(before, preferences.all)
        assertEquals(listOf(first), store.list())
        assertEquals(PictureSettings(5, 6, 7), store.readCurrent())
    }

    @Test fun WrongTypesAndDamagedStorageUseSafeDefaults() {
        val preferences = MemoryPreferences()
        preferences.edit().putBoolean("picture_settings_v1", true).putInt("picture_presets_v1", 99).apply()
        val store = PicturePresetStore(preferences)
        assertEquals(PictureSettings(), store.readCurrent())
        assertTrue(store.list().isEmpty())
        preferences.edit().putString("picture_settings_v1", "bad json").putString("picture_presets_v1", "bad json").apply()
        assertEquals(PictureSettings(), store.readCurrent())
        assertTrue(store.list().isEmpty())
        val saved = store.save("복구", PictureSettings(5, 2, -4))
        assertEquals(listOf(saved), PicturePresetStore(preferences).list())
    }

    @Test fun neutralLgPresetIdentitySurvivesSavingAndReopeningButExplicitResetClearsIt() {
        val preferences = MemoryPreferences()
        val store = PicturePresetStore(preferences)
        val filmmaker = lgInspiredPicturePresets().first { it.id == "lg_filmmaker" }
        store.writeCurrent(filmmaker.settings)
        assertTrue(store.selectAppliedPreset(filmmaker.id, filmmaker.settings))
        store.writeCurrent(filmmaker.settings)
        assertEquals(filmmaker, PicturePresetStore(preferences).currentPreset())
        store.clearSelectedPreset()
        store.writeCurrent(PictureSettings())
        assertEquals("builtin_original", PicturePresetStore(preferences).currentPreset()?.id)
        assertFalse(store.selectAppliedPreset("not_a_preset", PictureSettings()))
    }

    @Test fun selectedPresetNeverLabelsDifferentOrUnsavedValues() {
        val store = PicturePresetStore(MemoryPreferences())
        val vivid = lgInspiredPicturePresets().first { it.id == "lg_vivid" }
        assertFalse(store.selectAppliedPreset(vivid.id, vivid.settings))
        store.writeCurrent(vivid.settings)
        assertTrue(store.selectAppliedPreset(vivid.id, vivid.settings))
        store.writeCurrent(PictureSettings(1, 2, 3))
        assertNull(store.currentPreset())
        store.writeCurrent(vivid.settings)
        assertNull("A previously cleared ID cannot reappear just because values match", store.currentPreset())
    }

    @Test fun selectedCustomPresetTracksRenameAndDeletionWithoutDeletingColorSettings() {
        val store = PicturePresetStore(MemoryPreferences())
        val saved = store.save("내 영화", PictureSettings(7, -4, 9))
        store.writeCurrent(saved.settings)
        assertTrue(store.selectAppliedPreset(saved.id, saved.settings))
        val renamed = store.save("내 영화 새 이름", saved.settings, saved.id)
        assertEquals(renamed, store.currentPreset())
        store.delete(saved.id)
        assertNull(store.currentPreset())
        assertEquals(saved.settings, store.readCurrent())
    }

    /** In-memory SharedPreferences contract fixture; no Android storage or mocking library. */
    internal class MemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearing = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearing = true }
            override fun commit(): Boolean {
                if (clearing) this@MemoryPreferences.values.clear()
                pending.forEach { (key, value) -> if (value == null) this@MemoryPreferences.values.remove(key)
                    else this@MemoryPreferences.values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
