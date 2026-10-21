package io.mirr.shynigsy.player.subtitles

// Adapted from the user's Plex Play Universal 1.0.44 subtitle implementation.
// See PLEX_SUBTITLE_NOTICES.md for source provenance and scope.

/**
 * A vertical text cell. Latin letters and digits remain upright; punctuation may
 * use a vertical presentation form or rotate within the cell.
 */
internal data class VerticalSubtitleGlyph(
    val text: String,
    val rotate: Boolean = false,
    val spacer: Boolean = false,
    val advanceScale: Float = 1f,
    val centerInCell: Boolean = false,
)

private const val VERTICAL_WORD_SPACE = .22f
private const val MIN_COLUMN_ADVANCE = 4f

private class VerticalQuoteState {
    var doubleOpening = true
    var singleOpening = true
}

/** Source line breaks create columns; blank lines separate independent cues. */
internal fun String.toVerticalSubtitleColumns(): List<List<VerticalSubtitleGlyph>> {
    var state = VerticalQuoteState()
    return buildList {
        lineSequence().forEach { source ->
            if (source.isBlank()) {
                state = VerticalQuoteState()
            } else {
                add(source.trimEnd().verticalCells(state))
            }
        }
    }
}

internal fun String.toVerticalSubtitleGlyphs(): List<VerticalSubtitleGlyph> =
    verticalCells(VerticalQuoteState())

private fun String.verticalCells(state: VerticalQuoteState): List<VerticalSubtitleGlyph> {
    val points = codePoints().toArray()
    val cells = mutableListOf<VerticalSubtitleGlyph>()
    var cursor = 0
    while (cursor < points.size) {
        val point = points[cursor]
        if (Character.isWhitespace(point)) {
            cells += wordSpace()
            cursor++
            continue
        }
        if (point in ELLIPSIS_POINTS) {
            val start = cursor++
            while (cursor < points.size && points[cursor] in ELLIPSIS_POINTS) cursor++
            cells += if (cursor - start > 1 || point == '…'.code) {
                VerticalSubtitleGlyph("︙", advanceScale = .82f, centerInCell = true)
            } else {
                VerticalSubtitleGlyph(verticalForm(point) ?: point.text(), centerInCell = true)
            }
            continue
        }
        val latin = latinPoint(point)
        if (latin != null) {
            // A decomposed accent or an in-word apostrophe stays with its base.
            val cluster = StringBuilder(latin.text())
            cursor++
            while (cursor < points.size && isMark(points[cursor])) {
                cluster.append(points[cursor++].text())
            }
            if (cursor + 1 < points.size &&
                isApostrophe(points[cursor]) && latinPoint(points[cursor + 1]) != null
            ) {
                cluster.append(points[cursor++].text())
            }
            cells += VerticalSubtitleGlyph(cluster.toString(), centerInCell = true)
            continue
        }
        if (point == '"'.code || (point == '\''.code && !points.isWordApostrophe(cursor))) {
            val doubleQuote = point == '"'.code
            val expected = if (doubleQuote) state.doubleOpening else state.singleOpening
            val opening = points.quoteOpensAt(cursor, expected)
            val shape = if (doubleQuote) {
                if (opening) "﹁" else "﹂"
            } else {
                if (opening) "﹃" else "﹄"
            }
            cells += VerticalSubtitleGlyph(shape, centerInCell = true)
            if (doubleQuote) state.doubleOpening = !opening else state.singleOpening = !opening
            cursor++
            continue
        }
        val digit = when (point) {
            in '0'.code..'9'.code -> point
            in '０'.code..'９'.code -> point - '０'.code + '0'.code
            else -> null
        }
        val form = verticalForm(point)
        cells += when {
            digit != null -> VerticalSubtitleGlyph(digit.text(), centerInCell = true)
            form != null -> VerticalSubtitleGlyph(form, centerInCell = true)
            point in ROTATED_SYMBOLS -> VerticalSubtitleGlyph(
                point.text(), rotate = true, advanceScale = .84f, centerInCell = true,
            )
            else -> VerticalSubtitleGlyph(point.text())
        }
        cursor++
    }
    return cells
}

