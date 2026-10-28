package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.ApiException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

internal enum class PlaybackProgressFailureStage { POSITION, COMPLETION }

/** No URL, response body, credentials, title, exception message, or stack trace is retained. */
internal data class PlaybackProgressFailure(
    val stage: PlaybackProgressFailureStage,
    val httpStatus: Int?,
    val exceptionType: String,
)

internal fun playbackProgressFailure(stage: PlaybackProgressFailureStage, error: Exception): PlaybackProgressFailure {
    val cause = (error as? ApiException)?.cause as? Exception ?: error
    val type = when (cause) {
        is SocketTimeoutException -> "SocketTimeoutException"
        is SSLException -> "SSLException"
        is IOException -> "IOException"
        is ApiException -> "ApiException"
        else -> "OtherException"
    }
    return PlaybackProgressFailure(stage, (error as? ApiException)?.status?.takeIf { it in 100..599 }, type)
}

internal fun playbackProgressFailureMessage(positionFailed: Boolean, completionFailed: Boolean): String = when {
    positionFailed && completionFailed -> "재생 위치와 시청 완료 표시를 서버에 저장하지 못했습니다."
    positionFailed -> "재생 위치를 서버에 저장하지 못했습니다."
    completionFailed -> "시청 완료 표시를 서버에 저장하지 못했습니다."
    else -> "시청 상태를 서버에 저장하지 못했습니다."
}
