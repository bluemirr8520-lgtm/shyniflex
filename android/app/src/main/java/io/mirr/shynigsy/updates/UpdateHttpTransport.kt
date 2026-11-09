package io.mirr.shynigsy.updates

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** No account credentials are attached to GitHub or its public asset redirects. */
internal class UpdateHttpTransport(private val http: OkHttpClient) {
    fun response(initialUrl: String, accept: String = "application/vnd.github+json"): Response {
        var url = initialUrl
        repeat(5) {
            val request = Request.Builder().url(url).header("Accept", accept)
                .header("User-Agent", "ShyniFlix-Android-Updater").build()
            val target = request.url
            require(target.scheme == "https" && target.port == 443 && target.username.isEmpty() && target.password.isEmpty() &&
                target.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
            val result = http.newCall(request).execute()
            if (result.code !in setOf(301, 302, 303, 307, 308)) return result
            val next = result.header("Location")?.let { target.resolve(it)?.toString() }
            result.close()
            url = requireNotNull(next)
        }
        throw IOException("Too many update redirects")
    }

    private class AssetHttpFailure(val status: Int) : IOException("Update asset HTTP $status")

    /** Retry only transport/HTTP failures, never a content validation failure. */
    fun <T> asset(asset: UpdateAsset, consume: (Response) -> T): T {
        fun attempt(url: String, accept: String): T = response(url, accept).use { result ->
            if (!result.isSuccessful) throw AssetHttpFailure(result.code)
            consume(result)
        }
        val browserAccept = "application/vnd.github+json"
        var last: IOException
        try {
            return attempt(asset.url, browserAccept)
        } catch (error: IOException) {
            last = error
        }
        if (last is AssetHttpFailure && last.status in 500..599) {
            try {
                // The original URL has already been checked against the release.
                // Add only a fixed cache-buster, not a URL from an untrusted manifest.
                val retryUrl = asset.url.toHttpUrl().newBuilder().addQueryParameter("retry", "1").build().toString()
                return attempt(retryUrl, browserAccept)
            } catch (error: IOException) {
                last = error
            }
        }
        if (asset.apiUrl != null) {
            try {
                return attempt(asset.apiUrl, "application/octet-stream")
            } catch (error: IOException) {
                last = error
            }
        }
        throw last
    }
}
