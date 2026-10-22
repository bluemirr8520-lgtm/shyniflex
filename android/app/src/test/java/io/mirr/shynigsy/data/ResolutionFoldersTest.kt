package io.mirr.shynigsy.data

import org.junit.Assert.*
import org.junit.Test

class ResolutionFoldersTest {
    private fun version(id: Int, resolution: String?, label: String = "원본", metadata: Int = id) =
        MediaVersion(id, label, resolution = resolution, metadataItemId = metadata)

    @Test fun equivalentExplicitQualityMetadataSharesOneCanonicalFolder() {
        val rows = listOf(version(1, "4K"), version(2, "2160p"), version(3, "3840x2160"), version(4, "UHD"))
        val folder = resolutionFolders(rows).single()
        assertEquals("2160p", folder.key)
        assertEquals("4K", folder.label)
        assertEquals(rows, folder.versions)
    }

    @Test fun dimensionsPortraitAndProgressiveOrInterlacedLabelsNormalize() {
        val rows = listOf(version(1, "1920x1080"), version(2, " 1080p "), version(3, "1080i"),
            version(4, "1080 × 1920"), version(5, "Full HD"))
        assertEquals(rows, resolutionFolders(rows).single().versions)
        assertEquals("1080p", resolutionFolders(rows).single().key)
    }

    @Test fun dedicatedMetadataWinsOverContradictoryPresentationLabel() {
        val row = version(1, "720p", "4K · HEVC")
        assertEquals("720p", resolutionFolders(listOf(row)).single().key)
    }

    @Test fun absentMetadataCanUseAnExplicitStandaloneQualityToken() {
        val rows = listOf(version(1, null, "1080p · H.264 · AAC"),
            version(2, null, "HEVC (Full HD)"), version(3, "unknown", "1920 x 1080 | AAC"))
        assertEquals(rows, resolutionFolders(rows).single().versions)
        assertEquals("1080p", resolutionFolders(rows).single().key)
    }

    @Test fun filenamesPartNumbersAmbiguousGradesAndConflictingTokensRemainUnknown() {
        val labels = listOf("Movie.2026.1080p.mkv", "Movie (1080p).mp4", "원본 파일 1080", "file1080p",
            "19201080", "C:\\media\\1080p\\movie.mkv", "1080p · 720p", "2K", "SD")
        val rows = labels.mapIndexed { index, label -> version(index + 1, null, label) }
        val folder = resolutionFolders(rows).single()
        assertEquals("unknown", folder.key)
        assertEquals("해상도 미상", folder.label)
        assertEquals(rows, folder.versions)
    }

    @Test fun foldersSortHighToLowWithUnknownLastWithoutReorderingSourceCandidates() {
        val rows = listOf(version(9, null), version(7, "720p"), version(4, "4K"), version(1, "1080p"),
            version(8, "720p"), version(6, "8K"))
        val groups = resolutionFolders(rows)
        assertEquals(listOf("4320p", "2160p", "1080p", "720p", "unknown"), groups.map { it.key })
        assertEquals(listOf(7, 8), groups.first { it.key == "720p" }.versions.map { it.partId })
    }

    @Test fun groupingRetainsExactObjectsAndBothPhysicalAndMetadataIdentities() {
        val first = version(90, "1080p", metadata = 456)
        val second = version(91, "1080p", metadata = 789)
        val group = resolutionFolders(listOf(first, second)).single()
        assertSame(first, group.versions[0])
        assertSame(second, group.versions[1])
        assertEquals(listOf(90 to 456, 91 to 789), group.versions.map { it.partId to it.metadataItemId })
    }

    @Test fun invalidValuesCannotCreateImaginaryQualityFolders() {
        val rows = listOf("0p", "-1080p", "1920x0", "999999999999p", "99999x1080")
            .mapIndexed { index, resolution -> version(index + 1, resolution) }
        assertEquals("unknown", resolutionFolders(rows).single().key)
        assertTrue(resolutionFolders(emptyList()).isEmpty())
    }

    @Test fun croppedDimensionsRemainActualInsteadOfGuessingAStandardGrade() {
        val folder = resolutionFolders(listOf(version(1, "1920x800"))).single()
        assertEquals("800p", folder.key)
        assertEquals("800p", folder.label)
    }
}
