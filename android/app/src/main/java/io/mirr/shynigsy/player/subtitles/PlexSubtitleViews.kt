package io.mirr.shynigsy.player.subtitles

// Adapted from the user's Plex Play Universal 1.0.44 subtitle implementation.
// See PLEX_SUBTITLE_NOTICES.md for source provenance and scope.

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.media3.ui.CaptionStyleCompat
import kotlin.math.roundToInt

private const val SubtitleLineSpacingMultiplier = 1.24f

private fun subtitleEdgePadding(edgeType: Int, width: Float): Float = when (edgeType) {
    CaptionStyleCompat.EDGE_TYPE_OUTLINE -> width / 2f + 1f
    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> width * 1.55f + 1f
    else -> 1f
}

private data class HorizontalSubtitleLayoutKey(val text:String,val typeface:Typeface,val textSizePx:Float,val maxTextWidth:Float)

internal class PlexHorizontalSubtitleView(context: Context) : View(context) {
    /** Bounds of the last actual Canvas draw, including outline/background. */
    val subtitleBounds = RectF()
    var subtitleText: String = ""
        set(value) {
            if (field == value) return
            field = value
            subtitleBounds.setEmpty()
            paragraphs = value.toSubtitleParagraphs()
            requestLayout()
            invalidate()
        }
    var subtitleTypeface: Typeface = Typeface.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var subtitleTextFraction: Float = .0533f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    var subtitleFillColor: Int = android.graphics.Color.WHITE
    var subtitleBackgroundColor: Int = android.graphics.Color.TRANSPARENT
    var subtitleEdgeType: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE
    var subtitleEdgeColor: Int = android.graphics.Color.BLACK
    var outlineStrokeWidth: Float = 4f
    var horizontalOffsetPercent: Int = 0
    var verticalOffsetPercent: Int = 0
    var lineHeightMultiplier: Float = SubtitleLineSpacingMultiplier

    private var paragraphs: List<String> = emptyList()
    private var wrappedLayoutKey: HorizontalSubtitleLayoutKey? = null
    private var wrappedLines: List<String> = emptyList()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()
    private val drawBounds = RectF()
    private val backgroundBounds = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        subtitleBounds.setEmpty()
        val textSizePx = (height * subtitleTextFraction).coerceAtLeast(18f)
        configurePaints(textSizePx)
        val horizontalPadding = textSizePx * .34f
        val verticalPadding = textSizePx * .18f
        val maxTextWidth =
            (
                width -
                    paddingLeft -
                    paddingRight -
                    horizontalPadding * 2f
                ).coerceAtLeast(1f)
        val layoutKey = HorizontalSubtitleLayoutKey(subtitleText, subtitleTypeface, textSizePx, maxTextWidth)
        if (wrappedLayoutKey != layoutKey) {
            wrappedLines = paragraphs.flatMap { it.wrapHorizontalSubtitleLine(fillPaint, maxTextWidth) }
            wrappedLayoutKey = layoutKey
        }
        val lines = wrappedLines
        if (lines.isEmpty()) return
        val fontMetrics = fillPaint.fontMetrics
        val lineHeight = textSizePx * lineHeightMultiplier
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val contentHeight = textHeight + lineHeight * (lines.size - 1)
        val centerX = width / 2f + width * horizontalOffsetPercent / 100f
        val offsetY = height * verticalOffsetPercent / 100f
        val top = height - paddingBottom - contentHeight + offsetY
        var baseline = top - fontMetrics.ascent
        lines.forEach { line ->
            val textWidth = fillPaint.measureText(line)
            val edgePadding = subtitleEdgePadding(subtitleEdgeType, outlineStrokeWidth)
            val previousAlign = fillPaint.textAlign
            fillPaint.textAlign = Paint.Align.LEFT
            fillPaint.getTextBounds(line, 0, line.length, textBounds)
            fillPaint.textAlign = previousAlign
            if (!textBounds.isEmpty) {
                val left = centerX - textWidth / 2f
                drawBounds.set(left + textBounds.left, baseline + textBounds.top,
                    left + textBounds.right, baseline + textBounds.bottom)
                drawBounds.inset(-edgePadding, -edgePadding)
                subtitleBounds.union(drawBounds)
            }
            if (android.graphics.Color.alpha(subtitleBackgroundColor) > 0) {
                backgroundBounds.set(centerX - textWidth / 2f - horizontalPadding,
                    baseline + fontMetrics.ascent - verticalPadding,
                    centerX + textWidth / 2f + horizontalPadding,
                    baseline + fontMetrics.descent + verticalPadding)
                subtitleBounds.union(backgroundBounds)
                canvas.drawRoundRect(backgroundBounds,
                    textSizePx * .12f,
                    textSizePx * .12f,
                    backgroundPaint,
                )
            }
            if (subtitleEdgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
                canvas.drawText(line, centerX, baseline, edgePaint)
            }
            canvas.drawText(line, centerX, baseline, fillPaint)
            baseline += lineHeight
        }
    }

    private fun configurePaints(textSizePx: Float) {
        fillPaint.apply {
            style = Paint.Style.FILL
            color = subtitleFillColor
            typeface = subtitleTypeface
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            if (subtitleEdgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
                setShadowLayer(
                    outlineStrokeWidth,
                    outlineStrokeWidth * .55f,
                    outlineStrokeWidth * .55f,
                    subtitleEdgeColor,
                )
            } else {
                clearShadowLayer()
            }
        }
        edgePaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = outlineStrokeWidth
            color = subtitleEdgeColor
            typeface = subtitleTypeface
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            clearShadowLayer()
        }
        backgroundPaint.apply {
            style = Paint.Style.FILL
            color = subtitleBackgroundColor
            clearShadowLayer()
        }
    }
}

