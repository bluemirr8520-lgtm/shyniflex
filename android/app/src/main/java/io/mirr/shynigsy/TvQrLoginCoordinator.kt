package io.mirr.shynigsy

import android.os.SystemClock
import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.LoginOutcome
import io.mirr.shynigsy.data.TvPairingApi
import io.mirr.shynigsy.data.TvPairingClaim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class TvQrLoginPhase { IDLE, CREATING, WAITING, EXPIRED, ERROR }

/** Public presentation data contains no claim secret, credential, or account session. */
data class TvQrLoginState(
    val phase: TvQrLoginPhase = TvQrLoginPhase.IDLE,
    val qrUrl: String? = null,
    val remainingSeconds: Int = 0,
    val message: String? = null,
)

private const val TV_PAIRING_POLL_MS = 2_000L
private const val TV_PAIRING_CREATE_TIMEOUT_MS = 30_000L
private const val TV_PAIRING_EXPIRED_MESSAGE = "QR 코드가 만료되었습니다. 새 QR 코드를 만들어 주세요."

/**
 * One finite relay session, with sequential polling and no automatic creation/redeem retry.
 * The caller supplies the verified phone-approval route and owns the final login generation guard.
 */
internal suspend fun awaitTvPairing(
    api: TvPairingApi,
    deviceName: String,
    qrUrlForNonce: (String) -> String,
    onState: (TvQrLoginState) -> Unit,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    pause: suspend (Long) -> Unit = { delay(it) },
): LoginOutcome.Success {
    onState(TvQrLoginState(phase = TvQrLoginPhase.CREATING))
    try {
        // Count the creation request against lifetime too, rather than extending server expiry.
        val started = nowMs()
        val session = withTimeoutOrNull(TV_PAIRING_CREATE_TIMEOUT_MS) { api.createTvPairingSession() }
            ?: throw ApiException("QR 로그인 요청 시간이 초과되었습니다.", 408)
        currentCoroutineContext().ensureActive()
        val lifetimeMs = session.expiresInSeconds.toLong() * 1_000L
        val deadline = Math.addExact(started, lifetimeMs)
        val qrUrl = qrUrlForNonce(session.nonce)
        require(qrUrl.length in 1..4096 && qrUrl.contains(session.nonce) &&
            qrUrl.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) {
            "QR 로그인 주소가 올바르지 않습니다."
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - nowMs()
            if (remaining <= 0) throw ApiException(TV_PAIRING_EXPIRED_MESSAGE, 410)
            onState(TvQrLoginState(phase = TvQrLoginPhase.WAITING, qrUrl = qrUrl,
                remainingSeconds = ((remaining + 999L) / 1_000L).toInt()))
            pause(minOf(TV_PAIRING_POLL_MS, remaining))
            currentCoroutineContext().ensureActive()
            val claimBudget = deadline - nowMs()
            if (claimBudget <= 0) throw ApiException(TV_PAIRING_EXPIRED_MESSAGE, 410)
            val claim = withTimeoutOrNull(claimBudget) { api.claimTvPairing(session, deviceName) }
                ?: throw ApiException(TV_PAIRING_EXPIRED_MESSAGE, 410)
            currentCoroutineContext().ensureActive()
            if (nowMs() >= deadline) throw ApiException(TV_PAIRING_EXPIRED_MESSAGE, 410)
            when (claim) {
                TvPairingClaim.Pending -> Unit
                is TvPairingClaim.Approved -> return claim.login
            }
        }
    } catch (cancel: CancellationException) {
        // Mode, server, Activity or request-generation cancellation must not publish an error later.
        throw cancel
    } catch (error: Exception) {
        val status = (error as? ApiException)?.status ?: 0
        val message = when (status) {
            410 -> TV_PAIRING_EXPIRED_MESSAGE
            403 -> "서버가 QR 로그인을 허용하지 않습니다. 직접 로그인해 주세요."
            404 -> "이 서버는 QR 로그인을 지원하지 않습니다. 직접 로그인해 주세요."
            else -> "QR 로그인을 완료하지 못했습니다. 새 코드로 다시 시도하거나 직접 로그인하세요."
        }
        onState(TvQrLoginState(phase = if (status == 410) TvQrLoginPhase.EXPIRED else TvQrLoginPhase.ERROR,
            message = message))
        throw ApiException(message, status).apply { initCause(error) }
    }
}
