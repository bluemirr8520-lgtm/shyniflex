package io.mirr.shynigsy.player.subtitles

// Adapted from the user's Plex Play Universal 1.0.44 subtitle implementation.
// See PLEX_SUBTITLE_NOTICES.md for source provenance and scope.

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Locale

private const val MaxSubtitleDownloadBytes = 8L * 1024L * 1024L

internal data class ManualSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

internal fun readSubtitleBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (output.size().toLong() <= MaxSubtitleDownloadBytes) {
        val read = try {
            input.read(buffer)
        } catch (timeout: SocketTimeoutException) {
            if (output.size() > 0) break else throw timeout
        } catch (error: IOException) {
            if (output.size() > 0) break else throw error
        }
        if (read < 0) break
        if (output.size().toLong() + read > MaxSubtitleDownloadBytes) return byteArrayOf()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun decodeSubtitleBytes(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        }
        if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
    }
    if (bytes.size >= 4) {
        var evenZeroes = 0
        var oddZeroes = 0
        var index = 0
        while (index < bytes.size) {
            if (bytes[index] == 0.toByte()) evenZeroes++
            if (index + 1 < bytes.size && bytes[index + 1] == 0.toByte()) oddZeroes++
            index += 2
        }
        val pairs = bytes.size / 2
        if (oddZeroes > pairs / 3) return bytes.toString(Charsets.UTF_16LE)
        if (evenZeroes > pairs / 3) return bytes.toString(Charsets.UTF_16BE)
    }
    val utf8 = bytes.toString(Charsets.UTF_8)
    if ('\uFFFD' !in utf8) return utf8
    return runCatching {
        bytes.toString(Charset.forName("MS949"))
    }.getOrDefault(utf8)
}

internal fun List<ManualSubtitleCue>.textAt(positionMs: Long): String =
    asSequence()
        .filter { positionMs >= it.startMs && positionMs < it.endMs }
        .map { it.text }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

internal fun parseManualSubtitleCues(
    body: String,
): List<ManualSubtitleCue> {
    val normalized = body
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    // Plex's embedded-subtitle endpoint can convert ASS/SSA/TTML/SMI to SRT
    // while the metadata still reports the original codec. Detect the actual
    // response body first so converted text is not sent to the wrong parser.
    return when {
        normalized.contains("[Events]", ignoreCase = true) ->
            parseAssSubtitleCues(normalized)
        normalized.contains("<SAMI", ignoreCase = true) ||
            normalized.contains("<SYNC", ignoreCase = true) ->
            parseSamiSubtitleCues(normalized)
        normalized.contains("<tt", ignoreCase = true) &&
            normalized.contains("<p", ignoreCase = true) ->
            parseTtmlSubtitleCues(normalized)
        else -> parseTimedTextSubtitleCues(normalized)
    }
}

private val TtmlParagraphRegex = Regex("""(?is)<p\b([^>]*)>(.*?)</p>""")
private val TtmlTimeAttributeRegex = Regex("""(?i)\b(begin|end|dur)\s*=\s*["']([^"']+)["']""")

private fun parseTtmlSubtitleCues(body: String): List<ManualSubtitleCue> =
    TtmlParagraphRegex.findAll(body).mapNotNull { paragraph ->
        val timing = TtmlTimeAttributeRegex.findAll(paragraph.groupValues[1])
            .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
        val startMs = timing["begin"]?.let(::parseTtmlTimeMs) ?: return@mapNotNull null
        val endMs = timing["end"]?.let(::parseTtmlTimeMs)
            ?: timing["dur"]?.let(::parseTtmlTimeMs)?.let(startMs::plus)
            ?: return@mapNotNull null
        val text = cleanManualSubtitleText(paragraph.groupValues[2])
        if (text.isBlank() || endMs <= startMs) return@mapNotNull null
        ManualSubtitleCue(startMs, endMs, text)
    }.toList()

