package io.mirr.shynigsy.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Server operations shared by the native player's phone and TV controls. */
internal abstract class PlayerServer(val server: String, val token: String) {
    open val shyni: ShyniClient? = null
    abstract fun headersFor(url: String): Map<String, String>
    open fun playbackUrl(spec: PlaybackSpec): String = spec.url
    abstract fun playbackHeaders(spec: PlaybackSpec): Map<String, String>
    open fun onPlaybackSourceChanged(spec: PlaybackSpec?) = Unit
    abstract suspend fun playback(item: MediaItem): PlaybackSpec
    abstract suspend fun playbackNeighbors(spec: PlaybackSpec): PlaybackNeighbors
    open suspend fun continuousPlaybackNeighbors(spec: PlaybackSpec): PlaybackNeighbors = playbackNeighbors(spec)
    abstract suspend fun renewPlayback(spec: PlaybackSpec, positionMs: Long, audioStreamIndex: Int?): PlaybackSpec
    abstract suspend fun saveProgress(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String,
        watchedMs: Long, completionEligible: Boolean)
    abstract suspend fun markPlaybackWatched(spec: PlaybackSpec)

    suspend fun playbackSubtitlesForEngine(spec: PlaybackSpec): PlaybackSubtitleInspection =
        shyni?.playbackSubtitlesForEngine(spec) ?: PlaybackSubtitleInspection(spec.subtitles, true)

    abstract suspend fun convertPlayback(spec: PlaybackSpec, height: Int?, positionMs: Long,
        audioStreamIndex: Int? = spec.selectedAudioStreamIndex, subtitleStreamIndex: Int? = null): PlaybackSpec
}

internal class ShyniPlayerServer(server: String, token: String, deviceId: String) : PlayerServer(server, token) {
    override val shyni = ShyniClient(server, token, deviceId = deviceId)
    override fun headersFor(url: String) = shyni.headersFor(url)
    override fun playbackHeaders(spec: PlaybackSpec) = shyni.playbackHeaders(spec)
    override suspend fun playback(item: MediaItem) = shyni.playback(item, deferSubtitles = true)
    override suspend fun playbackNeighbors(spec: PlaybackSpec) = shyni.playbackNeighbors(spec)
    override suspend fun continuousPlaybackNeighbors(spec: PlaybackSpec) = shyni.continuousPlaybackNeighbors(spec)
    override suspend fun convertPlayback(spec: PlaybackSpec, height: Int?, positionMs: Long,
        audioStreamIndex: Int?, subtitleStreamIndex: Int?) = shyni.convertPlayback(spec, height, positionMs, audioStreamIndex)
    override suspend fun renewPlayback(spec: PlaybackSpec, positionMs: Long, audioStreamIndex: Int?) =
        shyni.renewPlayback(spec, positionMs, audioStreamIndex)
    override suspend fun saveProgress(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String,
        watchedMs: Long, completionEligible: Boolean) =
        shyni.saveProgress(spec, positionMs, durationMs, state, watchedMs, completionEligible)
    override suspend fun markPlaybackWatched(spec: PlaybackSpec) = shyni.markPlaybackWatched(spec)
}

internal class PlexPlayerServer(server: String, token: String, deviceId: String) : PlayerServer(server, token) {
    private val plex = PlexClient(server, token, deviceId)
    private val scrobbled = mutableSetOf<Pair<Int, String?>>()
    private var activeTranscodeId: String? = null

    override fun headersFor(url: String) = plex.headersFor(url)

    // libmpv can follow redirects and load nested URLs with global HTTP headers. Give the
    // decoder a token scoped to this request URL, keeping the stored spec free of credentials.
    override fun playbackUrl(spec: PlaybackSpec): String {
        val token = headersFor(spec.url)["X-Plex-Token"]
            ?: throw ApiException("Plex 서버의 영상 주소를 확인할 수 없습니다.")
        return spec.url.toHttpUrl().newBuilder().setQueryParameter("X-Plex-Token", token).build().toString()
    }
    override fun playbackHeaders(spec: PlaybackSpec): Map<String, String> = emptyMap()
    override fun onPlaybackSourceChanged(spec: PlaybackSpec?) {
        val previous = activeTranscodeId
        val url = spec?.takeIf { it.isServerConversion }?.let { plex.resource(it.url) }
        activeTranscodeId = url?.takeIf { it.encodedPath.endsWith("/video/:/transcode/universal/start.mkv") }
            ?.queryParameter("transcodeSessionId")
        if (previous != null && previous != activeTranscodeId) plex.stopTranscode(previous)
    }
    override suspend fun playback(item: MediaItem) = plex.playback(item)
    override suspend fun playbackNeighbors(spec: PlaybackSpec) = plex.neighbors(spec)
    override suspend fun continuousPlaybackNeighbors(spec: PlaybackSpec) = plex.neighbors(spec, includePrevious = false)
    override suspend fun convertPlayback(spec: PlaybackSpec, height: Int?, positionMs: Long,
        audioStreamIndex: Int?, subtitleStreamIndex: Int?) =
        plex.convertPlayback(spec, height, positionMs, audioStreamIndex, subtitleStreamIndex)
    override suspend fun renewPlayback(spec: PlaybackSpec, positionMs: Long, audioStreamIndex: Int?) =
        convertPlayback(spec, spec.conversionHeight, positionMs, audioStreamIndex)
    override suspend fun saveProgress(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String,
        watchedMs: Long, completionEligible: Boolean) {
        val timelineState = when (state) {
            "completed", "completed_candidate" -> "stopped"
            else -> state
        }
        plex.timeline(spec, positionMs, durationMs, timelineState)
    }
    override suspend fun markPlaybackWatched(spec: PlaybackSpec) {
        val key = spec.itemId to spec.sessionId
        // The player's progress queue has one writer. EOF, pause and stop can all pass the threshold.
        if (key in scrobbled) return
        plex.setWatched(spec.itemId, true)
        scrobbled.add(key)
    }
}
