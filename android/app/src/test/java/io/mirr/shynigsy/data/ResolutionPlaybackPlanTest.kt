package io.mirr.shynigsy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionPlaybackPlanTest {
    @Test fun oneFileStartsWithoutAChoice() {
        assertEquals(ResolutionPlaybackPlan(8, false), resolutionPlaybackPlan(listOf(MediaVersion(8, "원본", "1080p")), null))
    }

    @Test fun severalEncodesOfOneResolutionStartTheFirstServerCandidate() {
        val versions = listOf(MediaVersion(90, "선호 파일", "FHD"), MediaVersion(91, "다른 파일", "1920x1080"))
        assertEquals(ResolutionPlaybackPlan(90, false), resolutionPlaybackPlan(versions, null))
        assertEquals(ResolutionPlaybackPlan(90, false), resolutionPlaybackPlan(versions, 91))
    }

    @Test fun unknownResolutionAloneStillStartsWithoutAChoice() {
        val versions = listOf(MediaVersion(5, "original.mkv"), MediaVersion(6, "other.mkv"))
        assertEquals(ResolutionPlaybackPlan(5, false), resolutionPlaybackPlan(versions, null))
    }

    @Test fun twoDistinctResolutionsNeedAnExplicitChoice() {
        val versions = listOf(MediaVersion(2, "FHD", "1080p"), MediaVersion(1, "UHD", "4K"))
        val plan = resolutionPlaybackPlan(versions, null)
        assertTrue(plan.needsChoice)
        assertNull(plan.partId)
        assertEquals(ResolutionPlaybackPlan(2, false), resolutionPlaybackPlan(versions, 2))
    }

    @Test fun aStaleOrInventedPartCannotSkipTheChoice() {
        val versions = listOf(MediaVersion(1, "1080p"), MediaVersion(2, "720p"))
        assertTrue(resolutionPlaybackPlan(versions, 999).needsChoice)
        assertTrue(resolutionPlaybackPlan(versions, -1).needsChoice)
    }

    @Test fun invalidAndDuplicatePartIdsCannotCreateExtraChoices() {
        val versions = listOf(MediaVersion(0, "4K"), MediaVersion(-1, "720p"),
            MediaVersion(5, "1080p"), MediaVersion(5, "4K"))
        assertEquals(ResolutionPlaybackPlan(5, false), resolutionPlaybackPlan(versions, null))
        assertEquals(listOf(5), resolutionFolders(versions).single().versions.map { it.partId })
    }

    @Test fun aServerWithoutVersionsRetainsItsPlaybackDecisionCompatibility() {
        val plan = resolutionPlaybackPlan(emptyList(), 999)
        assertFalse(plan.needsChoice)
        assertNull(plan.partId)
    }
}
