package io.mirr.shynigsy.player.subtitles

/** DEFAULT preserves the original placement for each writing direction. */
internal enum class SubtitleHorizontalAlignment { DEFAULT, LEFT, CENTER, RIGHT }
internal enum class SubtitleVerticalAlignment { DEFAULT, TOP, CENTER, BOTTOM }

internal fun subtitleHorizontalStart(
    start: Float, end: Float, contentWidth: Float, alignment: SubtitleHorizontalAlignment,
    default: SubtitleHorizontalAlignment, offset: Float,
): Float = when (if (alignment == SubtitleHorizontalAlignment.DEFAULT) default else alignment) {
    SubtitleHorizontalAlignment.LEFT -> start
    SubtitleHorizontalAlignment.RIGHT -> end - contentWidth
    SubtitleHorizontalAlignment.CENTER, SubtitleHorizontalAlignment.DEFAULT -> (start + end - contentWidth) / 2f
} + offset

internal fun subtitleVerticalStart(
    start: Float, end: Float, contentHeight: Float, alignment: SubtitleVerticalAlignment,
    default: SubtitleVerticalAlignment, offset: Float,
): Float = when (if (alignment == SubtitleVerticalAlignment.DEFAULT) default else alignment) {
    SubtitleVerticalAlignment.TOP -> start
    SubtitleVerticalAlignment.BOTTOM -> end - contentHeight
    SubtitleVerticalAlignment.CENTER, SubtitleVerticalAlignment.DEFAULT -> (start + end - contentHeight) / 2f
} + offset
