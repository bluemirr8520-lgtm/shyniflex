package io.mirr.shynigsy.player

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class PicturePresetStoreTest {
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

    /** In-memory SharedPreferences contract fixture; no Android storage or mocking library. */
    private class MemoryPreferences : SharedPreferences {
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