internal class PlexVerticalSubtitleView(context: Context) : View(context) {
    /** Reused union of the rotated glyphs and column backgrounds actually drawn. */
    val subtitleBounds = RectF()
    var subtitleText: String = ""
        set(value) {
            if (field == value) return
            field = value
            subtitleBounds.setEmpty()
            sourceColumns = value.toVerticalSubtitleColumns()
            measuredColumns = emptyList()
            requestLayout()
            invalidate()
        }
    var subtitleTypeface: Typeface = Typeface.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            configurePaints()
            requestLayout()
            invalidate()
        }
    var subtitleTextSizePx: Float = 30f
        set(value) {
            if (field == value) return
            field = value
            configurePaints()
            requestLayout()
            invalidate()
        }
    var subtitleFillColor: Int = android.graphics.Color.WHITE
    var subtitleBackgroundColor: Int = android.graphics.Color.TRANSPARENT
    var subtitleEdgeType: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE
    var subtitleEdgeColor: Int = android.graphics.Color.BLACK
    var outlineStrokeWidth: Float = 4f
    var columnLineSpacingMultiplier: Float = SubtitleLineSpacingMultiplier
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    var glyphAdvancePx: Float = 28.5f
        set(value) {
            if (field == value) return
            field = value.coerceAtLeast(1f)
            measuredColumns = emptyList()
            requestLayout()
            invalidate()
        }
    var horizontalOffsetPercent: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var verticalOffsetPercent: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var sourceColumns: List<List<VerticalSubtitleGlyph>> = emptyList()
    private var measuredColumns: List<List<VerticalSubtitleGlyph>> = emptyList()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphBounds = Rect()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawBounds = RectF()

    init {
        setWillNotDraw(false)
        configurePaints()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        configurePaints()
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec)
            .takeIf {
                View.MeasureSpec.getMode(heightMeasureSpec) !=
                    View.MeasureSpec.UNSPECIFIED
            }
            ?: Int.MAX_VALUE
        val maxColumnAdvance = maxVerticalColumnAdvance(availableHeight)
        val columns = sourceColumns.wrapVerticalSubtitleColumns(maxColumnAdvance)
        measuredColumns = columns
        val columnCount = columns.size.coerceAtLeast(1)
        val measuredColumnAdvance = columns.maxOfOrNull { it.verticalAdvance() } ?: 1f
        val columnWidth = verticalColumnWidth()
        val columnAdvance = verticalColumnAdvance()
        val desiredWidth = paddingLeft + paddingRight +
            columnWidth +
            ((columnCount - 1).coerceAtLeast(0) * columnAdvance)
        val desiredHeight = paddingTop + paddingBottom + measuredColumnAdvance * glyphAdvancePx
        setMeasuredDimension(
            resolveSize(desiredWidth.roundToInt(), widthMeasureSpec),
            resolveSize(desiredHeight.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        subtitleBounds.setEmpty()
        configurePaints()
        val columns = measuredColumns
        if (columns.isEmpty()) return
        val columnWidth = verticalColumnWidth()
        val columnAdvance = verticalColumnAdvance()
        val availableTop = paddingTop.toFloat()
        val availableRight = (width - paddingRight).toFloat()
        val availableBottom = (height - paddingBottom).toFloat()
        val offsetX = width * horizontalOffsetPercent / 100f
        val rightEdge = availableRight + offsetX
        val firstColumnX = rightEdge - columnWidth / 2f
        columns.forEachIndexed { columnIndex, glyphs ->
            val x = firstColumnX - columnIndex * columnAdvance
            val contentHeight = glyphs.verticalAdvance() * glyphAdvancePx
            val offsetY = height * verticalOffsetPercent / 100f
            val availableHeight = availableBottom - availableTop
            val top = availableTop +
                (availableHeight - contentHeight) / 2f +
                offsetY
            if (android.graphics.Color.alpha(subtitleBackgroundColor) > 0 && contentHeight > 0f) {
                backgroundPaint.color = subtitleBackgroundColor
                val halfWidth = columnWidth * .46f
                val radius = outlineStrokeWidth.coerceAtLeast(1f) * 1.25f
                subtitleBounds.union(x - halfWidth, top, x + halfWidth, top + contentHeight)
                canvas.drawRoundRect(
                    x - halfWidth, top, x + halfWidth, top + contentHeight,
                    radius, radius, backgroundPaint,
                )
            }
            var baseline = top -
                ((fillPaint.ascent() + fillPaint.descent()) / 2f)
            glyphs.forEach { glyph ->
                if (!glyph.spacer) {
                    drawGlyph(canvas, glyph, x, baseline)
                }
                baseline += glyphAdvancePx * glyph.advanceScale
            }
        }
    }

    private fun drawGlyph(
        canvas: android.graphics.Canvas,
        glyph: VerticalSubtitleGlyph,
        x: Float,
        baseline: Float,
    ) {
        val cellCenterY = baseline + (fillPaint.ascent() + fillPaint.descent()) / 2f
        val drawPosition = if (glyph.centerInCell) {
            centeredGlyphPosition(glyph.text, x, cellCenterY)
        } else {
            x to baseline
        }
        val drawX = drawPosition.first
        val drawBaseline = drawPosition.second
        val previousFillAlign = fillPaint.textAlign
        val previousEdgeAlign = edgePaint.textAlign
        fillPaint.textAlign = Paint.Align.LEFT
        fillPaint.getTextBounds(glyph.text, 0, glyph.text.length, glyphBounds)
        if (!glyphBounds.isEmpty) {
            val left = if (glyph.centerInCell) drawX else drawX - fillPaint.measureText(glyph.text) / 2f
            val rawLeft = left + glyphBounds.left
            val rawTop = drawBaseline + glyphBounds.top
            val rawRight = left + glyphBounds.right
            val rawBottom = drawBaseline + glyphBounds.bottom
            if (glyph.rotate) {
                drawBounds.set(x - (rawBottom - cellCenterY), cellCenterY + (rawLeft - x),
                    x - (rawTop - cellCenterY), cellCenterY + (rawRight - x))
            } else drawBounds.set(rawLeft, rawTop, rawRight, rawBottom)
            val edgePadding = subtitleEdgePadding(subtitleEdgeType, outlineStrokeWidth)
            drawBounds.inset(-edgePadding, -edgePadding)
            subtitleBounds.union(drawBounds)
        }
        fillPaint.textAlign = previousFillAlign
        if (glyph.centerInCell) {
            fillPaint.textAlign = Paint.Align.LEFT
            edgePaint.textAlign = Paint.Align.LEFT
        }
        if (glyph.rotate) {
            canvas.save()
            canvas.rotate(90f, x, cellCenterY)
        }
        if (subtitleEdgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
            canvas.drawText(glyph.text, drawX, drawBaseline, edgePaint)
        }
        canvas.drawText(glyph.text, drawX, drawBaseline, fillPaint)
        if (glyph.rotate) {
            canvas.restore()
        }
        if (glyph.centerInCell) {
            fillPaint.textAlign = previousFillAlign
            edgePaint.textAlign = previousEdgeAlign
        }
    }

    private fun configurePaints() {
        fillPaint.apply {
            style = Paint.Style.FILL
            color = subtitleFillColor
            typeface = subtitleTypeface
            textSize = subtitleTextSizePx
            textAlign = Paint.Align.CENTER
            if (subtitleEdgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
                setShadowLayer(
                    outlineStrokeWidth,
                    outlineStrokeWidth * .55f,
                    outlineStrokeWidth * .55f,
                    subtitleEdgeColor,
                )
            } else {
                clearShadowLayer()
            }
        }
        edgePaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = outlineStrokeWidth
            color = subtitleEdgeColor
            typeface = subtitleTypeface
            textSize = subtitleTextSizePx
            textAlign = Paint.Align.CENTER
            clearShadowLayer()
        }
    }

    private fun maxVerticalColumnAdvance(availableHeightPx: Int): Float {
        val contentHeight = if (availableHeightPx == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            (availableHeightPx - paddingTop - paddingBottom).coerceAtLeast(1)
        }
        return (contentHeight / glyphAdvancePx)
            .coerceAtLeast(4f)
    }

    private fun verticalColumnWidth(): Float =
        subtitleTextSizePx * 1.9f + outlineStrokeWidth * 2.2f

    private fun verticalColumnAdvance(): Float =
        subtitleTextSizePx * columnLineSpacingMultiplier + outlineStrokeWidth * 1.4f

    private fun centeredGlyphPosition(
        text: String,
        cellCenterX: Float,
        cellCenterY: Float,
    ): Pair<Float, Float> {
        val previousAlign = fillPaint.textAlign
        fillPaint.textAlign = Paint.Align.LEFT
        fillPaint.getTextBounds(text, 0, text.length, glyphBounds)
        fillPaint.textAlign = previousAlign
        if (glyphBounds.isEmpty) {
            return cellCenterX to
                (cellCenterY - (fillPaint.ascent() + fillPaint.descent()) / 2f)
        }
        return (cellCenterX - (glyphBounds.left + glyphBounds.right) / 2f) to
            (cellCenterY - (glyphBounds.top + glyphBounds.bottom) / 2f)
    }
}

private fun String.toSubtitleParagraphs(): List<String> =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

private fun String.wrapHorizontalSubtitleLine(
    paint: Paint,
    maxWidthPx: Float,
): List<String> {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return emptyList()
    if (paint.measureText(normalized) <= maxWidthPx) return listOf(normalized)

    val lines = mutableListOf<String>()
    var current = ""
    normalized.codePoints().toArray().forEach { codePoint ->
        val token = String(Character.toChars(codePoint))
        val candidate = current + token
        if (current.isEmpty() || paint.measureText(candidate) <= maxWidthPx) {
            current = candidate
            return@forEach
        }

        val breakIndex = current.trimEnd().lastIndexOf(' ')
        if (breakIndex > 0) {
            lines += current.substring(0, breakIndex).trimEnd()
            current = current.substring(breakIndex).trimStart() + token
        } else {
            lines += current.trimEnd()
            current = token.trimStart()
        }
    }
    current.trim().takeIf { it.isNotBlank() }?.let { lines += it }
    return lines
}