internal fun List<List<VerticalSubtitleGlyph>>.wrapVerticalSubtitleColumns(
    maxColumnAdvance: Float,
): List<List<VerticalSubtitleGlyph>> =
    flatMap { it.wrapVerticalSubtitleColumn(maxColumnAdvance) }

/** Prefer whitespace boundaries, splitting an overlong word only when needed. */
internal fun List<VerticalSubtitleGlyph>.wrapVerticalSubtitleColumn(
    maxColumnAdvance: Float,
): List<List<VerticalSubtitleGlyph>> {
    val limit = maxColumnAdvance.coerceAtLeast(MIN_COLUMN_ADVANCE)
    val output = mutableListOf<List<VerticalSubtitleGlyph>>()
    var column = mutableListOf<VerticalSubtitleGlyph>()
    var used = 0f
    fun finish() {
        if (column.isNotEmpty()) output += column.toList()
        column = mutableListOf()
        used = 0f
    }
    toVerticalSubtitleWords().forEach { word ->
        val advance = word.verticalAdvance()
        if (advance > limit) {
            finish()
            output += word.wrapLongVerticalSubtitleWord(limit)
        } else {
            val gap = if (column.isEmpty()) 0f else VERTICAL_WORD_SPACE
            if (column.isNotEmpty() && used + gap + advance > limit) finish()
            if (column.isNotEmpty()) {
                column += wordSpace()
                used += VERTICAL_WORD_SPACE
            }
            column.addAll(word)
            used += advance
        }
    }
    finish()
    return output
}

internal fun List<VerticalSubtitleGlyph>.toVerticalSubtitleWords(): List<List<VerticalSubtitleGlyph>> {
    val words = mutableListOf<List<VerticalSubtitleGlyph>>()
    var start = 0
    while (start < size) {
        if (this[start].spacer) {
            start++
            continue
        }
        var end = start + 1
        while (end < size && !this[end].spacer) end++
        words += subList(start, end).toList()
        start = end
    }
    return words
}

internal fun List<VerticalSubtitleGlyph>.wrapLongVerticalSubtitleWord(
    maxColumnAdvance: Float,
): List<List<VerticalSubtitleGlyph>> {
    val limit = maxColumnAdvance.coerceAtLeast(MIN_COLUMN_ADVANCE)
    val cells = this
    return buildList {
        var start = 0
        while (start < cells.size) {
            var end = start
            var used = 0f
            while (end < cells.size && (end == start || used + cells[end].advanceScale <= limit)) {
                used += cells[end++].advanceScale
            }
            val fittedEnd = end
            if (end < cells.size) {
                // Avoid ending a column with an opener or starting one with a
                // closer. Keep the punctuation's role from tokenization.
                while (end > start &&
                    (cells[end - 1].text in OPENING_FORMS || cells[end].text in CLOSING_FORMS)
                ) {
                    end--
                }
                // A run consisting only of punctuation must still make progress.
                if (end == start) end = fittedEnd
            }
            add(cells.subList(start, end).toList())
            start = end
        }
    }
}

internal fun List<VerticalSubtitleGlyph>.verticalAdvance(): Float =
    sumOf { it.advanceScale.toDouble() }.toFloat()

internal fun List<VerticalSubtitleGlyph>.verticalWrapAdvance(): Float = verticalAdvance()

internal fun VerticalSubtitleGlyph.verticalWrapAdvance(): Float = advanceScale

private fun wordSpace() =
    VerticalSubtitleGlyph(" ", spacer = true, advanceScale = VERTICAL_WORD_SPACE)

private fun Int.text(): String = String(Character.toChars(this))

