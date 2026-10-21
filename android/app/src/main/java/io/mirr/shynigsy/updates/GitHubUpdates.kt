package io.mirr.shynigsy.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal class GitHubUpdates(private val context: Context) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val folder = File(context.cacheDir, "updates").apply { mkdirs() }

    suspend fun latest(installed: Long): UpdateCandidate? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=10"
        val releases = response(url).use { value ->
            if (value.code == 404) return@withContext null
            require(value.isSuccessful)
            JSONArray(limitedBody(value, 512 * 1024))
        }
        val candidates = mutableListOf<UpdateCandidate>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: continue
            if ((0 until assets.length()).none { assets.getJSONObject(it).optString("name") == UPDATE_MANIFEST }) continue
            val manifest = releaseAsset(release, UPDATE_MANIFEST)
            require(manifest.size <= 256 * 1024)
            val data = response(manifest.url).use { value ->
                require(value.isSuccessful)
                JSONObject(limitedBody(value, 256 * 1024))
            }
            updateCandidate(release, data, context.packageName, installed)?.let(candidates::add)
        }
        candidates.maxByOrNull { it.versionCode }
    }

    suspend fun download(candidate: UpdateCandidate, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val destination = File(folder, "update-${candidate.versionCode}.apk")
        if (destination.isFile && runCatching { verifyUpdateArchive(context, destination, candidate) }.isSuccess)
            return@withContext destination
        destination.delete()
        val temporary = File(folder, "update-${candidate.versionCode}.download.apk")
        try {
            response(candidate.asset.url).use { value ->
                require(value.isSuccessful)
                val body = requireNotNull(value.body)
                require(body.contentLength() <= MAX_UPDATE_BYTES)
                var received = 0L
                var lastProgress = -1
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= candidate.asset.size && received <= MAX_UPDATE_BYTES)
                        output.write(buffer, 0, count)
                        val percent = (received * 100 / candidate.asset.size).toInt()
                        if (percent != lastProgress) { progress(percent); lastProgress = percent }
                    }
                } }
                require(received == candidate.asset.size)
            }
            verifyUpdateArchive(context, temporary, candidate)
            check(temporary.renameTo(destination))
            folder.listFiles()?.filter { it != destination && Regex("update-[0-9]+\\.(apk|download\\.apk)").matches(it.name) }
                ?.forEach { it.delete() }
            destination
        } finally { temporary.delete() }
    }

    /** Redirects for public release assets never carry Shyni account headers or GitHub credentials. */
    private fun response(initialUrl: String): Response {
        var url = initialUrl
        repeat(5) {
            val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
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
        error("Too many update redirects")
    }

    private fun limitedBody(response: Response, limit: Int): String {
        val body = requireNotNull(response.body)
        require(body.contentLength() <= limit)
        val bytes = body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= limit)
        return bytes.toString(Charsets.UTF_8)
    }
}

@Suppress("DEPRECATION")
internal fun installedVersion(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).let {
    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
}

/** A checksum alone is insufficient: an update must retain both package identity and its installed signer. */
@Suppress("DEPRECATION")
internal fun verifyUpdateArchive(context: Context, file: File, candidate: UpdateCandidate) {
    require(file.length() == candidate.asset.size && file.length() in 1..MAX_UPDATE_BYTES)
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    }
    require(hash.digest().joinToString("") { "%02x".format(it) } == candidate.sha256)
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags))
    val current = context.packageManager.getPackageInfo(context.packageName, flags)
    require(candidate.packageName == context.packageName && archive.packageName == context.packageName)
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    require(code == candidate.versionCode)
    fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
        info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }.toSet()
    require(signers(archive).isNotEmpty() && signers(archive) == signers(current))
}
