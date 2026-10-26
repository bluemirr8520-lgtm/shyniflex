package io.mirr.shynigsy

import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.LoginOutcome
import io.mirr.shynigsy.data.TvPairingApi
import io.mirr.shynigsy.data.TvPairingClaim
import io.mirr.shynigsy.data.TvPairingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvQrLoginCoordinatorTest {
    private val nonce = "n".repeat(43)
    private val secret = "s".repeat(43)
    private val login = LoginOutcome.Success("approved-token", "viewer")
    private fun session(seconds: Int = 120) = TvPairingSession(nonce, secret, seconds)
    private fun url(value: String) = "https://synthetic.invalid/#pair=$value"

    private class FixtureApi(
        val create: suspend () -> TvPairingSession,
        val claim: suspend () -> TvPairingClaim,
    ) : TvPairingApi {
        var creations = 0; var claims = 0; var activeClaims = 0; var maximumClaims = 0
        override suspend fun createTvPairingSession(): TvPairingSession { creations++; return create() }
        override suspend fun claimTvPairing(session: TvPairingSession, deviceName: String): TvPairingClaim {
            claims++; activeClaims++; maximumClaims = maxOf(maximumClaims, activeClaims)
            return try { claim() } finally { activeClaims-- }
        }
    }

    @Test fun pendingPollsAreSequentialAndReturnOnlyTheApprovedAccount() { runBlocking {
        var now = 0L; var responses = 0
        val states = mutableListOf<TvQrLoginState>(); val pauses = mutableListOf<Long>()
        val api = FixtureApi({ session() }, { if (++responses < 3) TvPairingClaim.Pending else TvPairingClaim.Approved(login) })
        val result = awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { now }, pause = { pauses += it; now += it })
        assertEquals(login, result); assertEquals(1, api.creations); assertEquals(3, api.claims); assertEquals(1, api.maximumClaims)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), pauses)
        assertEquals(listOf(TvQrLoginPhase.CREATING, TvQrLoginPhase.WAITING, TvQrLoginPhase.WAITING, TvQrLoginPhase.WAITING), states.map { it.phase })
        assertEquals(listOf(120, 118, 116), states.drop(1).map { it.remainingSeconds })
        assertTrue(states.drop(1).all { it.qrUrl == url(nonce) })
        assertFalse(states.toString().contains(secret)); assertFalse(states.toString().contains(login.token))
    } }

    @Test fun localExpiryClearsTheQrBeforeMakingAnotherClaim() { runBlocking {
        var now = 0L; val states = mutableListOf<TvQrLoginState>()
        val api = FixtureApi({ session(1) }, { TvPairingClaim.Pending })
        try { awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { now }, pause = { now += it }); fail("Expected expiry") }
        catch (expected: ApiException) { assertEquals(410, expected.status) }
        assertEquals(0, api.claims); assertEquals(1, api.creations)
        assertEquals(TvQrLoginPhase.EXPIRED, states.last().phase); assertNull(states.last().qrUrl)
    } }

    @Test fun anApprovalArrivingAfterTheDeadlineCannotBeAccepted() { runBlocking {
        var now = 0L; val states = mutableListOf<TvQrLoginState>()
        val api = FixtureApi({ session(3) }, { now = 3_001; TvPairingClaim.Approved(login) })
        try { awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { now }, pause = { now += it }); fail("Late approval must not return") }
        catch (expected: ApiException) { assertEquals(410, expected.status) }
        assertEquals(1, api.claims); assertEquals(TvQrLoginPhase.EXPIRED, states.last().phase)
    } }

    @Test fun cancellingTheWaitingScreenDoesNotPublishAnErrorOrContinuePolling() { runBlocking {
        val reachedPause = CompletableDeferred<Unit>(); val states = mutableListOf<TvQrLoginState>()
        val api = FixtureApi({ session() }, { TvPairingClaim.Approved(login) })
        val pending = launch {
            awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { 0L }, pause = { reachedPause.complete(Unit); awaitCancellation() })
        }
        reachedPause.await(); pending.cancelAndJoin()
        assertEquals(0, api.claims); assertEquals(1, api.creations)
        assertEquals(listOf(TvQrLoginPhase.CREATING, TvQrLoginPhase.WAITING), states.map { it.phase })
    } }

    @Test fun aProviderFailureIsSanitizedAndDoesNotAutomaticallyCreateAnotherQr() { runBlocking {
        var now = 0L; val states = mutableListOf<TvQrLoginState>()
        val api = FixtureApi({ session() }, { throw ApiException("private $secret", 403) })
        try { awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { now }, pause = { now += it }); fail("Expected provider failure") }
        catch (expected: ApiException) { assertEquals(403, expected.status); assertFalse(expected.message.orEmpty().contains(secret)) }
        assertEquals(1, api.creations); assertEquals(1, api.claims)
        assertEquals(TvQrLoginPhase.ERROR, states.last().phase); assertNull(states.last().qrUrl)
        assertFalse(states.toString().contains(secret))
    } }

    @Test fun anApprovalRouteWithoutTheOwnedNonceIsNotShownOrPolled() { runBlocking {
        val states = mutableListOf<TvQrLoginState>(); val api = FixtureApi({ session() }, { TvPairingClaim.Pending })
        try { awaitTvPairing(api, "TV", { "https://synthetic.invalid/" }, { states += it }, nowMs = { 0L }, pause = {}); fail("Invalid route must fail") }
        catch (expected: ApiException) { assertEquals(0, api.claims) }
        assertEquals(listOf(TvQrLoginPhase.CREATING, TvQrLoginPhase.ERROR), states.map { it.phase })
    } }

    @Test fun creatingTheRelayDoesNotExtendItsServerLifetime() { runBlocking {
        var now = 0L; val states = mutableListOf<TvQrLoginState>()
        val api = FixtureApi({ now += 121_000; session() }, { TvPairingClaim.Pending })
        try { awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { now }, pause = {}); fail("Already expired relay must fail") }
        catch (expected: ApiException) { assertEquals(410, expected.status) }
        assertEquals(0, api.claims); assertEquals(listOf(TvQrLoginPhase.CREATING, TvQrLoginPhase.EXPIRED), states.map { it.phase })
    } }

    @Test fun aServerExpiryAlsoClearsTheOwnedQr() { runBlocking {
        var now = 0L; val states = mutableListOf<TvQrLoginState>()
        val api = FixtureApi({ session() }, { throw ApiException("Expired", 410) })
        try { awaitTvPairing(api, "TV", ::url, { states += it }, nowMs = { now }, pause = { now += it }); fail("Expected server expiry") }
        catch (expected: ApiException) { assertEquals(410, expected.status) }
        assertEquals(TvQrLoginPhase.EXPIRED, states.last().phase); assertNull(states.last().qrUrl)
        assertEquals(1, api.claims); assertEquals(1, api.creations)
    } }
}