private fun latinPoint(point: Int): Int? {
    val normalized = when (point) {
        in 'Ａ'.code..'Ｚ'.code -> point - 'Ａ'.code + 'A'.code
        in 'ａ'.code..'ｚ'.code -> point - 'ａ'.code + 'a'.code
        else -> point
    }
    return normalized.takeIf {
        Character.isLetter(it) && Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
    }
}

private fun isMark(point: Int): Boolean =
    Character.getType(point) in MARK_TYPES

private fun isApostrophe(point: Int): Boolean = point == '\''.code || point == '’'.code

private fun IntArray.isWordApostrophe(index: Int): Boolean =
    index > 0 && index < lastIndex &&
        Character.isLetterOrDigit(this[index - 1]) &&
        Character.isLetterOrDigit(this[index + 1])

private fun IntArray.quoteOpensAt(index: Int, expected: Boolean): Boolean {
    if (!expected) return false
    val before = (index - 1 downTo 0).firstOrNull { !Character.isWhitespace(this[it]) }?.let { this[it] }
    val after = (index + 1..lastIndex).firstOrNull { !Character.isWhitespace(this[it]) }?.let { this[it] }
    if (before == null || before.isOpeningBoundary()) return true
    if (after == null || after.isClosingBoundary()) return false
    return true
}

private fun Int.isOpeningBoundary(): Boolean =
    Character.getType(this) == Character.START_PUNCTUATION.toInt() ||
        Character.getType(this) == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
        this in SENTENCE_BOUNDARIES || this in setOf('—'.code, '―'.code, '-'.code, '－'.code)

private fun Int.isClosingBoundary(): Boolean =
    Character.getType(this) == Character.END_PUNCTUATION.toInt() ||
        Character.getType(this) == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
        this in SENTENCE_BOUNDARIES

private val MARK_TYPES = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
)
private val ELLIPSIS_POINTS = setOf('.'.code, '．'.code, '·'.code, '•'.code, '…'.code)
private val SENTENCE_BOUNDARIES = ",，、.．。:：;；!！?？".codePoints().toArray().toSet()
private val ROTATED_SYMBOLS = "/\\|@#$%^&*+=".codePoints().toArray().toSet()
private val OPENING_FORMS = setOf("︵", "︷", "︹", "︻", "︽", "︿", "﹁", "﹃", "﹇", "︗")
private val CLOSING_FORMS = setOf("︶", "︸", "︺", "︼", "︾", "﹀", "﹂", "﹄", "﹈", "︘")

private fun verticalForm(point: Int): String? = when (point) {
    ','.code, '，'.code -> "︐"
    '、'.code, '､'.code -> "︑"
    '.'.code, '．'.code, '。'.code -> "︒"
    ':'.code, '：'.code -> "︓"
    ';'.code, '；'.code -> "︔"
    '!'.code, '！'.code -> "︕"
    '?'.code, '？'.code -> "︖"
    '('.code, '（'.code -> "︵"
    ')'.code, '）'.code -> "︶"
    '{'.code, '｛'.code -> "︷"
    '}'.code, '｝'.code -> "︸"
    '〔'.code -> "︹"
    '〕'.code -> "︺"
    '【'.code -> "︻"
    '】'.code -> "︼"
    '《'.code -> "︽"
    '》'.code -> "︾"
    '〈'.code, '<'.code, '＜'.code -> "︿"
    '〉'.code, '>'.code, '＞'.code -> "﹀"
    '「'.code, '“'.code, '‘'.code, '｢'.code -> "﹁"
    '」'.code, '”'.code, '’'.code, '｣'.code -> "﹂"
    '『'.code, '〝'.code -> "﹃"
    '』'.code, '〟'.code -> "﹄"
    '['.code, '［'.code -> "﹇"
    ']'.code, '］'.code -> "﹈"
    '〖'.code -> "︗"
    '〗'.code -> "︘"
    '—'.code, '―'.code -> "︱"
    '-'.code, '－'.code, '–'.code -> "︲"
    '_'.code, '＿'.code -> "︳"
    '〜'.code, '～'.code, '~'.code -> "︴"
    else -> null
}
