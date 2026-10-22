package io.mirr.shynigsy.data

import java.util.Locale

internal data class ResolutionFolder(val key: String, val label: String, val versions: List<MediaVersion>)

/** UI folders preserve the authorized version rows and their server preference order. */
internal fun resolutionFolders(versions: List<MediaVersion>): List<ResolutionFolder> =
    versions.groupBy { version -> explicitResolutionHeight(version.resolution) ?: explicitResolutionHeight(version.label) }
        .entries.sortedWith(compareByDescending<Map.Entry<Int?, List<MediaVersion>>> { it.key ?: -1 })
        .map { (height, rows) ->
            ResolutionFolder(height?.let { "${it}p" } ?: "unknown", when (height) {
                4320 -> "8K"
                2160 -> "4K"
                null -> "해상도 미상"
                else -> "${height}p"
            }, rows)
        }

private val ResolutionDimensions = Regex("""(?i)^([1-9][0-9]{1,4})\s*[x×]\s*([1-9][0-9]{1,4})$""")
private val ResolutionHeight = Regex("""(?i)^([1-9][0-9]{1,4})[pi]$""")
private val ResolutionTokens = Regex(
    """(?i)[1-9][0-9]{1,4}\s*[x×]\s*[1-9][0-9]{1,4}|[1-9][0-9]{1,4}[pi]|full\s+hd|wqhd|qhd|fhd|uhd|8k|4k|hd""",
)
private val VideoFileSuffix = Regex("""(?i)\.(mkv|mp4|m4v|avi|mov|webm|wmv|ts|m2ts|mpg|mpeg)(?:$|\s|[·|,)])""")

private fun exactResolutionHeight(value: String): Int? {
    val normalized = value.trim().uppercase(Locale.ROOT)
    when (normalized.replace(" ", "")) {
        "8K" -> return 4320
        "4K", "UHD", "4KUHD", "UHD4K" -> return 2160
        "QHD", "WQHD" -> return 1440
        "FHD", "FULLHD" -> return 1080
        "HD" -> return 720
    }
    ResolutionHeight.matchEntire(normalized)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { height -> height <= 16_384 } }
    val dimensions = ResolutionDimensions.matchEntire(normalized) ?: return null
    val width = dimensions.groupValues[1].toIntOrNull()?.takeIf { it <= 16_384 } ?: return null
    val height = dimensions.groupValues[2].toIntOrNull()?.takeIf { it <= 16_384 } ?: return null
    // Use actual dimensions; a cropped frame is not silently promoted to a guessed grade.
    return minOf(width, height)
}

private fun explicitResolutionHeight(value: String?): Int? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    exactResolutionHeight(text)?.let { return it }
    if ('/' in text || '\\' in text || VideoFileSuffix.containsMatchIn(text)) return null
    fun boundary(index: Int): Boolean = index !in text.indices || text[index].isWhitespace() || text[index] in "·|,()[]"
    val heights = ResolutionTokens.findAll(text).filter {
        boundary(it.range.first - 1) && boundary(it.range.last + 1)
    }.mapNotNull { exactResolutionHeight(it.value) }.distinct().toList()
    return heights.singleOrNull()
}
