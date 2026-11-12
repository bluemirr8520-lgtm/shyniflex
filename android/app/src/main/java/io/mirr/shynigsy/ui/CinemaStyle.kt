package io.mirr.shynigsy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class CinemaStyle(
    val key: String,
    val label: String,
    val description: String,
    val defaultBackgroundArgb: Int,
) {
    CINEMA("cinema", "시네마 천국", "따뜻한 극장 조명과 여유로운 작품 소개", 0xFF211914.toInt()),
    OTT("ott", "포스터 중심 OTT", "선명한 포스터와 빠르게 고르는 영화 목록", 0xFF101217.toInt()),
    LOUNGE("lounge", "호텔 라운지", "밝은 아이보리와 차분한 브라스 포인트", 0xFFF3F0E8.toInt());

    companion object {
        fun fromKey(key: String?): CinemaStyle = entries.firstOrNull { it.key == key } ?: CINEMA
    }
}

internal val LocalCinemaStyle = staticCompositionLocalOf { CinemaStyle.CINEMA }

internal data class CinemaStyleController(
    val style: CinemaStyle,
    val backgrounds: Map<CinemaStyle, Int>,
    val apply: (CinemaStyle, Map<CinemaStyle, Int>) -> Unit,
) {
    fun background(style: CinemaStyle): Int = backgrounds[style] ?: style.defaultBackgroundArgb
}

internal val LocalCinemaStyleController = staticCompositionLocalOf {
    CinemaStyleController(CinemaStyle.CINEMA,
        CinemaStyle.entries.associateWith { it.defaultBackgroundArgb }) { _, _ -> }
}

internal data class CinemaStyleAppearance(val style: CinemaStyle, val backgrounds: Map<CinemaStyle, Int>) {
    val backgroundArgb: Int get() = backgrounds.getValue(style)
}

/** Style colors contain no account or playback data. The legacy color belongs to CINEMA only. */
internal class CinemaAppearancePreferences(val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("cinema_appearance", Context.MODE_PRIVATE))

    init {
        if (!preferences.contains(colorKey(CinemaStyle.CINEMA)) && preferences.contains(LEGACY_COLOR)) {
            preferences.edit().putInt(colorKey(CinemaStyle.CINEMA),
                readInt(LEGACY_COLOR, CinemaStyle.CINEMA.defaultBackgroundArgb)).apply()
        }
    }

    fun read(): CinemaStyleAppearance {
        val style = CinemaStyle.fromKey(runCatching { preferences.getString(STYLE_KEY, null) }.getOrNull())
        val colors = CinemaStyle.entries.associateWith { candidate ->
            val fallback = if (candidate == CinemaStyle.CINEMA)
                readInt(LEGACY_COLOR, candidate.defaultBackgroundArgb) else candidate.defaultBackgroundArgb
            readInt(colorKey(candidate), fallback)
        }
        return CinemaStyleAppearance(style, colors)
    }

    fun apply(style: CinemaStyle, colors: Map<CinemaStyle, Int>) {
        preferences.edit().apply {
            putString(STYLE_KEY, style.key)
            for ((candidate, color) in colors) {
                val opaque = color or 0xFF000000.toInt()
                putInt(colorKey(candidate), opaque)
                if (candidate == CinemaStyle.CINEMA) putInt(LEGACY_COLOR, opaque)
            }
        }.apply()
    }

    /** Read the active style at invocation time so older color-only callbacks remain safe. */
    fun saveCurrentBackground(color: Int) {
        val style = read().style
        apply(style, mapOf(style to color))
    }

    fun ownsKey(key: String?): Boolean = key == STYLE_KEY || key == LEGACY_COLOR ||
        CinemaStyle.entries.any { colorKey(it) == key }

    private fun readInt(key: String, default: Int): Int =
        runCatching { preferences.getInt(key, default) }.getOrDefault(default) or 0xFF000000.toInt()

    private fun colorKey(style: CinemaStyle) = "background_argb_${style.key}"

    private companion object {
        const val STYLE_KEY = "style_key"
        const val LEGACY_COLOR = "background_argb"
    }
}
