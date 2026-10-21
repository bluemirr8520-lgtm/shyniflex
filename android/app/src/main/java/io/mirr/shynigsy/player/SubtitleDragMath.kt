package io.mirr.shynigsy.player

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class SubtitleDragBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class SubtitleDragOffset(val x: Int, val y: Int, val translationX: Float, val translationY: Float)

/** Drag previews stay smooth; stored percentages also keep the caption on screen. */
internal object SubtitleDragMath {
    fun move(originX: Int, originY: Int, dx: Float, dy: Float, width: Int, height: Int,
        bounds: SubtitleDragBounds, margin: Float): SubtitleDragOffset {
        fun axis(origin: Int, delta: Float, extent: Int, first: Float, last: Float): Pair<Int, Float> {
            if (extent <= 0 || !delta.isFinite()) return origin to 0f
            val minimum = maxOf(margin - first, (-80 - origin) * extent / 100f)
            val maximum = minOf(extent - margin - last, (80 - origin) * extent / 100f)
            if (minimum > maximum) return origin to 0f
            val lower = maxOf(-80, ceil(origin + minimum / extent * 100f).toInt())
            val upper = minOf(80, floor(origin + maximum / extent * 100f).toInt())
            if (lower > upper) return origin to 0f
            val translation = delta.coerceIn(minimum, maximum)
            val percent = (origin + translation / extent * 100f).roundToInt().coerceIn(lower, upper)
            return percent to translation
        }
        val x = axis(originX, dx, width, bounds.left, bounds.right)
        val y = axis(originY, dy, height, bounds.top, bounds.bottom)
        return SubtitleDragOffset(x.first, y.first, x.second, y.second)
    }
}
