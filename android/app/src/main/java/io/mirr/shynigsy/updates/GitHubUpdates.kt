package io.mirr.shynigsy.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Injectable transport boundary; the production implementation always verifies downloaded APKs. */
internal interface UpdateSource {
    suspend fun latest(installed: Long): UpdateCandidate?
    suspend fun download(candidate: UpdateCandidate, progress: (Int) -> Unit): File
    fun verify(file: File, candidate: UpdateCandidate)
}

internal class GitHubUpdates(private val context: Context) : UpdateSource {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val transport = UpdateHttpTransport(http)
    // Small update manifests should not wait for the long timeout intended for APKs.
    // Prefer the ordinary asset URL, but move to the GitHub API after a short stall.
    private val manifestTransport = UpdateHttpTransport(http.newBuilder()
        .callTimeout(7, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).build(), browserTimeoutMillis = 3_000)
    private val folder = File(context.cacheDir, "updates").apply { mkdirs() }

    override suspend fun latest(installed: Long): UpdateCandidate? = withContext(Dispatchers.IO) {
        val api = "https://api.github.com/repos/$UPDATE_REPOSITORY"
        val packageName = context.packageName
        val abis = Build.SUPPORTED_ABIS.toList()
        if (usesPlexMiniUpdates(packageName)) {
            val refs = transport.response("$api/git/matching-refs/tags/$PLEXMINI_TAG_PREFIX").use { value ->
                require(value.isSuccessful)
                JSONArray(limitedBody(value, 1024 * 1024))
            }
            return@withContext latestPlexMiniUpdate(refs, packageName, installed, abis, readRelease = { tag ->
                coroutineContext.ensureActive()
                transport.response("$api/releases/tags/$tag").use { value ->
                    if (value.code == 404) null else {
                        require(value.isSuccessful)
                        JSONObject(limitedBody(value, 512 * 1024))
                    }
                }
            }, readManifest = ::readManifest)
        }
        val releases = transport.response("$api/releases?per_page=10").use { value ->
            require(value.isSuccessful)
            JSONArray(limitedBody(value, 512 * 1024))
        }
        inspectUpdateReleases(releases) { release ->
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@inspectUpdateReleases null
            if (release.optString("tag_name").startsWith(PLEXMINI_TAG_PREFIX)) return@inspectUpdateReleases null
            val manifest = releaseManifest(release, UPDATE_MANIFEST) ?: return@inspectUpdateReleases null
            updateCandidate(release, readManifest(manifest), packageName, installed, abis)
        }
    }

    private fun readManifest(asset: UpdateAsset): JSONObject = manifestTransport.asset(asset) { value ->
        JSONObject(limitedBody(value, 256 * 1024))
    }

    override suspend fun download(candidate: UpdateCandidate, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val destination = File(folder, "update-${candidate.versionCode}.apk")
        if (destination.isFile && runCatching { verifyUpdateArchive(context, destination, candidate) }.isSuccess)
            return@withContext destination
        destination.delete()
        val temporary = File(folder, "update-${candidate.versionCode}.download.apk")
        val downloadContext = coroutineContext
        try {
            transport.asset(candidate.asset) { value ->
                val body = requireNotNull(value.body)
                require(body.contentLength() <= MAX_UPDATE_BYTES)
                var received = 0L
                var lastProgress = -1
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        downloadContext.ensureActive()
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

    override fun verify(file: File, candidate: UpdateCandidate) = verifyUpdateArchive(context, file, candidate)

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
