package io.mirr.shynigsy

import io.mirr.shynigsy.data.ApiException
import org.junit.Assert.*
import org.junit.Test

class SpoilerUnlockFailureTest {
    @Test fun stalledRejectedAndRateLimitedPinsHaveDifferentRecoveryInstructions() {
        val timeout = spoilerUnlockFailureMessage(ApiException("untrusted", 408), false)
        val rejected = spoilerUnlockFailureMessage(ApiException("untrusted", 403), false)
        val rateLimited = spoilerUnlockFailureMessage(ApiException("untrusted", 429), false)
        val expired = spoilerUnlockFailureMessage(ApiException("untrusted", 401), false)
        assertTrue(timeout.contains("응답이 늦어"))
        assertTrue(rejected.contains("PIN을 확인"))
        assertTrue(rateLimited.contains("잠시 후"))
        assertTrue(expired.contains("다시 로그인"))
        assertEquals(4, setOf(timeout, rejected, rateLimited, expired).size)
    }

    @Test fun serverAndUnexpectedFailuresNeverEchoPinTokenOrResponseDetails() {
        val sensitive = "PIN=1234 token=spc_private account=secret"
        for (error in listOf(ApiException(sensitive, 500), ApiException(sensitive, 0), IllegalStateException(sensitive))) {
            val displayed = spoilerUnlockFailureMessage(error, false)
            assertFalse(displayed.contains(sensitive))
            assertFalse(displayed.contains("1234"))
            assertFalse(displayed.contains("spc_"))
            assertFalse(displayed.contains("secret"))
            assertTrue(displayed.contains("다시 시도"))
        }
    }

    @Test fun successfulUnlockWithFailedCatalogDoesNotAskForThePinAgain() {
        val displayed = spoilerUnlockFailureMessage(ApiException("untrusted", 403), true)
        assertTrue(displayed.contains("목록"))
        assertTrue(displayed.contains("새로고침"))
        assertFalse(displayed.contains("PIN"))
    }
}
