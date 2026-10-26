package io.mirr.shynigsy

import io.mirr.shynigsy.data.LoginOutcome
import io.mirr.shynigsy.data.ShyniClient
import io.mirr.shynigsy.data.parseTvLoginLink
import io.mirr.shynigsy.data.sameTvLoginServer
import io.mirr.shynigsy.data.tvLoginQrUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TvPairingRelayFlowTest {
    @Test fun phoneApprovalAndAnonymousTvClaimCompleteOneOwnedRelayWithoutSharingAccountTokens() {
        runBlocking {
            withTimeout(8_000L) {
                val logicalOrigin = "https://pairing.example.invalid"
                val nonce = "n".repeat(43)
                val secret = "s".repeat(43)
                val ticket = "t".repeat(43)
                val phoneToken = "phone-account-session"
                val tvToken = "separate-tv-session"
                val violations = ConcurrentLinkedQueue<String>()
                val logicalRequests = ConcurrentLinkedQueue<String>()
                val paths = ConcurrentLinkedQueue<String>()
                val creations = AtomicInteger()
                val pendingClaims = AtomicInteger()
                val completedClaims = AtomicInteger()
                val minted = AtomicBoolean()
                val approved = AtomicBoolean()
                val pendingSeen = CompletableDeferred<Unit>()
                fun check(condition: Boolean, description: String) {
                    if (!condition) violations += description
                }
                fun json(value: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", "application/json").setBody(value.toString())
                MockWebServer().use { relay ->
                    relay.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.requestUrl?.encodedPath.orEmpty()
                            paths += path
                            check(request.method == "POST", "non_post_relay_request")
                            val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrNull()
                                ?: return json(JSONObject().put("detail", "synthetic invalid body"), 400).also {
                                    violations += "non_json_relay_request"
                                }
                            return when (path) {
                                "/auth/tv-login/pairing/sessions" -> {
                                    creations.incrementAndGet()
                                    check(request.getHeader("Authorization") == null && request.getHeader("Cookie") == null,
                                        "tv_creation_sent_account_credentials")
                                    check(request.getHeader("X-Device-Id") == "tv-device", "creation_device_changed")
                                    check(body.length() == 0, "creation_body_contains_credentials")
                                    json(JSONObject().put("nonce", nonce).put("claim_secret", secret).put("expires_in", 120))
                                }
                                "/auth/tv-login/pairing/claim" -> {
                                    check(request.getHeader("Authorization") == null && request.getHeader("Cookie") == null,
                                        "tv_claim_sent_account_credentials")
                                    check(request.getHeader("X-Device-Id") == "tv-device", "claim_device_changed")
                                    check(body.optString("nonce") == nonce && body.optString("claim_secret") == secret,
                                        "claim_does_not_own_relay")
                                    check(body.optString("device_name") == "Living room TV" && body.optString("app_name") == "shyniflex",
                                        "claim_device_context_changed")
                                    if (!approved.get()) {
                                        pendingClaims.incrementAndGet(); pendingSeen.complete(Unit)
                                        json(JSONObject().put("status", "pending"))
                                    } else {
                                        completedClaims.incrementAndGet()
                                        json(JSONObject().put("status", "approved").put("token", tvToken)
                                            .put("username", "viewer").put("user_id", "synthetic-user").put("is_admin", false))
                                    }
                                }
                                "/auth/tv-login/tickets" -> {
                                    check(request.getHeader("Authorization") == "Bearer $phoneToken", "ticket_has_wrong_phone_account")
                                    check(request.getHeader("X-Device-Id") == "phone-device", "ticket_has_wrong_device")
                                    check(body.length() == 0, "ticket_body_contains_credentials")
                                    minted.set(true)
                                    json(JSONObject().put("ticket", ticket).put("expires_in", 60))
                                }
                                "/auth/tv-login/pairing/pair/$nonce" -> {
                                    check(request.getHeader("Authorization") == "Bearer $phoneToken", "approval_has_wrong_phone_account")
                                    check(request.getHeader("X-Device-Id") == "phone-device", "approval_has_wrong_device")
                                    check(minted.get() && body.length() == 1 && body.optString("ticket") == ticket,
                                        "approval_does_not_own_ticket")
                                    approved.set(true)
                                    json(JSONObject().put("status", "approved"), 202)
                                }
                                else -> json(JSONObject().put("detail", "synthetic unknown route"), 404).also {
                                    violations += "unexpected_relay_path"
                                }
                            }
                        }
                    }
                    relay.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                    // Keep the real application/QR origin HTTPS. Only this fixture transport maps it
                    // to loopback HTTP, so no TLS dependency or external DNS/network is required.
                    val transport = OkHttpClient.Builder().dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            if (hostname !in setOf("localhost", "127.0.0.1")) throw IOException("External fixture DNS is forbidden")
                            return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                        }
                    }).addInterceptor { chain ->
                        val request = chain.request()
                        val owned = request.url.scheme == "https" && request.url.host == "pairing.example.invalid" &&
                            request.url.port == 443 && request.url.username.isEmpty() && request.url.password.isEmpty()
                        if (!owned) {
                            violations += "request_left_selected_https_origin"
                            throw IOException("Unowned fixture origin")
                        }
                        logicalRequests += "${request.url.scheme}://${request.url.host}:${request.url.port}"
                        val local = relay.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                        chain.proceed(request.newBuilder().url(local).build())
                    }.build()
                    try {
                        val phone = ShyniClient(logicalOrigin, phoneToken, transport, "phone-device")
                        val tv = ShyniClient(logicalOrigin, httpClient = transport, deviceId = "tv-device")
                        val qrReady = CompletableDeferred<TvQrLoginState>()
                        val began = System.nanoTime()
                        val tvLogin = async(Dispatchers.Default) {
                            awaitTvPairing(tv, "Living room TV", { tvLoginQrUrl(logicalOrigin, it) },
                                onState = { if (it.phase == TvQrLoginPhase.WAITING) qrReady.complete(it) },
                                nowMs = { (System.nanoTime() - began) / 1_000_000L },
                                pause = { delay(minOf(it, 20L)) })
                        }
                        val qr = qrReady.await().qrUrl ?: error("QR route missing")
                        val link = parseTvLoginLink(qr) ?: error("QR route was not parseable")
                        assertTrue(sameTvLoginServer(logicalOrigin, link.server)); assertEquals(nonce, link.nonce)
                        assertFalse(qr.contains(secret)); assertFalse(qr.contains(phoneToken)); assertFalse(qr.contains(ticket))
                        assertFalse(qr.contains("claim_secret")); assertFalse(qr.contains("token="))
                        pendingSeen.await()
                        assertNull(tv.token)
                        phone.approveTvPairing(link.nonce)
                        val result = tvLogin.await()
                        assertEquals(LoginOutcome.Success(tvToken, "viewer"), result)
                        assertNotEquals(phoneToken, result.token)
                        assertEquals(phoneToken, phone.token)
                        assertNull("Only the ViewModel generation guard may install this result", tv.token)
                        assertEquals(1, creations.get()); assertTrue(pendingClaims.get() >= 1); assertEquals(1, completedClaims.get())
                        assertEquals(1, paths.count { it == "/auth/tv-login/tickets" })
                        assertEquals(1, paths.count { it == "/auth/tv-login/pairing/pair/$nonce" })
                        assertTrue(logicalRequests.isNotEmpty())
                        assertTrue(logicalRequests.all { it == "https://pairing.example.invalid:443" })
                        assertTrue("Dispatcher recorded protocol violations: $violations", violations.isEmpty())
                    } finally {
                        transport.connectionPool.evictAll()
                        transport.dispatcher.executorService.shutdownNow()
                    }
                }
            }
        }
    }
}
