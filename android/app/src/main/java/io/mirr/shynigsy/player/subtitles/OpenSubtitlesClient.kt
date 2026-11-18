package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class OpenSubtitlesAccount(val apiKey: String, val token: String? = null,
    val userName: String = "", val baseHost: String = DEFAULT_HOST, val allowedDownloads: Int? = null) {
    // Credentials must never appear in a UI/debug interpolation.
    override fun toString() = "OpenSubtitlesAccount(authenticated=${token != null})"
    companion object { const val DEFAULT_HOST = "api.opensubtitles.com" }
}

internal data class OpenSubtitlesQuery(val query: String, val language: String = "ko",
    val seasonNumber: Int? = null, val episodeNumber: Int? = null, val year: Int? = null, val page: Int = 1)
internal data class OpenSubtitlesFile(val fileId: Int, val fileName: String)
internal data class OpenSubtitlesResult(val id: String, val title: String, val release: String, val language: String,
    val rating: Double?, val downloadCount: Int, val files: List<OpenSubtitlesFile>)
internal data class OpenSubtitlesSearchPage(val items: List<OpenSubtitlesResult>, val page: Int,
    val totalPages: Int, val totalCount: Int) { val hasNextPage get() = page < totalPages }
internal class OpenSubtitlesDownload(val bytes: ByteArray, val fileName: String, val remaining: Int?, val resetTime: String?)
internal class OpenSubtitlesException(message: String, val status: Int? = null,
    val loginRequired: Boolean = false, val quotaExceeded: Boolean = false) : IOException(message)

/** Provider-only REST transport. No Shyni clients, cookies, headers, URLs or media tickets enter this class.
 * Contract checked against https://ai.opensubtitles.com/api-docs (2026-11-18).
 * Download quota is spent only by an explicit downloadBytes call, never during search.
 */