private fun parseTtmlTimeMs(value: String): Long {
    val normalized = value.trim()
    if (normalized.endsWith("ms", ignoreCase = true)) {
        return normalized.dropLast(2).toDoubleOrNull()?.toLong() ?: 0L
    }
    if (normalized.endsWith("s", ignoreCase = true)) {
        return ((normalized.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000.0).toLong()
    }
    val parts = normalized.split(':')
    if (parts.size != 3) return 0L
    val hours = parts[0].toLongOrNull() ?: 0L
    val minutes = parts[1].toLongOrNull() ?: 0L
    val seconds = parts[2].toDoubleOrNull() ?: 0.0
    return (((hours * 60L + minutes) * 60L) * 1_000L + seconds * 1_000.0).toLong()
}

private val SubtitleTimingRegex = Regex(
    """(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""",
)

private fun parseTimedTextSubtitleCues(body: String): List<ManualSubtitleCue> {
    val normalized = body.replace(Regex("(?m)^WEBVTT.*$"), "")
    val timings = SubtitleTimingRegex.findAll(normalized).toList()
    return timings.mapIndexedNotNull { index, match ->
        val lineEnd = normalized.indexOf('\n', match.range.last + 1)
            .let { if (it < 0) normalized.length else it + 1 }
        val nextTimingStart = timings.getOrNull(index + 1)?.range?.first
            ?: normalized.length
        if (lineEnd > nextTimingStart) return@mapIndexedNotNull null

        val cueLines = normalized.substring(lineEnd, nextTimingStart)
            .lines()
            .toMutableList()
        while (cueLines.lastOrNull()?.isBlank() == true) {
            cueLines.removeAt(cueLines.lastIndex)
        }
        // Broken/hand-authored SRT files often omit the blank line between
        // cues. In that case the next numeric cue identifier belongs to the
        // following timing line, not to the current subtitle text.
        if (
            index + 1 < timings.size &&
            cueLines.lastOrNull()?.trim()?.matches(Regex("\\d+")) == true
        ) {
            cueLines.removeAt(cueLines.lastIndex)
        }
        val text = cleanManualSubtitleText(cueLines.joinToString("\n"))
        if (text.isBlank()) return@mapIndexedNotNull null
        ManualSubtitleCue(
            startMs = match.subtitleTimeMs(1),
            endMs = match.subtitleTimeMs(5),
            text = text,
        )
    }.filter { it.endMs > it.startMs }
}

private fun parseAssSubtitleCues(body: String): List<ManualSubtitleCue> {
    var inEvents = false
    var fields = listOf<String>()
    val cues = mutableListOf<ManualSubtitleCue>()
    body.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("[", ignoreCase = true)) {
            inEvents = line.equals("[Events]", ignoreCase = true)
            return@forEach
        }
        if (!inEvents) return@forEach
        if (line.startsWith("Format:", ignoreCase = true)) {
            fields = line.substringAfter(':')
                .split(',')
                .map { it.trim().lowercase(Locale.ROOT) }
            return@forEach
        }
        if (!line.startsWith("Dialogue:", ignoreCase = true) || fields.isEmpty()) {
            return@forEach
        }
        val values = line.substringAfter(':')
            .split(",", limit = fields.size)
        val startIndex = fields.indexOf("start")
        val endIndex = fields.indexOf("end")
        val textIndex = fields.indexOf("text")
        if (
            startIndex !in values.indices ||
            endIndex !in values.indices ||
            textIndex !in values.indices
        ) {
            return@forEach
        }
        val text = cleanManualSubtitleText(values[textIndex])
        if (text.isBlank()) return@forEach
        val startMs = parseAssTimeMs(values[startIndex])
        val endMs = parseAssTimeMs(values[endIndex])
        if (endMs > startMs) {
            cues += ManualSubtitleCue(startMs, endMs, text)
        }
    }
    return cues
}

private val SamiSyncRegex = Regex(
    """(?is)<sync\s+start\s*=\s*["']?(\d+)["']?[^>]*>(.*?)(?=<sync\s+start|\z)""",
)
private val SamiParagraphRegex = Regex("""(?is)<p\b([^>]*)>(.*?)(?=<p\b|\z)""")

private fun parseSamiSubtitleCues(body: String): List<ManualSubtitleCue> {
    val entries = SamiSyncRegex.findAll(body)
        .mapNotNull { match ->
            val startMs = match.groupValues[1].toLongOrNull()
                ?: return@mapNotNull null
            val text = cleanSamiSubtitleText(match.groupValues[2])
            startMs to text
        }
        .filter { it.second.isNotBlank() }
        .toList()
    return entries.mapIndexed { index, entry ->
        val endMs = entries.getOrNull(index + 1)?.first
            ?.coerceAtLeast(entry.first + 1)
            ?: (entry.first + 4_000L)
        ManualSubtitleCue(entry.first, endMs, entry.second)
    }
}

private fun cleanSamiSubtitleText(body: String): String {
    val paragraphs = SamiParagraphRegex.findAll(body).toList()
    if (paragraphs.isEmpty()) return cleanManualSubtitleText(body)
    val koreanParagraphs = paragraphs.filter { match ->
        val attributes = match.groupValues[1].lowercase(Locale.ROOT)
        attributes.contains("krcc") ||
            attributes.contains("ko") ||
            attributes.contains("kor") ||
            attributes.contains("korean") ||
            attributes.contains("한국")
    }
    val selected = (koreanParagraphs.ifEmpty { paragraphs })
        .joinToString("\n") { it.groupValues[2] }
    return cleanManualSubtitleText(selected)
}

private fun MatchResult.subtitleTimeMs(offset: Int): Long {
    val hours = groups[offset]?.value?.toLongOrNull() ?: 0L
    val minutes = groups[offset + 1]?.value?.toLongOrNull() ?: 0L
    val seconds = groups[offset + 2]?.value?.toLongOrNull() ?: 0L
    val millis = groups[offset + 3]
        ?.value
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0L
    return ((hours * 60L + minutes) * 60L + seconds) * 1_000L + millis
}

private fun parseAssTimeMs(value: String): Long {
    val parts = value.trim().split(':')
    if (parts.size != 3) return 0L
    val secondsParts = parts[2].split('.')
    val hours = parts[0].toLongOrNull() ?: 0L
    val minutes = parts[1].toLongOrNull() ?: 0L
    val seconds = secondsParts.getOrNull(0)?.toLongOrNull() ?: 0L
    val centiseconds = secondsParts.getOrNull(1)
        ?.padEnd(2, '0')
        ?.take(2)
        ?.toLongOrNull()
        ?: 0L
    return ((hours * 60L + minutes) * 60L + seconds) * 1_000L +
        centiseconds * 10L
}

private fun cleanManualSubtitleText(text: String): String =
    text
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ")
        .replace(Regex("""\{\\[^}]*\}"""), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .decodeNumericHtmlEntities()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

private fun String.decodeNumericHtmlEntities(): String =
    replace(Regex("""&#(x[0-9a-fA-F]+|\d+);""")) { match ->
        val value = match.groupValues[1]
        val codePoint =
            if (value.startsWith("x", ignoreCase = true)) {
                value.drop(1).toIntOrNull(16)
            } else {
                value.toIntOrNull()
            }
        codePoint
            ?.takeIf { Character.isValidCodePoint(it) }
            ?.let { String(Character.toChars(it)) }
            ?: match.value
    }
