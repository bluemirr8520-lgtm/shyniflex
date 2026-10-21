package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.data.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SubtitleLoadException(val status: Int? = null, val empty: Boolean = false) : IOException()

/** Server text conversion is optional: Plex parsers also accept the original file. */
internal class SubtitleLoader(
    httpClient: OkHttpClient = OkHttpClient(),
    private val headersFor: (String) -> Map<String, String> = { emptyMap() },
) {
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    suspend fun load(track: SubtitleTrack): List<ManualSubtitleCue> {
        var failure: Exception? = null
        for (url in listOfNotNull(track.url, track.fallbackUrl).distinct()) {
            try {
                val metadataHeaders = track.headers.filterKeys {
                    !it.equals("Authorization", ignoreCase = true) && !it.equals("Cookie", ignoreCase = true)
                }
                return fetch(url, metadataHeaders + headersFor(url))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: SubtitleLoadException()
    }

    private suspend fun fetch(url: String, headers: Map<String, String>): List<ManualSubtitleCue> =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val cues = response.use {
                            if (!it.isSuccessful) throw SubtitleLoadException(status = it.code)
                            val body = it.body ?: throw SubtitleLoadException(empty = true)
                            val limit = 8L * 1024 * 1024
                            if (body.contentLength() > limit) throw SubtitleLoadException()
                            val buffer = okio.Buffer()
                            val source = body.source()
                            while (source.read(buffer, 65_536) >= 0) {
                                if (buffer.size > limit) throw SubtitleLoadException()
                                if (!continuation.isActive) return
                            }
                            parseManualSubtitleCues(decodeSubtitleBytes(buffer.readByteArray()))
                                .takeIf { parsed -> parsed.isNotEmpty() }
                                ?: throw SubtitleLoadException(empty = true)
                        }
                        if (continuation.isActive) continuation.resume(cues)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
}
