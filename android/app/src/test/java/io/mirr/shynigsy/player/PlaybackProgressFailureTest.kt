package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.ApiException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackProgressFailureTest {
    @Test fun saveFailuresHaveDistinctMessages() {
        assertEquals("재생 위치를 서버에 저장하지 못했습니다.", playbackProgressFailureMessage(true, false))
        assertEquals("시청 완료 표시를 서버에 저장하지 못했습니다.", playbackProgressFailureMessage(false, true))
        assertEquals("재생 위치와 시청 완료 표시를 서버에 저장하지 못했습니다.", playbackProgressFailureMessage(true, true))
    }

    @Test fun logFieldsKeepOnlySafeStageStatusAndExceptionCategory() {
        val failure = playbackProgressFailure(PlaybackProgressFailureStage.POSITION,
            ApiException("private URL title token", 503))
        assertEquals(PlaybackProgressFailureStage.POSITION, failure.stage)
        assertEquals(503, failure.httpStatus ?: -1)
        assertEquals("ApiException", failure.exceptionType)
        assertFalse(failure.toString().contains("private"))
        val network = ApiException("secret").apply { initCause(SocketTimeoutException("sensitive URL")) }
        val timeout = playbackProgressFailure(PlaybackProgressFailureStage.COMPLETION, network)
        assertNull(timeout.httpStatus)
        assertEquals("SocketTimeoutException", timeout.exceptionType)
        assertFalse(timeout.toString().contains("sensitive"))
    }
}
