package io.mirr.shynigsy.data

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TvPairingClientTest {
    private val nonce = "n".repeat(43)
    private val secret = "s".repeat(43)

    private fun sessionBody() = JSONObject().put("nonce", nonce).put("claim_secret", secret).put("expires_in", 120)
    private fun ownedSession() = parseTvPairingSession(sessionBody())

    @Test fun createsRelayWithoutSendingAnExistingAccountToken() { runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(sessionBody().toString()))
            val client = ShyniClient(server.url("/").toString(), "existing-token", deviceId = "tv-device")
            val session = client.createTvPairingSession()
            assertEquals(120, session.expiresInSeconds)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/auth/tv-login/pairing/sessions", request.path)
            assertEquals("{}", request.body.readUtf8())
            assertEquals("tv-device", request.getHeader("X-Device-Id"))
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals("existing-token", client.token)
            assertFalse(session.toString().contains(nonce)); assertFalse(session.toString().contains(secret))
        }
    } }

    @Test fun pendingClaimPostsOnlyTheOwnedRelayAndDeviceContext() { runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
            val client = ShyniClient(server.url("/").toString(), "existing-token", deviceId = "tv-device")
            assertSame(TvPairingClaim.Pending, client.claimTvPairing(ownedSession(), "Living room TV"))
            val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
            assertEquals("POST", request.method)
            assertEquals("/auth/tv-login/pairing/claim", request.path)
            assertEquals(nonce, body.getString("nonce")); assertEquals(secret, body.getString("claim_secret"))
            assertEquals("Living room TV", body.getString("device_name")); assertEquals("shyniflex", body.getString("app_name"))
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertEquals("existing-token", client.token)
        }
    } }

    @Test fun approvedClaimDoesNotInstallItsTokenBeforeTheCallerChecksGeneration() { runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"status":"approved","token":"new-tv-token","username":"viewer","user_id":"synthetic","is_admin":false}"""))
            val client = ShyniClient(server.url("/").toString())
            val claim = client.claimTvPairing(ownedSession(), "TV") as TvPairingClaim.Approved
            assertEquals(LoginOutcome.Success("new-tv-token", "viewer"), claim.login)
            assertNull(client.token)
            assertFalse(claim.toString().contains("new-tv-token"))
        }
    } }

    @Test fun malformedRelayFieldsCannotCreateAUsableSession() {
        val invalid = listOf(
            sessionBody().put("nonce", "short"), sessionBody().put("claim_secret", nonce),
            sessionBody().put("claim_secret", "x".repeat(42) + "\n"),
            sessionBody().put("expires_in", "120"), sessionBody().put("expires_in", 1.5),
            sessionBody().put("expires_in", 0), sessionBody().put("expires_in", 301),
            sessionBody().put("expires_in", true), sessionBody().put("nonce", JSONObject.NULL),
        )
        invalid.forEach { value ->
            try { parseTvPairingSession(value); fail("Malformed relay must be rejected") }
            catch (expected: ApiException) { assertFalse(expected.message.orEmpty().contains(secret)) }
        }
    }

    @Test fun contradictoryOrIncompleteClaimResponsesCannotAuthenticate() {
        val invalid = listOf(
            JSONObject().put("status", "pending").put("token", "unexpected-token"),
            JSONObject().put("status", "approved").put("username", "viewer"),
            JSONObject().put("status", "approved").put("token", "token"),
            JSONObject().put("status", "approved").put("token", "token\r\nvalue").put("username", "viewer"),
            JSONObject().put("status", "approved").put("token", "token").put("username", "viewer").put("require_totp", true),
            JSONObject().put("status", "unknown").put("token", "token").put("username", "viewer"),
        )
        invalid.forEach { value ->
            try { parseTvPairingClaim(value); fail("Malformed claim must be rejected") }
            catch (expected: ApiException) { assertFalse(expected.message.orEmpty().contains("unexpected-token")) }
        }
    }

    @Test fun relayRedirectCannotForwardTheClaimSecretToAnotherOrigin() { runBlocking {
        MockWebServer().use { source -> MockWebServer().use { other ->
            source.start(); other.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/collect")))
            val client = ShyniClient(source.url("/").toString())
            try { client.claimTvPairing(ownedSession(), "TV"); fail("Redirect must be rejected") }
            catch (expected: ApiException) { assertEquals(307, expected.status) }
            assertEquals(0, other.requestCount)
            assertNull(client.token)
        } }
    } }

    @Test fun expiryDisabledAndOversizedResponsesRemainUnauthenticated() { runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = ShyniClient(server.url("/").toString())
            for (status in listOf(410, 403)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"synthetic"}"""))
                try { client.claimTvPairing(ownedSession(), "TV"); fail("Terminal response must not authenticate") }
                catch (expected: ApiException) { assertEquals(status, expected.status) }
            }
            server.enqueue(MockResponse().setBody(JSONObject().put("padding", "x".repeat(40_000)).toString()))
            try { client.createTvPairingSession(); fail("Oversized response must be rejected") }
            catch (expected: ApiException) { assertNull(client.token) }
        }
    } }

    @Test fun cancelledClaimDoesNotWaitForTheServerOrSetAToken() { runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = ShyniClient(server.url("/").toString())
            val pending = async(Dispatchers.Default) { client.claimTvPairing(ownedSession(), "TV") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled); assertNull(client.token)
        }
    } }

    @Test fun approvedCookieOnlyClaimIsReturnedWithoutInstallingIt() { runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "shyni_session=tv-session; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "device=tv-context; Path=/; HttpOnly")
                .setBody("""{"status":"approved","username":"viewer"}"""))
            val client = ShyniClient(server.url("/").toString())
            val result = client.claimTvPairing(ownedSession(), "TV") as TvPairingClaim.Approved
            assertEquals(LoginOutcome.Success("cookie:shyni_session=tv-session; device=tv-context", "viewer"), result.login)
            assertNull(client.token)
        }
    } }

    @Test fun aCookieOnAPendingClaimCannotBecomeALoggedInSession() { runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "shyni_session=not-approved; Path=/; HttpOnly")
                .setBody("""{"status":"pending"}"""))
            val client = ShyniClient(server.url("/").toString())
            assertSame(TvPairingClaim.Pending, client.claimTvPairing(ownedSession(), "TV"))
            assertNull(client.token)
        }
    } }

    @Test fun explicitPhoneApprovalUsesOneTicketOnTheSameAuthenticatedOrigin() { runBlocking {
        MockWebServer().use { server ->
            server.start(); val ticket = "t".repeat(43)
            server.enqueue(MockResponse().setBody(JSONObject().put("ticket", ticket).put("expires_in", 60).toString()))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"approved"}"""))
            val client = ShyniClient(server.url("/").toString(), "phone-token", deviceId = "phone-device")
            client.approveTvPairing(nonce)
            val mint = server.takeRequest(); val approve = server.takeRequest()
            assertEquals("/auth/tv-login/tickets", mint.path); assertEquals("{}", mint.body.readUtf8())
            assertEquals("/auth/tv-login/pairing/pair/$nonce", approve.path)
            val approvedBody = JSONObject(approve.body.readUtf8())
            assertEquals(1, approvedBody.length()); assertEquals(ticket, approvedBody.getString("ticket"))
            assertEquals("Bearer phone-token", mint.getHeader("Authorization"))
            assertEquals("Bearer phone-token", approve.getHeader("Authorization"))
            assertEquals("phone-device", approve.getHeader("X-Device-Id"))
            assertEquals("phone-token", client.token); assertEquals(2, server.requestCount)
        }
    } }

    @Test fun invalidNonceAndMalformedTicketCannotSendAnApproval() { runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = ShyniClient(server.url("/").toString(), "phone-token")
            try { client.approveTvPairing("../../another-path"); fail("Invalid nonce must fail before a request") }
            catch (expected: IllegalArgumentException) { assertEquals(0, server.requestCount) }
            server.enqueue(MockResponse().setBody("""{"ticket":"short","expires_in":60}"""))
            try { client.approveTvPairing(nonce); fail("Invalid ticket must fail before approval") }
            catch (expected: ApiException) { assertEquals(1, server.requestCount) }
            assertEquals("phone-token", client.token)
        }
    } }

    @Test fun aPhoneAccountChangeDuringTicketMintingCannotApproveTheTv() { runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = ShyniClient(server.url("/").toString(), "phone-token")
            server.enqueue(MockResponse().setBody(JSONObject().put("ticket", "t".repeat(43)).put("expires_in", 60).toString())
                .setBodyDelay(200, TimeUnit.MILLISECONDS))
            val operation = async(Dispatchers.Default) { client.approveTvPairing(nonce) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            client.token = "other-account"
            try { operation.await(); fail("Changed account must not approve") }
            catch (expected: kotlinx.coroutines.CancellationException) { assertEquals(1, server.requestCount) }
            assertEquals("other-account", client.token)
        }
    } }
}
