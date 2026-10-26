package io.mirr.shynigsy.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Public QR routing information only. A claim secret or existing session never belongs here. */
data class TvLoginLink(val server: String, val nonce: String)

private const val TV_LOGIN_LINK_LIMIT = 4096
private val tvLoginNonce = Regex("[A-Za-z0-9_-]{32,256}")

/** Build the app-only approval link, using a canonical HTTPS origin and a public nonce. */
fun tvLoginQrUrl(server: String, nonce: String): String {
    require(tvLoginNonce.matches(nonce)) { "잘못된 TV 연결 코드입니다." }
    val origin = canonicalTvLoginServer(server)
    return "shyniflex://tv-login?server=${encodeTvLoginValue(origin)}&nonce=${encodeTvLoginValue(nonce)}"
        .also { require(it.length <= TV_LOGIN_LINK_LIMIT) { "TV 연결 주소가 너무 깁니다." } }
}

/** Untrusted ACTION_VIEW data is only a request to show confirmation, never authority to log in. */
fun parseTvLoginLink(uriString: String): TvLoginLink? = runCatching {
    require(uriString.isNotEmpty() && uriString.length <= TV_LOGIN_LINK_LIMIT)
    require(uriString == uriString.trim() && uriString.none { it.isISOControl() })
    val uri = URI(uriString)
    require(uri.scheme.equals("shyniflex", ignoreCase = true))
    require(uri.host.equals("tv-login", ignoreCase = true))
    // Reject empty user info, a trailing colon, encoded authorities and all alternative ports.
    require(uri.rawAuthority.equals("tv-login", ignoreCase = true))
    require(uri.rawUserInfo == null && uri.port == -1)
    require(uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
    val query = requireNotNull(uri.rawQuery)
    val values = LinkedHashMap<String, String>()
    for (part in query.split('&')) {
        val separator = part.indexOf('=')
        require(separator > 0)
        val key = URLDecoder.decode(part.substring(0, separator), "UTF-8")
        val value = URLDecoder.decode(part.substring(separator + 1), "UTF-8")
        require(key in setOf("server", "nonce") && key !in values)
        values[key] = value
    }
    require(values.keys == setOf("server", "nonce"))
    val nonce = requireNotNull(values["nonce"])
    require(tvLoginNonce.matches(nonce))
    TvLoginLink(canonicalTvLoginServer(requireNotNull(values["server"])), nonce)
}.getOrNull()

/** A different account origin, protocol or nondefault port must require a separate login. */
fun sameTvLoginServer(first: String, second: String): Boolean {
    val firstOrigin = runCatching { canonicalTvLoginServer(first) }.getOrNull() ?: return false
    val secondOrigin = runCatching { canonicalTvLoginServer(second) }.getOrNull() ?: return false
    return firstOrigin == secondOrigin
}

private fun canonicalTvLoginServer(server: String): String {
    require(server.isNotEmpty() && server.length <= 2048 && server == server.trim())
    require(server.none { it.isISOControl() || it.isWhitespace() } && '\\' !in server)
    val raw = runCatching { URI(server) }.getOrElse {
        throw IllegalArgumentException("서버 주소가 올바르지 않습니다.")
    }
    require(raw.scheme.equals("https", ignoreCase = true) && !raw.rawAuthority.isNullOrEmpty())
    require(raw.rawUserInfo == null && !raw.rawAuthority.endsWith(':'))
    require(raw.rawPath.isNullOrEmpty() || raw.rawPath == "/")
    require(raw.rawQuery == null && raw.rawFragment == null)
    val origin = runCatching { server.toHttpUrl() }.getOrElse {
        throw IllegalArgumentException("서버 주소가 올바르지 않습니다.")
    }
    require(origin.scheme == "https" && origin.username.isEmpty() && origin.password.isEmpty())
    require(origin.encodedPath == "/" && origin.query == null && origin.fragment == null)
    return origin.toString().trimEnd('/')
}

private fun encodeTvLoginValue(value: String): String = URLEncoder.encode(value, "UTF-8")