internal class OpenSubtitlesClient private constructor(
    private val api: OkHttpClient, private val files: OkHttpClient,
    private val testOrigin: HttpUrl? = null,
    private val userAgent: String = "shyniflex v${BuildConfig.VERSION_NAME}",
) {
    constructor() : this(newClient(), newClient())

    suspend fun login(apiKey: String, userName: String, password: String): OpenSubtitlesAccount {
        val key = validatedKey(apiKey)
        val name = userName.trim()
        if (name.isEmpty() || name.length > 200 || password.isEmpty() || password.length > 1024)
            throw OpenSubtitlesException("OpenSubtitles 사용자 이름과 비밀번호를 확인해 주세요.")
        val body = JSONObject().put("username", name).put("password", password)
        val result = apiJson(OpenSubtitlesAccount(key), "login", body)
        val token = result.opt("token") as? String
        if (token == null || !validHeaderValue(token, 8192)) invalidResponse()
        val host = result.opt("base_url") as? String ?: OpenSubtitlesAccount.DEFAULT_HOST
        if (host !in API_HOSTS) throw OpenSubtitlesException("OpenSubtitles의 로그인 서버 주소를 확인할 수 없습니다.")
        return OpenSubtitlesAccount(key, token, cleanLabel(name, 200), host,
            result.optJSONObject("user")?.nonNegativeInt("allowed_downloads"))
    }

    suspend fun search(account: OpenSubtitlesAccount, query: OpenSubtitlesQuery): OpenSubtitlesSearchPage {
        val text = query.query.trim().lowercase(Locale.ROOT)
        val language = query.language.trim().lowercase(Locale.ROOT)
        if (text.isBlank() || text.length > 240 || query.page !in 1..100_000 ||
            (language != "all" && !Regex("[a-z]{2,3}(?:-[a-z]{2})?").matches(language)) ||
            query.seasonNumber?.let { it !in 0..10_000 } == true ||
            query.episodeNumber?.let { it !in 0..100_000 } == true ||
            query.year?.let { it !in 1800..2200 } == true)
            throw OpenSubtitlesException("검색어와 언어·시즌·회차를 확인해 주세요.")
        // Canonical ordering/lowercase/+ spaces avoid the provider's 301 normalization hop.
        val params = sortedMapOf("query" to text)
        if (language != "all") params["languages"] = language
        if (query.page > 1) params["page"] = query.page.toString()
        query.seasonNumber?.let { params["season_number"] = it.toString() }
        query.episodeNumber?.let { params["episode_number"] = it.toString() }
        query.year?.let { params["year"] = it.toString() }
        val encoded = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val result = apiJson(account, "subtitles", query = encoded)
        val rows = result.optJSONArray("data") ?: invalidResponse()
        // API pages are normally 50 rows. Do not truncate to a UI page size and lose results.
        if (rows.length() > 100) invalidResponse()
        val page = result.positiveInt("page") ?: invalidResponse()
        val totalPages = result.nonNegativeInt("total_pages") ?: invalidResponse()
        val totalCount = result.nonNegativeInt("total_count") ?: invalidResponse()
        if (page != query.page || totalPages > 100_000 || (rows.length() > 0 && totalPages < page)) invalidResponse()
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val attributes = row.optJSONObject("attributes") ?: return@mapNotNull null
            val rawFiles = attributes.optJSONArray("files") ?: return@mapNotNull null
            if (rawFiles.length() > 50) return@mapNotNull null
            val subtitleFiles = (0 until rawFiles.length()).mapNotNull { fileIndex ->
                val file = rawFiles.optJSONObject(fileIndex) ?: return@mapNotNull null
                val id = file.positiveInt("file_id") ?: return@mapNotNull null
                OpenSubtitlesFile(id, safeFileName(file.optString("file_name"), id))
            }.distinctBy { it.fileId }
            if (subtitleFiles.isEmpty()) return@mapNotNull null
            val feature = attributes.optJSONObject("feature_details")
            OpenSubtitlesResult(cleanLabel(row.optString("id"), 80),
                cleanLabel(feature?.optString("title").orEmpty(), 300),
                cleanLabel(attributes.optString("release"), 500),
                cleanLabel(attributes.optString("language"), 40),
                attributes.optDouble("ratings", Double.NaN).takeIf { it.isFinite() && it in 0.0..10.0 },
                attributes.nonNegativeInt("download_count") ?: 0, subtitleFiles)
        }
        return OpenSubtitlesSearchPage(items, page, totalPages, totalCount)
    }

    suspend fun downloadBytes(account: OpenSubtitlesAccount, fileId: Int): OpenSubtitlesDownload {
        if (fileId <= 0) throw OpenSubtitlesException("선택한 자막 파일을 확인해 주세요.")
        // Do not auto-retry this POST: the provider charges quota when issuing the link.
        val result = apiJson(account, "download", JSONObject().put("file_id", fileId))
        var url = (result.opt("link") as? String)?.toHttpUrlOrNull() ?: invalidResponse()
        val name = safeFileName(result.optString("file_name"), fileId)
        val remaining = result.nonNegativeInt("remaining")
        val reset = (result.opt("reset_time_utc") as? String)
            ?.takeIf { it.length <= 40 && Regex("[0-9T:Z.+ -]+").matches(it) }
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!allowedFileUrl(url)) throw OpenSubtitlesException("안전한 OpenSubtitles 다운로드 주소가 아닙니다.")
            // Deliberately anonymous: never copy API, Authorization or Shyni headers to a file URL.
            val response = exchange(files, Request.Builder().url(url).header("User-Agent", userAgent).build(), MAX_FILE_BYTES)
            if (response.status in REDIRECTS) {
                if (hop == MAX_REDIRECTS) throw OpenSubtitlesException("자막 다운로드 주소가 너무 많이 변경되었습니다.")
                url = response.location?.let(url::resolve) ?: invalidResponse()
            } else {
                checkStatus(response.status)
                if (response.bytes.isEmpty()) throw OpenSubtitlesException("다운로드한 자막 파일이 비어 있습니다.")
                return OpenSubtitlesDownload(response.bytes, name, remaining, reset)
            }
        }
        invalidResponse()
    }

    private suspend fun apiJson(account: OpenSubtitlesAccount, endpoint: String, body: JSONObject? = null,
        query: String? = null): JSONObject {
        val key = validatedKey(account.apiKey)
        if (account.baseHost !in API_HOSTS || (account.token != null && !validHeaderValue(account.token, 8192)))
            throw OpenSubtitlesException("저장된 OpenSubtitles 계정을 다시 연결해 주세요.", loginRequired = true)
        if (account.baseHost == "vip-api.opensubtitles.com" && account.token == null)
            throw OpenSubtitlesException("OpenSubtitles 계정에 다시 로그인해 주세요.", loginRequired = true)
        val origin = testOrigin ?: "https://${account.baseHost}/".toHttpUrlOrNull()!!
        var url = origin.newBuilder().encodedPath("/api/v1/$endpoint").encodedQuery(query).build()
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = Request.Builder().url(url).header("Api-Key", key).header("User-Agent", userAgent)
                .header("Accept", "application/json")
            account.token?.let { request.header("Authorization", "Bearer $it") }
            body?.let { request.post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
            val response = exchange(api, request.build(), MAX_JSON_BYTES)
            if (response.status in REDIRECTS) {
                // Only a same-origin search normalization may keep the provider credentials.
                val next = response.location?.let(url::resolve)
                if (body != null || hop == MAX_REDIRECTS || next == null || !sameOrigin(next, origin) ||
                    next.encodedPath != "/api/v1/$endpoint" || next.username.isNotEmpty() || next.password.isNotEmpty() || next.fragment != null)
                    throw OpenSubtitlesException("OpenSubtitles의 응답 주소를 확인할 수 없습니다.", response.status)
                url = next
            } else {
                checkStatus(response.status)
                return try { JSONObject(String(response.bytes, Charsets.UTF_8)) }
                    catch (_: Exception) { invalidResponse() }
            }
        }
        invalidResponse()
    }

    private data class Reply(val status: Int, val location: String?, val bytes: ByteArray)

    private suspend fun exchange(client: OkHttpClient, request: Request, maximum: Int): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    OpenSubtitlesException(if (e is SocketTimeoutException) "OpenSubtitles 응답 시간이 초과되었습니다. 다시 시도해 주세요."
                        else "OpenSubtitles에 연결하지 못했습니다. 네트워크를 확인해 주세요."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val bytes = if (!response.isSuccessful) byteArrayOf() else {
                            val body = response.body ?: invalidResponse()
                            if (body.contentLength() > maximum) throw tooLarge()
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    if (call.isCanceled()) throw CancellationException()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    if (output.size().toLong() + read > maximum) throw tooLarge()
                                    output.write(buffer, 0, read)
                                }
                                output.toByteArray()
                            }
                        }
                        Reply(response.code, response.header("Location"), bytes)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error as? OpenSubtitlesException
                        ?: OpenSubtitlesException("OpenSubtitles 응답을 읽지 못했습니다. 다시 시도해 주세요."))
                }
            }
        })
    }

    private fun allowedFileUrl(url: HttpUrl): Boolean =
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.toString().length <= 16_384 &&
            (if (testOrigin != null) sameOrigin(url, testOrigin) else
                url.isHttps && url.port == 443 && (url.host == "opensubtitles.com" || url.host.endsWith(".opensubtitles.com")))

    companion object {
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        internal val API_HOSTS = setOf("api.opensubtitles.com", "vip-api.opensubtitles.com")
        private fun newClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp may retry a 503 + Retry-After: 0 even with connection retries disabled.
                // Never repeat a login or quota-spending POST behind the user's action.
                if (chain.request().method == "POST" && response.code == 503)
                    response.newBuilder().removeHeader("Retry-After").build() else response
            }.build()

        /** Loopback-only fixture seam: production callers cannot supply another host/client or trust policy. */
        internal fun forLocalTests(origin: HttpUrl): OpenSubtitlesClient {
            require(origin.scheme == "http" && origin.host in setOf("127.0.0.1", "localhost") &&
                origin.username.isEmpty() && origin.password.isEmpty() && origin.encodedPath == "/")
            return OpenSubtitlesClient(newClient(), newClient(), origin, "shyniflex synthetic-test")
        }

        internal fun validatedKey(value: String): String = value.trim().also {
            if (!validHeaderValue(it, 1024)) throw OpenSubtitlesException("유효한 OpenSubtitles 앱 API 키를 입력해 주세요.")
        }
        internal fun validHeaderValue(value: String, maximum: Int) = value.length in 1..maximum && value.all { it.code in 33..126 }
        private fun sameOrigin(left: HttpUrl, right: HttpUrl) = left.scheme == right.scheme && left.host == right.host && left.port == right.port
        private fun checkStatus(status: Int) {
            if (status in 200..299) return
            throw when (status) {
                401 -> OpenSubtitlesException("OpenSubtitles 계정에 다시 로그인해 주세요.", status, loginRequired = true)
                403 -> OpenSubtitlesException("OpenSubtitles 앱 키 또는 계정 권한을 확인해 주세요.", status)
                406 -> OpenSubtitlesException("OpenSubtitles 자막 다운로드 한도를 모두 사용했습니다. 한도가 복원된 뒤 다시 시도해 주세요.", status, quotaExceeded = true)
                429 -> OpenSubtitlesException("OpenSubtitles 요청이 많습니다. 잠시 후 다시 시도해 주세요.", status)
                else -> OpenSubtitlesException("OpenSubtitles 요청을 처리하지 못했습니다. (HTTP $status)", status)
            }
        }
        private fun invalidResponse(): Nothing = throw OpenSubtitlesException("OpenSubtitles 응답 형식을 확인할 수 없습니다.")
        private fun tooLarge() = OpenSubtitlesException("자막 파일 또는 검색 응답이 허용 크기를 초과했습니다. 자막은 8 MB까지 지원합니다.")
        private fun JSONObject.nonNegativeInt(name: String): Int? = (opt(name) as? Number)?.let {
            val number = it.toDouble()
            if (number.isFinite() && number >= 0 && number <= Int.MAX_VALUE && number == number.toInt().toDouble()) number.toInt() else null
        }
        private fun JSONObject.positiveInt(name: String) = nonNegativeInt(name)?.takeIf { it > 0 }
        private fun cleanLabel(value: String, maximum: Int) = value.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").trim().take(maximum)
        private fun safeFileName(value: String, id: Int) = cleanLabel(value.substringAfterLast('/').substringAfterLast('\\'), 240)
            .takeIf { it.isNotBlank() && it !in setOf(".", "..") } ?: "자막-$id.srt"
    }
}
