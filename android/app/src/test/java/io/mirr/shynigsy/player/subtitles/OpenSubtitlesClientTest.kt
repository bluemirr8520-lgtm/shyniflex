package io.mirr.shynigsy.player.subtitles

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.net.InetAddress

class OpenSubtitlesClientTest {
    private val account = OpenSubtitlesAccount("synthetic-app-key", "synthetic-jwt", "synthetic-user")

    // Windows reverse DNS can turn the default loopback hostname into the machine name.
    // Bind and address this isolated fixture by its literal IPv4 loopback instead.
    private fun loopbackServer() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun MockWebServer.loopbackUrl(path: String): HttpUrl = url(path).newBuilder().host("127.0.0.1").build()

    @Test fun searchUsesCanonicalQueryAndKeepsAllServerRows() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(json(page(2, 3, 123, 50)))
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            val result = client.search(account, OpenSubtitlesQuery(" The Movie ", "KO", 1, 6, 2026, 2))
            assertEquals(50, result.items.size)
            assertEquals(2, result.page); assertEquals(3, result.totalPages); assertEquals(123, result.totalCount)
            assertTrue(result.hasNextPage)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/subtitles?episode_number=6&languages=ko&page=2&query=the+movie&season_number=1&year=2026", request.path)
            assertEquals(account.apiKey, request.getHeader("Api-Key"))
            assertEquals("Bearer ${account.token}", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Spoiler-Token"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun emptyPageAndAllLanguagesAreNotAnError() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(json(page(1, 0, 0, 0)))
            val result = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
                .search(account.copy(token = null), OpenSubtitlesQuery("영화", "all"))
            assertTrue(result.items.isEmpty()); assertFalse(result.hasNextPage)
            val request = server.takeRequest()
            assertFalse(request.path!!.contains("languages=")); assertFalse(request.path!!.contains("page="))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun loginUsesOnlyProviderKeyAndValidatesReturnedHost() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(json(JSONObject().put("token", "new-synthetic-jwt").put("base_url", "vip-api.opensubtitles.com")
                .put("user", JSONObject().put("allowed_downloads", 100))))
            val result = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
                .login("synthetic-app-key", "synthetic-user", "synthetic-password")
            assertEquals("vip-api.opensubtitles.com", result.baseHost)
            assertEquals(100, result.allowedDownloads)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("synthetic-user", body.getString("username"))
            assertEquals("synthetic-password", body.getString("password"))
            assertFalse(result.toString().contains("synthetic"))
        }
    }

    @Test fun rejectsHostInjectionEvenWhenLoginReturnsSuccess() = runBlocking {
        loopbackServer().use { server ->
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            for (host in listOf("api.opensubtitles.com.evil.test", "https://api.opensubtitles.com", "127.0.0.1", "api.opensubtitles.com@evil.test")) {
                server.enqueue(json(JSONObject().put("token", "synthetic-jwt").put("base_url", host)))
                val error = failure { client.login("synthetic-app-key", "user", "password") }
                assertFalse(error.message.orEmpty().contains(host))
            }
        }
    }

    @Test fun selectedDownloadSpendsOnePostAndFetchesAnonymously() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(json(download(server, "/file.srt")))
            server.enqueue(MockResponse().setBody("1\n00:00:00,000 --> 00:00:01,000\n안녕하세요\n"))
            val result = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/" )).downloadBytes(account, 12)
            assertTrue(String(result.bytes, Charsets.UTF_8).contains("안녕하세요"))
            assertEquals("caption.srt", result.fileName); assertEquals(4, result.remaining)
            val issue = server.takeRequest()
            assertEquals("POST", issue.method); assertEquals("/api/v1/download", issue.path)
            assertEquals(12, JSONObject(issue.body.readUtf8()).getInt("file_id"))
            assertEquals("Bearer ${account.token}", issue.getHeader("Authorization"))
            val file = server.takeRequest()
            assertEquals("GET", file.method); assertEquals("/file.srt", file.path)
            assertNull(file.getHeader("Authorization")); assertNull(file.getHeader("Api-Key")); assertNull(file.getHeader("Cookie"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun downloadCanonicalRedirectStillHasNoCredentials() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(json(download(server, "/first")))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.srt"))
            server.enqueue(MockResponse().setBody("synthetic subtitle"))
            OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/")).downloadBytes(account, 12)
            server.takeRequest(); server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/final.srt", request.path)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Api-Key"))
        }
    }

    @Test fun rejectsUntrustedFileAndRedirectWithoutContactingTheTarget() = runBlocking {
        loopbackServer().use { server -> loopbackServer().use { target ->
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            server.enqueue(json(download(server, "/file").put("link", target.loopbackUrl("/steal").toString())))
            failure { client.downloadBytes(account, 12) }
            assertEquals(0, target.requestCount)
            server.enqueue(json(download(server, "/file")))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.loopbackUrl("/steal")))
            failure { client.downloadBytes(account, 12) }
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun searchRedirectCannotForwardProviderCredentialsElsewhere() = runBlocking {
        loopbackServer().use { server -> loopbackServer().use { target ->
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", target.loopbackUrl("/api/v1/subtitles")))
            failure { OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/")).search(account, OpenSubtitlesQuery("movie")) }
            assertEquals(0, target.requestCount)
            assertEquals(1, server.requestCount)
        } }
    }

    @Test fun sameOriginSearchNormalizationIsBoundedAndSupported() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/api/v1/subtitles?languages=ko&query=movie"))
            server.enqueue(json(page(1, 1, 1, 1)))
            val result = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/")).search(account, OpenSubtitlesQuery("movie"))
            assertEquals(1, result.items.size); assertEquals(2, server.requestCount)
        }
    }

    @Test fun quotaPostIsNeverRetriedOrRedirectedAndErrorsNeverExposeBodies() = runBlocking {
        loopbackServer().use { server ->
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            server.enqueue(MockResponse().setResponseCode(406).setBody("synthetic-secret raw body"))
            val quota = failure { client.downloadBytes(account, 12) }
            assertTrue(quota.quotaExceeded); assertEquals(406, quota.status)
            assertFalse(quota.message.orEmpty().contains("synthetic-secret")); assertNull(quota.cause)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/api/v1/download"))
            failure { client.downloadBytes(account, 12) }
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            failure { client.downloadBytes(account, 12) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun expiredTokenAndRateLimitAreDistinct() = runBlocking {
        loopbackServer().use { server ->
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(failure { client.search(account, OpenSubtitlesQuery("movie")) }.loginRequired)
            server.enqueue(MockResponse().setResponseCode(429))
            val rate = failure { client.search(account, OpenSubtitlesQuery("movie")) }
            assertEquals(429, rate.status); assertFalse(rate.quotaExceeded)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rejectsDeclaredAndStreamingOversizeFiles() = runBlocking {
        loopbackServer().use { server ->
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            server.enqueue(json(download(server, "/large")))
            server.enqueue(MockResponse().setBody("small").setHeader("Content-Length", OpenSubtitlesClient.MAX_FILE_BYTES + 1))
            failure { client.downloadBytes(account, 12) }
            server.enqueue(json(download(server, "/chunked")))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(OpenSubtitlesClient.MAX_FILE_BYTES + 1), 8192))
            failure { client.downloadBytes(account, 12) }
            Unit
        }
    }

    @Test fun cancelStopsAnInFlightProviderCallWithoutRetry() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async { OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/")).search(account, OpenSubtitlesQuery("movie")) }
            kotlinx.coroutines.yield()
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled); assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidInputsFailWithoutAnyRequest() = runBlocking {
        loopbackServer().use { server ->
            val client = OpenSubtitlesClient.forLocalTests(server.loopbackUrl("/"))
            failure { client.search(account.copy(apiKey = "key\r\nAuthorization: secret"), OpenSubtitlesQuery("movie")) }
            failure { client.search(account, OpenSubtitlesQuery("")) }
            failure { client.search(account, OpenSubtitlesQuery("movie", page = 0)) }
            failure { client.downloadBytes(account, -1) }
            failure { client.search(account.copy(baseHost = "untrusted.test"), OpenSubtitlesQuery("movie")) }
            assertEquals(0, server.requestCount)
        }
    }

    private suspend fun failure(block: suspend () -> Any?): OpenSubtitlesException {
        try { block() } catch (error: OpenSubtitlesException) { return error }
        throw AssertionError("Expected a sanitized provider error")
    }
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun download(server: MockWebServer, path: String) = JSONObject().put("link", server.loopbackUrl(path))
        .put("file_name", "../../caption.srt").put("remaining", 4).put("reset_time_utc", "2026-11-19T00:00:00.000Z")
    private fun page(page: Int, pages: Int, count: Int, rows: Int): JSONObject = JSONObject().put("page", page)
        .put("total_pages", pages).put("total_count", count).put("per_page", 50).put("data", JSONArray().apply {
            repeat(rows) { index -> put(JSONObject().put("id", index.toString()).put("attributes", JSONObject()
                .put("language", "ko").put("release", "Synthetic release").put("ratings", 8.5).put("download_count", 11)
                .put("feature_details", JSONObject().put("title", "Synthetic title"))
                .put("files", JSONArray().put(JSONObject().put("file_id", index + 1).put("file_name", "caption.srt"))))) }
        })
}
