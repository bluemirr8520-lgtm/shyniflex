package io.mirr.shynigsy.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PictureSettingsTest {
    private fun id(number: Int) = "custom_00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private fun preset(number: Int, name: String = "설정 $number", settings: PictureSettings = PictureSettings(number, -number, 10)) =
        PicturePreset(id(number), name, settings)

    @Test fun neutralAndBoundedAdjustmentsRetainIndependentAxes() {
        assertTrue(PictureSettings().isNeutral)
        assertFalse(PictureSettings(saturation = -1).isNeutral)
        assertEquals(PictureSettings(50, -50, 100), PictureSettings(Int.MAX_VALUE, Int.MIN_VALUE, 101).normalized())
        assertEquals(PictureSettings(-50, 50, -100), PictureSettings(-51, 51, -101).normalized())
        assertEquals(PictureSettings(12, -5, 0), PictureSettings(12, -5, 0).normalized())
    }

    @Test fun builtInsOfferOneNeutralResetAndFourDistinctVideoLooks() {
        val values = builtInPicturePresets()
        assertEquals(listOf("원본", "밝게", "시네마", "선명하게", "부드럽게"), values.map { it.name })
        assertEquals(5, values.map { it.id }.distinct().size)
        assertEquals(5, values.map { it.settings }.distinct().size)
        assertEquals(listOf("원본"), values.filter { it.settings.isNeutral }.map { it.name })
        assertTrue(values.all { it.settings == it.settings.normalized() })
        assertTrue(values.first { it.name == "밝게" }.settings.brightness > 0)
    }

    @Test fun lgReferenceModesUseBoundedColorOnlyApproximationsAndNeutralFilmmaker() {
        val presets = lgInspiredPicturePresets()
        assertEquals(listOf("표준", "선명한 영상", "영화", "스포츠", "게임", "밝은 공간", "어두운 공간", "필름메이커 느낌"),
            presets.map { it.name })
        assertEquals(13, (builtInPicturePresets() + presets).map { it.id }.distinct().size)
        assertTrue(presets.all { it.settings.brightness in -10..10 && it.settings.contrast in -15..15 &&
            it.settings.saturation in -20..20 })
        assertTrue(presets.first { it.id == "lg_standard" }.settings.isNeutral)
        assertTrue(presets.first { it.id == "lg_filmmaker" }.settings.isNeutral)
        assertTrue(presets.first { it.id == "lg_bright_room" }.settings.brightness >
            presets.first { it.id == "lg_dark_room" }.settings.brightness)
    }

    @Test fun currentSettingsRoundTripAndMalformedValuesReturnToOriginal() {
        val expected = PictureSettings(-23, 44, -100)
        assertEquals(expected, decodePictureSettings(encodePictureSettings(expected)))
        assertEquals(PictureSettings(50, -50, 100), decodePictureSettings(encodePictureSettings(PictureSettings(300, -300, 300))))
        val valid = encodePictureSettings(expected)
        val invalid = listOf(null, "", "{", "[]", "x".repeat(513),
            JSONObject(valid).put("schema", 2).toString(),
            JSONObject(valid).put("brightness", "20").toString(),
            JSONObject(valid).put("contrast", 2.5).toString(),
            JSONObject(valid).put("saturation", 2_147_483_648L).toString(),
            JSONObject(valid).apply { remove("brightness") }.toString())
        invalid.forEach { assertEquals(PictureSettings(), decodePictureSettings(it)) }
    }

    @Test fun namedPresetsRoundTripWithoutChangingOrderIdentityOrNegativeValues() {
        val saved = listOf(preset(1, "밤 영화", PictureSettings(-4, 5, -10)), preset(2, "스포츠", PictureSettings(8, 12, 30)))
        assertEquals(saved, decodePicturePresets(encodePicturePresets(saved)))
        assertTrue(decodePicturePresets(encodePicturePresets(emptyList())).isEmpty())
    }

    @Test fun damagedPresetIsSkippedWhileOtherSavedPresetsRemainUsable() {
        val saved = listOf(preset(1), preset(2), preset(3))
        val root = JSONObject(encodePicturePresets(saved))
        root.getJSONArray("presets").getJSONObject(1).getJSONObject("settings").put("brightness", true)
        assertEquals(listOf(saved[0], saved[2]), decodePicturePresets(root.toString()))
        root.getJSONArray("presets").getJSONObject(1).put("id", "../../private/file")
        assertEquals(listOf(saved[0], saved[2]), decodePicturePresets(root.toString()))
    }

    @Test fun duplicateIdentitiesAndNamesCannotProduceAmbiguousSelections() {
        val saved = listOf(preset(1, "Cinema"), preset(2, "두 번째"), preset(3, "세 번째"))
        val root = JSONObject(encodePicturePresets(saved))
        root.getJSONArray("presets").getJSONObject(1).put("name", "ＣＩＮＥＭＡ")
        root.getJSONArray("presets").getJSONObject(2).put("id", id(1))
        assertEquals(listOf(saved[0]), decodePicturePresets(root.toString()))
    }

    @Test fun invalidSchemaOrOversizedPayloadCannotRestoreUnboundedLists() {
        val one = JSONObject(encodePicturePresets(listOf(preset(1))))
        assertTrue(decodePicturePresets(null).isEmpty())
        assertTrue(decodePicturePresets("x".repeat(16_385)).isEmpty())
        assertTrue(decodePicturePresets(one.put("schema", "1").toString()).isEmpty())
        assertTrue(decodePicturePresets(one.put("schema", 2).toString()).isEmpty())
        val many = JSONArray()
        repeat(21) { many.put(JSONObject()) }
        assertTrue(decodePicturePresets(JSONObject().put("schema", 1).put("presets", many).toString()).isEmpty())
    }

    @Test fun namesTrimAndNormalizeButRejectBlankOversizedOrControlInput() {
        assertEquals("나만의 색감", normalizedPicturePresetName("  나만의 색감  "))
        assertEquals("é", normalizedPicturePresetName("e\u0301"))
        assertEquals("가".repeat(30), normalizedPicturePresetName("가".repeat(30)))
        listOf("", "  ", "가".repeat(31), "이름\n", "이름\t다음", "이름\u202e").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { normalizedPicturePresetName(name) }
        }
    }

    @Test fun savingSameNameRequiresAnExplicitReplacementAndKeepsOriginalUntouched() {
        val original = listOf(preset(1, "Cinema"), preset(2, "다른 설정"))
        assertThrows(IllegalArgumentException::class.java) {
            updatedPicturePresets(original, " cinema ", PictureSettings(), null, id(3))
        }
        val updated = updatedPicturePresets(original, "시네마 새 설정", PictureSettings(12, 8, -4), id(1), id(3))
        assertEquals(listOf(id(1), id(2)), updated.map { it.id })
        assertEquals(PictureSettings(12, 8, -4), updated[0].settings)
        assertEquals("시네마 새 설정", updated[0].name)
        assertEquals(original[1], updated[1])
        assertEquals("Cinema", original[0].name)
        assertThrows(IllegalArgumentException::class.java) {
            updatedPicturePresets(original, "다른 설정", PictureSettings(), id(1), id(3))
        }
    }

    @Test fun savingBeyondLimitFailsButReplacingOneOfTwentyStillWorks() {
        val full = (1..20).map { preset(it) }
        assertThrows(IllegalArgumentException::class.java) {
            updatedPicturePresets(full, "추가", PictureSettings(), null, id(21))
        }
        val updated = updatedPicturePresets(full, "변경", PictureSettings(500, -500, -500), id(20), id(21))
        assertEquals(20, updated.size)
        assertEquals(PictureSettings(50, -50, -100), updated.last().settings)
        assertEquals(id(20), updated.last().id)
        assertEquals(full.dropLast(1), updated.dropLast(1))
    }

    @Test fun unknownOrBuiltinReplacementCannotOverwriteACustomPreset() {
        val original = listOf(preset(1))
        for (replace in listOf(id(2), "builtin_original")) {
            assertThrows(IllegalArgumentException::class.java) {
                updatedPicturePresets(original, "새 설정", PictureSettings(), replace, id(3))
            }
        }
    }
}
