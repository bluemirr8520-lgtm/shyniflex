package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ShyniCookieAuthTest {
    @Test fun cookieLoginRestoresSessionWithoutBearerAndDoesNotLeakToOtherOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "shyni_session=mock-session; Path=/; HttpOnly")
                .setBody("""{"username":"test"}"""))
            val client = ShyniClient(server.url("/").toString())
            assertEquals(LoginOutcome.Success("cookie:shyni_session=mock-session", "test"), client.login("test", "pass"))
            assertNull(server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("""{"username":"test"}"""))
            assertEquals("test", client.me())
            val request = server.takeRequest()
            assertEquals("shyni_session=mock-session", request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(client.headersFor("https://different.invalid/image.jpg").isEmpty())
            assertEquals("shyni_session=mock-session", client.headersFor(server.url("/image.jpg").toString())["Cookie"])
        }
    }
    @Test fun totpCookieIsAcceptedOnlyAfterChallengeCompletes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"require_totp":true,"challenge_token":"challenge"}"""))
            val client = ShyniClient(server.url("/").toString())
            assertEquals(LoginOutcome.NeedsTotp("challenge"), client.login("test", "pass"))
            assertNull(client.token)
            server.takeRequest()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=after-otp; Path=/; HttpOnly")
                .setBody("""{"username":"test"}"""))
            assertEquals(LoginOutcome.Success("cookie:session=after-otp", "test"), client.completeTotp("challenge", "123456"))
        }
    }
}
