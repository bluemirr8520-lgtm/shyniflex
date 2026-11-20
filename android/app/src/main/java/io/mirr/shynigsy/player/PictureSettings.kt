package io.mirr.shynigsy.player

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Video image adjustments only, independent of subtitles, resizing and the source file. */
internal data class PictureSettings(val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0) {
    fun normalized() = copy(brightness = brightness.coerceIn(-50, 50),
        contrast = contrast.coerceIn(-50, 50), saturation = saturation.coerceIn(-100, 100))
    val isNeutral: Boolean get() = brightness == 0 && contrast == 0 && saturation == 0
}

internal data class PicturePreset(val id: String, val name: String, val settings: PictureSettings)
internal const val MAX_PICTURE_PRESETS = 20
internal const val MAX_PICTURE_PRESET_NAME = 30
private const val MAX_PRESET_JSON_CHARS = 16_384
private val customPresetId = Regex("custom_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun builtInPicturePresets(): List<PicturePreset> = listOf(
    PicturePreset("builtin_original", "원본", PictureSettings()),
    PicturePreset("builtin_bright", "밝게", PictureSettings(12, -5, 0)),
    PicturePreset("builtin_cinema", "시네마", PictureSettings(-3, 10, -5)),
    PicturePreset("builtin_vivid", "선명하게", PictureSettings(0, 12, 18)),
    PicturePreset("builtin_soft", "부드럽게", PictureSettings(2, -10, -8)),
)

/** App-authored three-axis approximations inspired by LG's mode descriptions, not LG calibration or algorithms. */
internal fun lgInspiredPicturePresets(): List<PicturePreset> = listOf(
    PicturePreset("lg_standard", "표준", PictureSettings()),
    PicturePreset("lg_vivid", "선명한 영상", PictureSettings(3, 10, 12)),
    PicturePreset("lg_cinema", "영화", PictureSettings(-2, 4, -3)),
    PicturePreset("lg_sports", "스포츠", PictureSettings(2, 6, 8)),
    PicturePreset("lg_game", "게임", PictureSettings(0, 3, 2)),
    PicturePreset("lg_bright_room", "밝은 공간", PictureSettings(8, 2, 2)),
    PicturePreset("lg_dark_room", "어두운 공간", PictureSettings(-3, -2, -2)),
    // Neutral color processing is intentional; certified FILMMAKER MODE also controls features outside this app.
    PicturePreset("lg_filmmaker", "필름메이커 느낌", PictureSettings()),
)

internal fun normalizedPicturePresetName(value: String): String {
    require(value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
        "이름에 줄바꿈이나 제어 문자를 사용할 수 없습니다."
    }
    val name = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    require(name.length in 1..MAX_PICTURE_PRESET_NAME) { "이름은 1~30자로 입력해 주세요." }
    return name
}

private fun nameIdentity(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** Same-name saves never overwrite an existing preset without an explicit replacement ID. */
internal fun updatedPicturePresets(previous: List<PicturePreset>, name: String, settings: PictureSettings,
    replaceId: String?, newId: String): List<PicturePreset> {
    val normalizedName = normalizedPicturePresetName(name)
    val replacing = replaceId?.let { id -> previous.indexOfFirst { it.id == id }.also { index ->
        require(customPresetId.matches(id) && index >= 0) { "저장된 프리셋을 찾을 수 없습니다." }
    } }
    require(previous.none { it.id != replaceId && nameIdentity(it.name) == nameIdentity(normalizedName) }) {
        "같은 이름의 프리셋이 있습니다. 다른 이름을 쓰거나 해당 프리셋을 덮어써 주세요."
    }
    if (replacing == null) {
        require(previous.size < MAX_PICTURE_PRESETS) { "프리셋은 최대 20개까지 저장할 수 있습니다." }
        require(customPresetId.matches(newId) && previous.none { it.id == newId }) { "프리셋을 저장하지 못했습니다." }
    }
    val preset = PicturePreset(replaceId ?: newId, normalizedName, settings.normalized())
    return previous.toMutableList().apply { if (replacing == null) add(preset) else set(replacing, preset) }
}

internal fun encodePictureSettings(value: PictureSettings): String =
    value.normalized().toJson().put("schema", 1).toString()

internal fun decodePictureSettings(value: String?): PictureSettings {
    if (value == null || value.length !in 1..512) return PictureSettings()
    return runCatching {
        val root = JSONObject(value)
        require(root.strictInt("schema") == 1)
        root.toPictureSettings()
    }.getOrDefault(PictureSettings())
}

internal fun encodePicturePresets(presets: List<PicturePreset>): String {
    require(presets.size <= MAX_PICTURE_PRESETS)
    val ids = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    val entries = JSONArray()
    presets.forEach { preset ->
        val name = normalizedPicturePresetName(preset.name)
        require(customPresetId.matches(preset.id) && ids.add(preset.id) && names.add(nameIdentity(name)))
        entries.put(JSONObject().put("id", preset.id).put("name", name)
            .put("settings", preset.settings.normalized().toJson()))
    }
    return JSONObject().put("schema", 1).put("presets", entries).toString().also { require(it.length <= MAX_PRESET_JSON_CHARS) }
}

/** A damaged entry cannot discard unrelated valid presets or inject arbitrary file references. */
internal fun decodePicturePresets(value: String?): List<PicturePreset> {
    if (value == null || value.length !in 1..MAX_PRESET_JSON_CHARS) return emptyList()
    return runCatching {
        val root = JSONObject(value)
        require(root.strictInt("schema") == 1)
        val entries = root.getJSONArray("presets")
        require(entries.length() <= MAX_PICTURE_PRESETS)
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        (0 until entries.length()).mapNotNull { index -> runCatching {
            val entry = entries.getJSONObject(index)
            val id = entry.get("id") as? String ?: error("id")
            require(customPresetId.matches(id))
            val name = normalizedPicturePresetName(entry.get("name") as? String ?: error("name"))
            val settings = entry.getJSONObject("settings").toPictureSettings()
            require(id !in ids && nameIdentity(name) !in names)
            ids.add(id); names.add(nameIdentity(name))
            PicturePreset(id, name, settings)
        }.getOrNull() }
    }.getOrDefault(emptyList())
}

private fun PictureSettings.toJson() = JSONObject().put("brightness", brightness)
    .put("contrast", contrast).put("saturation", saturation)
private fun JSONObject.toPictureSettings() = PictureSettings(strictInt("brightness"),
    strictInt("contrast"), strictInt("saturation")).normalized()
private fun JSONObject.strictInt(key: String): Int {
    val number = get(key) as? Number ?: error("integer")
    val value = number.toLong()
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && number.toDouble() == value.toDouble())
    return value.toInt()
}
