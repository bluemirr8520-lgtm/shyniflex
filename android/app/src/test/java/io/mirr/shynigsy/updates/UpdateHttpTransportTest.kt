package io.mirr.shynigsy.updates

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateHttpTransportTest {
    private val url = "https://github.com/$UPDATE_REPOSITORY/releases/download/v1/app.apk"
    private val api = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/assets/123"
    private val asset = UpdateAsset("app.apk", url, 3, api)

    private fun transport(handle: (okhttp3.Request) -> Response): UpdateHttpTransport {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor(Interceptor { chain -> handle(chain.request()) }).build()
        return UpdateHttpTransport(client)
    }

    private fun reply(request: okhttp3.Request, status: Int, body: String = "", location: String? = null): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody("text/plain".toMediaType()))
        if (location != null) builder.header("Location", location)
        return builder.build()
    }

    @Test fun retriesOnlyServerFailuresWithFixedCacheBuster() {
        val requests = mutableListOf<String>()
        val t = transport { request ->
            requests += request.url.toString()
            if (request.url.query == null) reply(request, 504) else reply(request, 200, "ok")
        }
        assertEquals("ok", t.asset(asset) { it.body!!.string() })
        assertEquals(listOf(url, "$url?retry=1"), requests)
    }

    @Test fun fallsBackToTrustedAssetApiAndFollowsOnlyAllowedRedirects() {
        val hosts = mutableListOf<String>()
        val t = transport { request ->
            hosts += request.url.host
            when (request.url.host) {
                "github.com" -> reply(request, 503)
                "api.github.com" -> {
                    assertEquals("application/octet-stream", request.header("Accept"))
                    reply(request, 302, location = "https://release-assets.githubusercontent.com/token")
                }
                else -> reply(request, 200, "apk")
            }
        }
        assertEquals("apk", t.asset(asset) { it.body!!.string() })
        assertEquals(listOf("github.com", "github.com", "api.github.com", "release-assets.githubusercontent.com"), hosts)
    }

    @Test fun neverFollowsRedirectToUntrustedHostOrRetriesInvalidContent() {
        var requests = 0
        val invalid = transport { request -> requests++; reply(request, 302, location = "https://evil.invalid/file") }
        assertTrue(runCatching { invalid.asset(asset) { it.body!!.string() } }.isFailure)
        assertEquals(1, requests)
        requests = 0
        val badContent = transport { request -> requests++; reply(request, 200, "bad") }
        assertTrue(runCatching { badContent.asset(asset) { require(it.body!!.string() == "good") } }.isFailure)
        assertEquals(1, requests)
    }

    @Test fun connectionFailureFallsBackToApiAndUnusableEndpointsReportError() {
        val hosts = mutableListOf<String>()
        val t = transport { request ->
            hosts += request.url.host
            if (request.url.host == "github.com") throw IOException("offline")
            reply(request, 200, "ok")
        }
        assertEquals("ok", t.asset(asset) { it.body!!.string() })
        assertEquals(listOf("github.com", "api.github.com"), hosts)
        val failed = transport { request -> reply(request, 404) }
        assertTrue(runCatching { failed.asset(asset) { it.body!!.string() } }.isFailure)
    }
}
