package io.mirr.shynigsy.plex

import io.mirr.shynigsy.BuildConfig
import io.mirr.shynigsy.data.PLEX_PRODUCT_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.DocumentBuilderFactory

internal data class LinkCode(val id: Long, val code: String, val lifetimeSeconds: Long)
internal class PlexServerResource(val id: String, val title: String, val token: String, val urls: List<String>) {
    fun matches(address: String) = urls.any { samePlexAddress(it, address) }
}

private fun samePlexAddress(url: String, preferred: String): Boolean {
    val address = preferred.trim().trimEnd('/')
    if (address.isEmpty()) return false
    val normalized = (if ("://" in address) address else "${url.substringBefore("://")}://$address").toHttpUrlOrNull()
    return normalized?.toString()?.trimEnd('/') == url.trimEnd('/')
}

internal suspend fun <T : Any> PlexServerResource.connectFirstAvailable(
    preferred: String, connect: suspend (String) -> T,
): T {
    var failure: IOException? = null
    for (url in urls.sortedBy { !samePlexAddress(it, preferred) }) {
        try {
            withTimeoutOrNull(4_000L) { connect(url) }?.let { return it }
        } catch (e: IOException) {
            failure = e
        }
    }
    throw IOException("‘$title’ 서버에 연결하지 못했습니다. 서버 전원과 원격 접속 설정을 확인한 뒤 다시 시도해 주세요.", failure)
}

internal class PlexAccountException(val status: Int, message: String) : IOException(message)

internal class PlexAccount(
    private val deviceId: String,
    private val pinsBase: HttpUrl = "https://plex.tv/api/v2/".toHttpUrl(),
    private val resourcesBase: HttpUrl = "https://clients.plex.tv/api/v2/".toHttpUrl(),
    private val authPage: HttpUrl = "https://app.plex.tv/auth".toHttpUrl(),
) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun createCode(strong: Boolean = false): LinkCode {
        val endpoint = pinsBase.newBuilder().addPathSegment("pins")
        if (strong) endpoint.addQueryParameter("strong", "true")
        val data = JSONObject(request(endpoint.build(), post = true))
        return LinkCode(data.getLong("id"), data.getString("code"), data.optLong("expiresIn", 300).coerceIn(1, 1800))
    }

    fun signInUrl(code: LinkCode): String {
        val parameters = authPage.newBuilder()
            .addQueryParameter("clientID", deviceId)
            .addQueryParameter("code", code.code)
            .addQueryParameter("context[device][product]", PLEX_PRODUCT_NAME)
            .build().encodedQuery
        // Plex's hosted sign-in reads its parameters from the fragment. The app polls for the claim.
        return "$authPage#?$parameters"
    }

    suspend fun tokenFor(code: LinkCode): String? {
        val url = pinsBase.newBuilder().addPathSegment("pins").addPathSegment(code.id.toString())
            .addQueryParameter("code", code.code).build()
        val data = JSONObject(request(url))
        return if (data.isNull("authToken")) null else data.getString("authToken").takeIf { it.isNotBlank() }
    }

    suspend fun servers(accountToken: String, preferred: String = ""): List<PlexServerResource> {
        val url = resourcesBase.newBuilder().addPathSegment("resources")
            .addQueryParameter("includeHttps", "1").addQueryParameter("includeRelay", "1")
            .addQueryParameter("includeIPv6", "1").build()
        val servers = parseServerConnections(JSONArray(request(url, token = accountToken)))
        if (servers.isEmpty() || servers.any { it.matches(preferred) }) return servers
        val alias = preferred.trim().toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        } ?: return servers
        val machineId = identify(alias) ?: return servers
        return servers.map { server ->
            if (server.id == machineId) PlexServerResource(server.id, server.title, server.token,
                listOf(alias.toString().trimEnd('/')) + server.urls) else server
        }
    }

    /** /identity is a public PMS endpoint. Account and server tokens are never attached to this probe. */
    private suspend fun identify(base: HttpUrl): String? = withContext(Dispatchers.IO) {
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/identity").build()
        val request = Request.Builder().url(url).header("Accept", "application/json, application/xml").build()
        try {
            val call = http.newCall(request)
            call.timeout().timeout(1800, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) return@use null
                val data = response.peekBody(8193).bytes()
                if (data.size > 8192) return@use null
                plexMachineId(data.toString(Charsets.UTF_8).trim())
            }
        } catch (_: IOException) { null }
    }

    private suspend fun request(url: HttpUrl, token: String? = null, post: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
                .header("X-Plex-Client-Identifier", deviceId).header("X-Plex-Product", PLEX_PRODUCT_NAME)
                .header("X-Plex-Platform", "Android").header("X-Plex-Version", BuildConfig.VERSION_NAME)
            if (post) builder.post(ByteArray(0).toRequestBody(null))
            if (token != null) builder.header("X-Plex-Token", token)
            http.newCall(builder.build()).execute().use {
                if (!it.isSuccessful) throw PlexAccountException(it.code,
                    when (it.code) {
                        404, 410 -> "연결 코드가 만료되었습니다. 새 코드를 받아 주세요."
                        401, 403 -> "Plex 계정 인증이 만료되었습니다. 다시 로그인해 주세요."
                        429 -> "연결 요청이 많습니다. 잠시 후 다시 시도해 주세요."
                        else -> "Plex 계정 서비스에 연결하지 못했습니다 (HTTP ${it.code})."
                    }
                )
                it.body?.string() ?: throw IOException("Plex 계정 응답이 비어 있습니다.")
            }
        }
}

private fun plexMachineId(body: String): String? = runCatching {
    val id = if (body.startsWith("{")) {
        JSONObject(body).getJSONObject("MediaContainer").getString("machineIdentifier")
    } else {
        if (!body.startsWith("<") || body.contains("<!DOCTYPE", ignoreCase = true)) return null
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(DefaultHandler())
        }
        val root = parser.parse(InputSource(StringReader(body))).documentElement
        if (root.tagName != "MediaContainer") return null
        root.getAttribute("machineIdentifier")
    }
    id.takeIf { it.isNotBlank() && !it.contains('/') }
}.getOrNull()

internal fun parseServerConnections(resources: JSONArray): List<PlexServerResource> = buildList {
    for (i in 0 until resources.length()) {
        val server = resources.optJSONObject(i) ?: continue
        if ("server" !in server.optString("provides").split(',').map(String::trim)) continue
        if (server.isNull("accessToken")) continue
        val token = server.optString("accessToken").takeIf { it.isNotBlank() } ?: continue
        val connections = server.optJSONArray("connections") ?: continue
        val endpoints = mutableListOf<Pair<String, Int>>()
        for (j in 0 until connections.length()) {
            val connection = connections.optJSONObject(j) ?: continue
            val url = connection.optString("uri").toHttpUrlOrNull() ?: continue
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) continue
            val local = connection.optBoolean("local")
            if (!url.isHttps && !local) continue
            val priority = when {
                connection.optBoolean("relay") -> 3
                local && url.isHttps -> 0
                local -> 1
                else -> 2
            }
            endpoints += url.toString().trimEnd('/') to priority
        }
        val urls = endpoints.sortedBy { it.second }.map { it.first }.distinct()
        if (urls.isNotEmpty()) {
            val id = server.optString("clientIdentifier").ifBlank { urls.first() }
            add(PlexServerResource(id, server.optString("name", "Plex Media Server"), token, urls))
        }
    }
}.groupBy { it.id }.values.map { entries ->
    val first = entries.first()
    PlexServerResource(first.id, first.title, first.token, entries.flatMap { it.urls }.distinct())
}
