package io.mirr.shynigsy.player.subtitles

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

/** The same user-controlled typography and placement as Plex Play Universal. */
internal data class PlexSubtitleAppearance(
    val sizePercent: Int = 100,
    val horizontalOffsetPercent: Int = 0,
    val verticalOffsetPercent: Int = 0,
    val verticalWriting: Boolean = false,
    val foregroundColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.TRANSPARENT,
    val edgeType: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    val edgeColor: Int = Color.BLACK,
    val verticalSpacingPercent: Int = 100,
)

internal object PlexSubtitleFonts {
    val keys = listOf("system")
    val labels = listOf("시스템 기본")

    fun options(context: Context): List<SubtitleFontEntry> =
        keys.zip(labels) { key, label -> SubtitleFontEntry(key, label) } + SubtitleFontStore(context).list()

    fun normalizedKey(context: Context, key: String?): String {
        if (key == null || key == "system") return "system"
        return if (SubtitleFontStore(context).resolve(key) != null) key else "system"
    }

    fun typeface(context: Context, key: String): Typeface =
        SubtitleFontStore(context).resolve(key) ?: Typeface.DEFAULT
}

/** Apply Plex typography to embedded Media3 cues without changing renderer ownership. */
@UnstableApi
internal fun applyPlexSubtitleStyle(
    subtitleView: SubtitleView,
    appearance: PlexSubtitleAppearance,
    typeface: Typeface,
) {
    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.setApplyEmbeddedFontSizes(false)
    subtitleView.setFractionalTextSize(.0533f * appearance.sizePercent.coerceIn(50, 200) / 100f)
    subtitleView.setStyle(CaptionStyleCompat(appearance.foregroundColor, appearance.backgroundColor,
        Color.TRANSPARENT, appearance.edgeType, appearance.edgeColor, typeface))
    subtitleView.post {
        subtitleView.rotation = 0f
        subtitleView.translationX = subtitleView.width * appearance.horizontalOffsetPercent.coerceIn(-100, 100) / 100f
        subtitleView.translationY = subtitleView.height * appearance.verticalOffsetPercent.coerceIn(-100, 100) / 100f
        subtitleView.invalidate()
    }
}

/** Native adapter around the original Plex horizontal and vertical Canvas views. */
@UnstableApi
internal class PlexSubtitleOverlayView(context: Context) : FrameLayout(context) {
    private val horizontal = PlexHorizontalSubtitleView(context)
    private val vertical = PlexVerticalSubtitleView(context)

    var subtitleText: String = ""
        set(value) { field = value; horizontal.subtitleText = value; vertical.subtitleText = value }
    var subtitleTypeface: Typeface = Typeface.DEFAULT
        set(value) { field = value; refreshStyle() }
    var appearance: PlexSubtitleAppearance = PlexSubtitleAppearance()
        set(value) { field = value; refreshStyle() }

    /** Snapshot in overlay coordinates, suitable for a drag gesture's hit test. */
    fun subtitleBounds(): RectF {
        val selected = if (appearance.verticalWriting) vertical else horizontal
        val bounds = RectF(if (appearance.verticalWriting) vertical.subtitleBounds else horizontal.subtitleBounds)
        if (!bounds.isEmpty) bounds.offset(selected.left + selected.translationX, selected.top + selected.translationY)
        return bounds
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(horizontal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(vertical, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        horizontal.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        vertical.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        refreshStyle()
    }

    private fun refreshStyle() {
        val density = resources.displayMetrics.density
        val scale = appearance.sizePercent.coerceIn(50, 200) / 100f
        val x = appearance.horizontalOffsetPercent.coerceIn(-100, 100)
        val y = appearance.verticalOffsetPercent.coerceIn(-100, 100)
        horizontal.apply {
            visibility = if (appearance.verticalWriting) View.GONE else View.VISIBLE
            subtitleTypeface = this@PlexSubtitleOverlayView.subtitleTypeface
            subtitleTextFraction = .0533f * scale
            subtitleFillColor = appearance.foregroundColor
            subtitleBackgroundColor = appearance.backgroundColor
            subtitleEdgeType = appearance.edgeType
            subtitleEdgeColor = appearance.edgeColor
            outlineStrokeWidth = 3.5f * density
            horizontalOffsetPercent = x; verticalOffsetPercent = y
            setPadding((24 * density).roundToInt(), (24 * density).roundToInt(),
                (24 * density).roundToInt(), (72 * density).roundToInt())
            invalidate()
        }
        vertical.apply {
            visibility = if (appearance.verticalWriting) View.VISIBLE else View.GONE
            subtitleTypeface = this@PlexSubtitleOverlayView.subtitleTypeface
            subtitleTextSizePx = 30f * density * scale
            glyphAdvancePx = verticalSubtitleGlyphAdvancePx(28.5f * density * scale, appearance.verticalSpacingPercent)
            subtitleFillColor = appearance.foregroundColor
            subtitleBackgroundColor = appearance.backgroundColor
            subtitleEdgeType = appearance.edgeType
            subtitleEdgeColor = appearance.edgeColor
            outlineStrokeWidth = 3.5f * density
            horizontalOffsetPercent = x; verticalOffsetPercent = y
            setPadding((34 * density).roundToInt(), (18 * density).roundToInt(),
                (80 * density).roundToInt(), (18 * density).roundToInt())
            requestLayout(); invalidate()
        }
    }
}
