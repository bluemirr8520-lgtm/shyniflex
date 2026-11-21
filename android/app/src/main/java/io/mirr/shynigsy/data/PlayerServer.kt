package io.mirr.shynigsy.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Server operations shared by the native player's phone and TV controls. */
internal abstract class PlayerServer(val server: String, val token: String) {
    open val shyni: ShyniClient? = null
    abstract fun headersFor(url: String): Map<String, String>
    open fun playbackUrl(spec: PlaybackSpec): String = spec.url
    abstract fun playbackHeaders(spec: PlaybackSpec): Map<String, String>
    abstract suspend fun playback(item: MediaItem): PlaybackSpec
    abstract suspend fun playbackNeighbors(spec: PlaybackSpec): PlaybackNeighbors
    abstract suspend fun renewPlayback(spec: PlaybackSpec, positionMs: Long, audioStreamIndex: Int?): PlaybackSpec
    abstract suspend fun saveProgress(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String,
        watchedMs: Long, completionEligible: Boolean)
    abstract suspend fun markPlaybackWatched(spec: PlaybackSpec)

    suspend fun playbackSubtitlesForEngine(spec: PlaybackSpec): PlaybackSubtitleInspection =
        shyni?.playbackSubtitlesForEngine(spec) ?: PlaybackSubtitleInspection(spec.subtitles, true)

    suspend fun convertPlayback(spec: PlaybackSpec, height: Int?, positionMs: Long,
        audioStreamIndex: Int? = spec.selectedAudioStreamIndex): PlaybackSpec =
        shyni?.convertPlayback(spec, height, positionMs, audioStreamIndex)
            ?: throw ApiException("이 서버는 원본 재생을 지원합니다.")
}

internal class ShyniPlayerServer(server: String, token: String, deviceId: String) : PlayerServer(server, token) {
    override val shyni = ShyniClient(server, token, deviceId = deviceId)
    override fun headersFor(url: String) = shyni.headersFor(url)
    override fun playbackHeaders(spec: PlaybackSpec) = shyni.playbackHeaders(spec)
    override suspend fun playback(item: MediaItem) = shyni.playback(item, deferSubtitles = true)
    override suspend fun playbackNeighbors(spec: PlaybackSpec) = shyni.playbackNeighbors(spec)
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

    override fun headersFor(url: String) = plex.headersFor(url)

    // libmpv can follow redirects and load nested URLs with global HTTP headers. Give the
    // decoder a token scoped to this request URL, keeping the stored spec free of credentials.
    override fun playbackUrl(spec: PlaybackSpec): String {
        val token = headersFor(spec.url)["X-Plex-Token"]
            ?: throw ApiException("Plex 서버의 영상 주소를 확인할 수 없습니다.")
        return spec.url.toHttpUrl().newBuilder().setQueryParameter("X-Plex-Token", token).build().toString()
    }
    override fun playbackHeaders(spec: PlaybackSpec): Map<String, String> = emptyMap()
    override suspend fun playback(item: MediaItem) = plex.playback(item)
    override suspend fun playbackNeighbors(spec: PlaybackSpec) = plex.neighbors(spec)
    override suspend fun renewPlayback(spec: PlaybackSpec, positionMs: Long, audioStreamIndex: Int?): PlaybackSpec {
        val source = spec.sourceItem ?: throw ApiException("재생 중인 Plex 항목을 확인할 수 없습니다.")
        val refreshed = plex.playback(source, selectedPartId = spec.partId)
        if (refreshed.itemId != spec.itemId || refreshed.partId != spec.partId) {
            throw ApiException("Plex 서버의 원본 파일이 변경되었습니다. 라이브러리에서 다시 열어 주세요.")
        }
        return refreshed.copy(resumeMs = positionMs, sessionId = spec.sessionId, selectedAudioStreamIndex = audioStreamIndex)
    }
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
