package io.mirr.shynigsy.player.mpv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.util.Locale

/** Media3 controls and gestures backed entirely by the bundled libmpv decoder. */
@UnstableApi
class MpvPlayer(
    context: Context,
    headers: Map<String, String> = emptyMap(),
    preferSoftwareDecode: Boolean = false,
    serverConversion: Boolean = false,
    sourceDurationMs: Long = 0,
) :
    SimpleBasePlayer(Looper.getMainLooper()), MPVLib.EventObserver,
    SurfaceHolder.Callback, TextureView.SurfaceTextureListener {

    private val app = context.applicationContext
    private val playbackProfile = MpvPlaybackProfile.forDevice(app, preferSoftwareDecode)
    val isServerConverted: Boolean = serverConversion
    private val conversionTimeline = if (serverConversion) ServerConversionTimeline(sourceDurationMs) else null
    private val originalDuration = MpvSourceDuration(sourceDurationMs)
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val requestHeaders = headers.toMap().also { values ->
        values.forEach { (name, value) ->
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid playback HTTP header" }
        }
    }
    private var alive = false
    private var released = false
    private var loaded = false
    private var loading = false
    private var requestedPlay = false
    private var playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var status = Player.STATE_IDLE
    private var failure: PlaybackException? = null
    private var item: MediaItem? = null
    private var itemUid = Any()
    private var positionMs = 0L
    private var positionUpdatedAt = SystemClock.elapsedRealtime()
    private var durationMs = C.TIME_UNSET
    private var bufferedMs = 0L
    private var pendingStartMs = 0L
    private var nativePaused = true
    private val readiness = MpvPlaybackReadiness()
    private var seekable = false
    private var playback = PlaybackParameters.DEFAULT
    private var selection = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var gain = 1f
    private var ducked = false
    private var video = VideoSize.UNKNOWN
    private var tracks = Tracks.EMPTY
    private var nativeTracks = emptyList<MpvNativeTrack>()
    private var cues = CueGroup.EMPTY_TIME_ZERO
    private var surfaceSize = Size.ZERO
    private var configuredVideoLayout: Triple<Boolean, Boolean, Float>? = null
    private var firstFrame = false
    private var nativeFirstFrameSeen = false
    private var stopRequested = false
    private var startObserved = false
    private var output: Any? = null
    private var holder: SurfaceHolder? = null
    private var texture: TextureView? = null
    private var surfaceAttached = false
    private var ownedSurface: Surface? = null
    private var focused = false
    private var focusSuppressed = false
    private var resumeAfterFocus = false
    private var handleFocus = true
    private var receiverRegistered = false
    private var sourceOrigin: String? = null
    @Volatile private var loadGeneration = 0
    private var expectedNativePath: String? = null
    private val eofState = MpvEofState()
    private var stateUpdatePosted = false
    private val stateUpdate = Runnable {
        stateUpdatePosted = false
        if (alive && !released) invalidateState()
    }
    private val eofCheck = object : Runnable {
        override fun run() {
            if (!alive || released || !loaded || stopRequested || status == Player.STATE_ENDED) return
            // A property update may be coalesced or lost on event-queue overflow.
            // MPV's keep-open EOF flag is authoritative; position alone is not.
            if (reconcileNativeEof()) invalidateState()
            if (loaded && status != Player.STATE_ENDED) main.postDelayed(this, 1_000)
        }
    }

    var onNativeSubtitleText: ((String) -> Unit)? = null
    var onNativePosition: ((Long) -> Unit)? = null

    /** Hides glyphs only; the subtitle decoder and sub-text observation stay active. */
    var nativeSubtitleVisibility: Boolean = true
        set(value) {
            field = value
            if (alive) MPVLib.setPropertyBoolean("sub-visibility", value)
        }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        dispatch {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focused = true; focusSuppressed = false; ducked = false
                    applyGain()
                    if (resumeAfterFocus && requestedPlay) MPVLib.setPropertyBoolean("pause", false)
                    resumeAfterFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyGain() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterFocus = requestedPlay
                    focusSuppressed = true
                    MPVLib.setPropertyBoolean("pause", true)
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    requestedPlay = false; resumeAfterFocus = false; focusSuppressed = false; focused = false
                    playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                    MPVLib.setPropertyBoolean("pause", true)
                }
            }
            invalidateState()
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(focusListener, main).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && alive) {
                requestedPlay = false
                playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                MPVLib.setPropertyBoolean("pause", true)
                abandonFocus()
                invalidateState()
            }
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Create MpvPlayer on the main thread" }
        synchronized(OWNER_LOCK) {
            check(owner == null) { "Release the previous MPV player before creating another" }
            owner = this
        }
        try {
            MPVLib.create(app)
            val ca = File(app.filesDir, "mpv-cacert.pem")
            if (!ca.exists()) app.assets.open("cacert.pem").use { input -> ca.outputStream().use(input::copyTo) }
            val options = linkedMapOf(
                "config" to "no", "load-scripts" to "no", "ytdl" to "no",
                // JNI itself logs mpv messages. Disable them before opening any signed URL.
                "msg-level" to "all=no", "terminal" to "no", "idle" to "yes",
                "keep-open" to "always", "keep-open-pause" to "yes", "force-window" to "no", "vo" to "null",
                "gpu-context" to "android", "opengl-es" to "yes",
                "ao" to "audiotrack,opensles", "audio-client-name" to "Shyni MPV",
                "audio-pitch-correction" to "yes", "tls-verify" to "yes", "tls-ca-file" to ca.path,
                "rebase-start-time" to "yes",
                "network-timeout" to "20", "sub-ass-override" to "no", "embeddedfonts" to "yes",
                "sub-auto" to "no", "sub-codepage" to "auto", "input-default-bindings" to "no",
                "input-vo-keyboard" to "no", "osc" to "no", "osd-level" to "0",
                "gpu-shader-cache-dir" to File(app.cacheDir, "mpv-shaders").apply { mkdirs() }.path
            )
            options.putAll(playbackProfile.mpvOptions())
            options.forEach { (key, value) -> MPVLib.setOptionString(key, value) }
            MPVLib.addObserver(this)
            MPVLib.init()
            alive = true
            observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            observe("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observe("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observe("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observe("seeking", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observe("seekable", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observe("track-list", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            observe("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            observe("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            observe("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            observe("video-params", MPVLib.MpvFormat.MPV_FORMAT_NONE)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            receiverRegistered = true
        } catch (error: Throwable) {
            MPVLib.removeObserver(this)
            if (alive) MPVLib.destroy()
            alive = false
            synchronized(OWNER_LOCK) { if (owner === this) owner = null }
            throw error
        }
    }

    override fun getState(): State {
        val playlist = item?.let {
            listOf(MediaItemData.Builder(itemUid).setMediaItem(it).setTracks(tracks)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                .setIsSeekable(seekable).setIsDynamic(durationMs == C.TIME_UNSET).build())
        }.orEmpty()
        val result = State.Builder().setAvailableCommands(COMMANDS)
            .setPlaylist(playlist).setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else status)
            .setPlayWhenReady(requestedPlay, playReason)
            .setPlaybackSuppressionReason(if (focusSuppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setPlayerError(failure).setIsLoading(loading && status == Player.STATE_BUFFERING)
            .setPlaybackParameters(playback).setTrackSelectionParameters(selection).setAudioAttributes(audioAttributes)
            .setVolume(gain).setVideoSize(video).setSurfaceSize(surfaceSize).setCurrentCues(cues)
            .setContentPositionMs(PositionSupplier { currentPositionValue() })
            .setContentBufferedPositionMs(PositionSupplier { bufferedMs.coerceAtLeast(currentPositionValue()) })
            .setTotalBufferedDurationMs(PositionSupplier { (bufferedMs - currentPositionValue()).coerceAtLeast(0) })
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setNewlyRenderedFirstFrame(firstFrame).build()
        firstFrame = false
        return result
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        loadGeneration++
        main.removeCallbacks(eofCheck); eofState.reset()
        stopRequested = true
        MPVLib.command(arrayOf("stop"))
        item = mediaItems.firstOrNull(); itemUid = Any()
        pendingStartMs = startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        rewriteConversionSource()
        positionMs = pendingStartMs; positionUpdatedAt = SystemClock.elapsedRealtime()
        originalDuration.reset()
        bufferedMs = positionMs; durationMs = conversionTimeline?.duration(null) ?: originalDuration.durationMs ?: C.TIME_UNSET
        seekable = isServerConverted && durationMs > 0
        loaded = false; loading = false; status = Player.STATE_IDLE; failure = null
        startObserved = false; firstFrame = false; nativeFirstFrameSeen = false; readiness.reset()
        tracks = Tracks.EMPTY; nativeTracks = emptyList(); video = VideoSize.UNKNOWN; clearCues()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        rewriteConversionSource()
        val media = item?.localConfiguration ?: return done()
        loadGeneration++
        main.removeCallbacks(eofCheck); eofState.reset()
        failure = null; stopRequested = false; startObserved = false; loaded = false; loading = true; status = Player.STATE_BUFFERING
        firstFrame = false; nativeFirstFrameSeen = false; readiness.reset(); bufferedMs = pendingStartMs
        nativePaused = !requestedPlay || focusSuppressed
        positionMs = pendingStartMs; positionUpdatedAt = SystemClock.elapsedRealtime()
        if (isServerConverted) {
            durationMs = conversionTimeline?.duration(null) ?: C.TIME_UNSET
            seekable = durationMs > 0
            tracks = Tracks.EMPTY; video = VideoSize.UNKNOWN
        }
        nativeTracks = emptyList()
        clearCues()
        val uri = media.uri
        expectedNativePath = uri.toString()
        val origin = "${uri.scheme}://${uri.host}:${uri.port}"
        if (sourceOrigin == null) sourceOrigin = origin
        val scopedHeaders = if (sourceOrigin == origin) requestHeaders else emptyMap()
        MPVLib.setOptionString("http-header-fields", scopedHeaders.entries.joinToString(",") { (key, value) -> "$key: $value".replace("\\", "\\\\").replace(",", "\\,") })
        MPVLib.setOptionString("start", MpvPlaybackProfile.initialStartOption(pendingStartMs, isServerConverted))
        // A fresh load retries the configured decoder after an earlier fallback.
        // Avoid reinitializing the old decoder when its setting is already right.
        if (MPVLib.getPropertyString("hwdec") != playbackProfile.hardwareDecoder)
            MPVLib.setPropertyString("hwdec", playbackProfile.hardwareDecoder)
        MPVLib.setPropertyBoolean("pause", !requestedPlay || focusSuppressed)
        applyTrackPreferences(selection)
        MPVLib.command(arrayOf("loadfile", uri.toString(), "replace"))
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        positionMs = currentPositionValue(); positionUpdatedAt = SystemClock.elapsedRealtime()
        requestedPlay = playWhenReady
        playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        if (playWhenReady) {
            if (status == Player.STATE_ENDED) { pendingStartMs = 0; handlePrepare() }
            focusSuppressed = !requestFocus()
            resumeAfterFocus = focusSuppressed
        } else { focusSuppressed = false; resumeAfterFocus = false; abandonFocus() }
        MPVLib.setPropertyBoolean("pause", !playWhenReady || focusSuppressed)
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val requested = positionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val target = conversionTimeline?.clamp(requested) ?: requested
        this.positionMs = target; pendingStartMs = target; positionUpdatedAt = SystemClock.elapsedRealtime()
        clearCues()
        if (isServerConverted) {
            if (durationMs > 0 && target >= durationMs) {
                loadGeneration++; stopRequested = true; loaded = false; loading = false
                status = Player.STATE_ENDED; bufferedMs = target; firstFrame = false
                MPVLib.setPropertyBoolean("pause", true); abandonFocus()
                return done()
            }
            // The progressive response has no Range support. A source seek is a
            // replacement request, including when the preceding request is opening.
            if (status != Player.STATE_IDLE) return handlePrepare()
            rewriteConversionSource()
            return done()
        }
        if (loaded) {
            // Drop already-posted callbacks for the old position, including EOF.
            loadGeneration++; eofState.seekStarted()
            readiness.seekStarted(); status = Player.STATE_BUFFERING; updatePlaybackReadiness()
            MPVLib.command(arrayOf("seek", seconds(target), "absolute+exact"))
        }
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        positionMs = currentPositionValue(); positionUpdatedAt = SystemClock.elapsedRealtime()
        playback = PlaybackParameters(playbackParameters.speed.coerceIn(0.25f, 4f), 1f)
        MPVLib.setPropertyDouble("speed", playback.speed.toDouble())
        return done()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        gain = volume.coerceIn(0f, 1f); applyGain(); return done()
    }

    override fun handleSetAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): ListenableFuture<*> {
        this.audioAttributes = audioAttributes; handleFocus = handleAudioFocus
        if (!handleAudioFocus) abandonFocus()
        return done()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        selection = trackSelectionParameters
        applyTrackPreferences(selection)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        loadGeneration++
        main.removeCallbacks(eofCheck); eofState.reset()
        positionMs = currentPositionValue(); pendingStartMs = positionMs
        stopRequested = true; loaded = false; loading = false; status = Player.STATE_IDLE
        readiness.reset()
        MPVLib.command(arrayOf("stop")); clearCues(); abandonFocus()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (released) return done()
        released = true
        loadGeneration++
        main.removeCallbacksAndMessages(null)
        stateUpdatePosted = false
        abandonFocus()
        if (receiverRegistered) { app.unregisterReceiver(noisy); receiverRegistered = false }
        clearOutput()
        MPVLib.removeObserver(this)
        if (alive) { alive = false; MPVLib.destroy() }
        synchronized(OWNER_LOCK) { if (owner === this) owner = null }
        status = Player.STATE_IDLE; loading = false; loaded = false; clearCues()
        readiness.reset()
        return done()
    }

    private fun applyTrackPreferences(parameters: TrackSelectionParameters) {
        MPVLib.setPropertyString("alang", parameters.preferredAudioLanguages.joinToString(","))
        MPVLib.setPropertyString("slang", parameters.preferredTextLanguages.joinToString(","))
        for ((type, property) in listOf(C.TRACK_TYPE_AUDIO to "aid", C.TRACK_TYPE_TEXT to "sid")) {
            if (parameters.disabledTrackTypes.contains(type)) {
                MPVLib.setPropertyString(property, "no")
                if (type == C.TRACK_TYPE_TEXT) clearCues()
            } else {
                val override = parameters.overrides.values.firstOrNull { it.type == type && it.trackIndices.isNotEmpty() }
                val format = override?.let { it.mediaTrackGroup.getFormat(it.trackIndices[0]) }
                if (format?.id?.toIntOrNull() != null) MPVLib.setPropertyString(property, format.id!!)
                else if (loaded && !isTypeSelected(type)) MPVLib.setPropertyString(property, "auto")
            }
        }
    }

    private fun isTypeSelected(type: Int) = tracks.groups.any { it.type == type && it.isSelected }

    private fun rewriteConversionSource() {
        val timeline = conversionTimeline ?: return
        val current = item ?: return
        val uri = current.localConfiguration?.uri ?: return
        val load = timeline.reopen(uri.toString(), pendingStartMs)
        pendingStartMs = load.sourcePositionMs
        item = current.buildUpon().setUri(Uri.parse(load.url)).build()
    }

    /** FFmpeg's original global stream index, never an audio-track ordinal. */
    fun selectedAudioStreamIndex(): Int? {
        if (!alive || !loaded) return null
        if (C.TRACK_TYPE_AUDIO in selection.disabledTrackTypes) return null
        val override = selection.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.trackIndices.isNotEmpty() }
        val selectedId = override?.let { it.mediaTrackGroup.getFormat(it.trackIndices[0]).id?.toIntOrNull() }
        return (nativeTracks.firstOrNull { it.type == "audio" && it.id == selectedId }
            ?: nativeTracks.firstOrNull { it.type == "audio" && it.selected })?.streamIndex
    }

    /** Shyni's embedded stream_index is FFmpeg's global stream index, not the subtitle ordinal. */
    fun subtitleTrackIdForStreamIndex(streamIndex: Int): String? {
        if (!alive || !loaded || streamIndex < 0) return null
        return nativeTracks.firstOrNull { it.type == "sub" && it.streamIndex == streamIndex }?.id?.toString()
    }

    /** A strict native list and decoded color properties are required before trying Exo. */
    internal fun exoProbeEvidence(): MpvExoProbe? {
        if (!alive || !loaded) return null
        val tracks = MpvTrackSnapshot.exoEvidence(MPVLib.getPropertyString("track-list")) ?: return null
        return MpvExoProbe(tracks, MPVLib.getPropertyString("video-dec-params/pixelformat"),
            MPVLib.getPropertyString("video-dec-params/hw-pixelformat"),
            MPVLib.getPropertyString("video-dec-params/primaries"), MPVLib.getPropertyString("video-dec-params/gamma"),
            MPVLib.getPropertyInt("video-dec-params/w"), MPVLib.getPropertyInt("video-dec-params/h"),
            MPVLib.getPropertyDouble("container-fps"))
    }

    /** Source chapters are read only from the currently loaded, original MPV file. */
    internal fun nativeChapterCredits(expectedSourceDurationMs: Long): io.mirr.shynigsy.data.PlaybackMarker? {
        if (!alive || !loaded || !nativeFirstFrameSeen || released || stopRequested || isServerConverted) return null
        val expectedPath = expectedNativePath ?: return null
        if (MPVLib.getPropertyString("path") != expectedPath) return null
        val count = MPVLib.getPropertyInt("chapter-list/count")?.takeIf { it in 1..64 } ?: return null
        val duration = MPVLib.getPropertyDouble("duration") ?: return null
        val chapters = ArrayList<MpvChapter>(count)
        for (index in 0 until count) {
            val time = MPVLib.getPropertyDouble("chapter-list/$index/time") ?: return null
            val title = MPVLib.getPropertyString("chapter-list/$index/title")
            chapters.add(MpvChapter(time, title))
        }
        if (MPVLib.getPropertyString("path") != expectedPath) return null
        return chapterCreditsMarker(chapters, duration, expectedSourceDurationMs, serverConversion = false)
    }

    private fun refreshTracks(snapshot: String? = null) {
        if (!alive || !loaded) return
        // One snapshot replaces hundreds of synchronous core round trips for a
        // film with many subtitle tracks. Subsequent updates arrive via observe.
        val parsed = MpvTrackSnapshot.parse(snapshot ?: MPVLib.getPropertyString("track-list") ?: return) ?: return
        nativeTracks = parsed
        val groups = ArrayList<Tracks.Group>()
        for (track in parsed) {
            val flags = (if (track.default) C.SELECTION_FLAG_DEFAULT else 0) or
                (if (track.forced) C.SELECTION_FLAG_FORCED else 0)
            val format = Format.Builder().setId(track.id.toString()).setLanguage(track.language)
                .setLabel(track.title).setCodecs(track.codec)
                .setSampleMimeType(mime(track.type, track.codec)).setSelectionFlags(flags)
                .setRoleFlags(if (track.hearingImpaired) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
            if (track.type == "audio") {
                track.audioChannels?.let(format::setChannelCount)
                track.sampleRate?.let(format::setSampleRate)
            }
            if (track.type == "video") {
                track.width?.let(format::setWidth)
                track.height?.let(format::setHeight)
            }
            groups.add(Tracks.Group(TrackGroup("mpv-${track.type}-${track.id}", format.build()), false,
                intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(track.selected)))
        }
        tracks = Tracks(groups)
    }

    private fun updateVideoSize() {
        val width = MPVLib.getPropertyInt("dwidth") ?: 0
        val height = MPVLib.getPropertyInt("dheight") ?: 0
        video = if (width > 0 && height > 0) VideoSize(width, height) else VideoSize.UNKNOWN
    }

    private fun setText(text: String) {
        cues = if (text.isBlank()) CueGroup.EMPTY_TIME_ZERO else CueGroup(listOf(Cue.Builder().setText(text).build()), positionMs * 1000)
        onNativeSubtitleText?.invoke(text)
    }
    private fun clearCues() = setText("")
    private fun observe(name: String, format: Int) = MPVLib.observeProperty(name, format)

    override fun eventProperty(property: String) = dispatchNative {
        when (property) {
            "track-list" -> { nativeTracks = emptyList(); tracks = Tracks.EMPTY }
            "video-params" -> if (loaded) updateVideoSize()
            "sub-text" -> clearCues()
            "eof-reached" -> reconcileNativeEof()
        }
        invalidateState()
    }
    override fun eventProperty(property: String, value: Long) = dispatchNative { invalidateState() }
    override fun eventProperty(property: String, value: String) = dispatchNative {
        if (!loaded) return@dispatchNative
        when (property) {
            "track-list" -> refreshTracks(value)
            "sub-text" -> setText(value)
            "sid" -> clearCues()
        }
        invalidateState()
    }
    override fun eventProperty(property: String, value: Double) = dispatchNative {
        if (!loaded) return@dispatchNative
        when (property) {
            "time-pos" -> {
                val nativePosition = (value * 1000).toLong().coerceAtLeast(0)
                positionMs = conversionTimeline?.sourcePosition(nativePosition) ?: nativePosition
                positionUpdatedAt = SystemClock.elapsedRealtime(); onNativePosition?.invoke(positionMs)
            }
            "duration" -> durationMs = sourceDuration(value)
            "demuxer-cache-duration" -> {
                val cacheDuration = (value * 1000).toLong().coerceAtLeast(0)
                bufferedMs = conversionTimeline?.let { timeline ->
                    timeline.bufferedPosition((positionMs - timeline.streamStartPositionMs).coerceAtLeast(0), cacheDuration)
                } ?: (positionMs + cacheDuration)
            }
        }
        scheduleStateUpdate()
    }
    override fun eventProperty(property: String, value: Boolean) = dispatchNative {
        if (!loaded) return@dispatchNative
        when (property) {
            "pause" -> {
                positionMs = currentPositionValue(); positionUpdatedAt = SystemClock.elapsedRealtime(); nativePaused = value
                if (value) reconcileNativeEof()
            }
            "seekable" -> seekable = if (isServerConverted) durationMs > 0 else value
            "paused-for-cache" -> {
                freezePlaybackClock()
                readiness.cachePausedChanged(value)
                updatePlaybackReadiness()
            }
            "eof-reached" -> if (value) reconcileNativeEof(observedEof = true)
            "seeking" -> {
                val seeking = MPVLib.getPropertyBoolean("seeking") ?: value
                eofState.nativeSeekingChanged(seeking)
                if (seeking) {
                    freezePlaybackClock(); readiness.seekStarted(); updatePlaybackReadiness()
                } else reconcileNativeEof()
            }
        }
        invalidateState()
    }
    override fun event(eventId: Int) = dispatchNative {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                if (!isExpectedNativeSource()) return@dispatchNative
                startObserved = true; loaded = false; nativeFirstFrameSeen = false; loading = true; status = Player.STATE_BUFFERING
                readiness.reset(); clearCues()
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                // Queued callbacks from the replaced response must not relabel
                // its old local clock with the new request's source offset.
                if (!isExpectedNativeSource()) return@dispatchNative
                loaded = true
                configuredVideoLayout?.let { setVideoLayout(it.first, it.second, it.third) }
                durationMs = sourceDuration(MPVLib.getPropertyDouble("duration"))
                seekable = if (isServerConverted) durationMs > 0 else MPVLib.getPropertyBoolean("seekable") == true
                nativePaused = MPVLib.getPropertyBoolean("pause") ?: (!requestedPlay || focusSuppressed)
                readiness.fileLoaded(MPVLib.getPropertyBoolean("paused-for-cache") == true)
                updatePlaybackReadiness()
                synchronizeNativePosition()
                refreshTracks(); applyTrackPreferences(selection); updateVideoSize()
                MPVLib.setPropertyBoolean("sub-visibility", nativeSubtitleVisibility)
                setText(MPVLib.getPropertyString("sub-text").orEmpty())
                reconcileNativeEof()
                main.removeCallbacks(eofCheck)
                if (status != Player.STATE_ENDED) main.postDelayed(eofCheck, 1_000)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // A queued restart from an earlier seek must not complete a newer one.
                if (loaded && !stopRequested && status != Player.STATE_ENDED &&
                    MPVLib.getPropertyBoolean("seeking") != true) {
                    eofState.playbackRestarted()
                    synchronizeNativePosition()
                    readiness.playbackRestarted(); updatePlaybackReadiness()
                    firstFrame = true; nativeFirstFrameSeen = true
                    // A seek inside one cue may not emit another sub-text change.
                    setText(MPVLib.getPropertyString("sub-text").orEmpty())
                    reconcileNativeEof()
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                reconcileNativeEof()
                if (startObserved && !stopRequested && status != Player.STATE_ENDED) {
                    // keep-open makes normal EOF observable; an early unload is a real error.
                    status = Player.STATE_IDLE; loading = false
                    failure = PlaybackException("영상을 재생하지 못했습니다. 서버 연결, 재생 권한 또는 파일을 확인해 주세요.", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                    abandonFocus()
                }
                main.removeCallbacks(eofCheck)
                startObserved = false
            }
            MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                if (MPVLib.getPropertyBoolean("seeking") != false) {
                    freezePlaybackClock(); readiness.seekStarted(); updatePlaybackReadiness()
                    eofState.nativeSeekingChanged(true)
                }
                clearCues()
            }
            MPVLib.MpvEvent.MPV_EVENT_QUEUE_OVERFLOW -> reconcileNativeEof()
        }
        invalidateState()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        clearOutput(); output = videoOutput
        when (videoOutput) {
            is SurfaceView -> attachHolder(videoOutput.holder)
            is SurfaceHolder -> attachHolder(videoOutput)
            is Surface -> attach(videoOutput)
            is TextureView -> {
                texture = videoOutput; videoOutput.surfaceTextureListener = this
                if (videoOutput.isAvailable) videoOutput.surfaceTexture?.let { onSurfaceTextureAvailable(it, videoOutput.width, videoOutput.height) }
            }
        }
        return done()
    }
    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput === output) clearOutput()
        return done()
    }
    private fun attachHolder(value: SurfaceHolder) {
        holder = value; value.addCallback(this)
        if (value.surface.isValid) { surfaceSize = Size(value.surfaceFrame.width(), value.surfaceFrame.height()); attach(value.surface) }
    }
    /** MPV owns aspect correction inside its Surface; resizing the host alone cannot stretch video. */
    fun setVideoLayout(keepAspect: Boolean, crop: Boolean, zoom: Float = 1f) {
        configuredVideoLayout = Triple(keepAspect, crop, zoom)
        if (!alive) return
        MPVLib.setPropertyString("video-unscaled", "no")
        MPVLib.setPropertyBoolean("keepaspect", keepAspect)
        MPVLib.setPropertyDouble("panscan", if (crop) 1.0 else 0.0)
        MPVLib.setPropertyDouble("video-zoom", kotlin.math.ln(zoom.toDouble()) / kotlin.math.ln(2.0))
    }

    private fun attach(surface: Surface) {
        if (!alive || !surface.isValid) return
        MPVLib.attachSurface(surface); surfaceAttached = true
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyBoolean("force-window", true)
        if (surfaceSize.width > 0 && surfaceSize.height > 0) MPVLib.setPropertyString("android-surface-size", "${surfaceSize.width}x${surfaceSize.height}")
        configuredVideoLayout?.let { setVideoLayout(it.first, it.second, it.third) }
    }
    private fun detach() {
        if (alive && surfaceAttached) {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyBoolean("force-window", false)
            MPVLib.detachSurface()
        }
        surfaceAttached = false
        ownedSurface?.release(); ownedSurface = null
    }
    private fun clearOutput() {
        holder?.removeCallback(this); holder = null
        if (texture?.surfaceTextureListener === this) texture?.surfaceTextureListener = null
        texture = null; detach(); output = null; surfaceSize = Size.ZERO
    }
    override fun surfaceCreated(holder: SurfaceHolder) { if (this.holder === holder) attach(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (this.holder !== holder || !alive) return
        surfaceSize = Size(width, height); MPVLib.setPropertyString("android-surface-size", "${width}x$height"); invalidateState()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (this.holder === holder) { detach(); surfaceSize = Size.ZERO; invalidateState() } }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceSize = Size(width, height); ownedSurface = Surface(surface); attach(ownedSurface!!); invalidateState()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceSize = Size(width, height); if (alive) MPVLib.setPropertyString("android-surface-size", "${width}x$height"); invalidateState()
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { detach(); surfaceSize = Size.ZERO; return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun currentPositionValue(): Long {
        val elapsed = if (loaded && readiness.ready && requestedPlay && !nativePaused && !focusSuppressed && status == Player.STATE_READY) SystemClock.elapsedRealtime() - positionUpdatedAt else 0
        val value = positionMs + (elapsed * playback.speed).toLong()
        return if (durationMs > 0) value.coerceIn(0, durationMs) else value.coerceAtLeast(0)
    }
    private fun freezePlaybackClock() {
        positionMs = currentPositionValue()
        positionUpdatedAt = SystemClock.elapsedRealtime()
    }
    private fun synchronizeNativePosition() {
        MPVLib.getPropertyDouble("time-pos")?.takeIf { it.isFinite() && it >= 0 }?.let { nativeTime ->
            val nativePosition = (nativeTime * 1000).toLong()
            positionMs = conversionTimeline?.sourcePosition(nativePosition) ?: nativePosition
        }
        positionUpdatedAt = SystemClock.elapsedRealtime()
    }
    private fun updatePlaybackReadiness() {
        if (!loaded || stopRequested || failure != null || status == Player.STATE_ENDED || status == Player.STATE_IDLE) return
        status = if (readiness.ready) Player.STATE_READY else Player.STATE_BUFFERING
        loading = !readiness.ready
    }
    private fun requestFocus(): Boolean {
        if (!handleFocus || gain == 0f) return true
        if (focused) return true
        focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focused
    }
    private fun abandonFocus() { if (focused || focusSuppressed) audio.abandonAudioFocusRequest(focusRequest); focused = false; ducked = false }
    private fun applyGain() { if (alive) MPVLib.setPropertyDouble("volume", (gain * if (ducked) 20 else 100).toDouble()) }
    private fun sourceDuration(nativeDurationSeconds: Double?): Long =
        if (isServerConverted) conversionTimeline?.duration(MpvSourceDuration.nativeMilliseconds(nativeDurationSeconds)) ?: C.TIME_UNSET
        else originalDuration.observe(nativeDurationSeconds) ?: C.TIME_UNSET

    /** Main-thread reconciliation for hosts; never infers EOF from the progress clock. */
    fun reconcilePlaybackCompletion(): Boolean {
        if (!alive || released) return false
        if (reconcileNativeEof()) invalidateState()
        return status == Player.STATE_ENDED
    }

    private fun reconcileNativeEof(observedEof: Boolean? = null): Boolean {
        if (!loaded || stopRequested || status == Player.STATE_ENDED || !isExpectedNativeSource()) return false
        val eof = MPVLib.getPropertyBoolean("eof-reached") ?: observedEof
        if (eof != true) return false
        val seeking = MPVLib.getPropertyBoolean("seeking") == true
        eofState.nativeSeekingChanged(seeking)
        if (!eofState.confirm(loaded, stopRequested, eof, seeking)) return false
        status = Player.STATE_ENDED; loading = false
        if (durationMs > 0) positionMs = durationMs
        positionUpdatedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(eofCheck)
        abandonFocus()
        return true
    }

    private fun isExpectedNativeSource(): Boolean {
        if (!isServerConverted) return true
        val path = MPVLib.getPropertyString("path")
        return path == null || expectedNativePath == null || path == expectedNativePath
    }

    private fun dispatch(action: () -> Unit) { main.post { if (alive && !released) action() } }
    private fun scheduleStateUpdate() {
        if (stateUpdatePosted) return
        stateUpdatePosted = true
        // Position/cache observations often arrive together. Controls and EOF
        // still publish immediately; these numeric updates share one UI frame.
        main.postDelayed(stateUpdate, 16)
    }
    private fun dispatchNative(action: () -> Unit) {
        val generation = loadGeneration
        main.post { if (alive && !released && generation == loadGeneration) action() }
    }
    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    companion object {
        private val OWNER_LOCK = Any()
        private var owner: MpvPlayer? = null
        private fun seconds(milliseconds: Long) = String.format(Locale.ROOT, "%.3f", milliseconds / 1000.0)
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_VOLUME,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_GET_TEXT, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_MEDIA_ITEMS_METADATA, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_GET_TRACKS
        ).build()
        private fun mime(type: String, codec: String): String = when (type) {
            "video" -> when (codec) { "h264" -> MimeTypes.VIDEO_H264; "hevc" -> MimeTypes.VIDEO_H265; "av1" -> MimeTypes.VIDEO_AV1; "vp9" -> MimeTypes.VIDEO_VP9; else -> "video/x-$codec" }
            "audio" -> when (codec) {
                "aac" -> MimeTypes.AUDIO_AAC; "ac3" -> MimeTypes.AUDIO_AC3; "eac3" -> MimeTypes.AUDIO_E_AC3
                "dts" -> MimeTypes.AUDIO_DTS; "truehd" -> MimeTypes.AUDIO_TRUEHD; "flac" -> MimeTypes.AUDIO_FLAC
                "opus" -> MimeTypes.AUDIO_OPUS; "vorbis" -> MimeTypes.AUDIO_VORBIS; "mp3" -> MimeTypes.AUDIO_MPEG
                else -> if (codec.startsWith("pcm_")) MimeTypes.AUDIO_RAW else "audio/x-$codec"
            }
            else -> when (codec) {
                "ass", "ssa" -> MimeTypes.TEXT_SSA; "subrip", "srt" -> MimeTypes.APPLICATION_SUBRIP
                "webvtt" -> MimeTypes.TEXT_VTT; "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS
                "dvd_subtitle" -> MimeTypes.APPLICATION_VOBSUB; "dvb_subtitle" -> MimeTypes.APPLICATION_DVBSUBS
                "eia_608" -> MimeTypes.APPLICATION_CEA608; "eia_708" -> MimeTypes.APPLICATION_CEA708
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}
