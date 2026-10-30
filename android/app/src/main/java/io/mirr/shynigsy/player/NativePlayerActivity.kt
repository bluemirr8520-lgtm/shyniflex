package io.mirr.shynigsy.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mirr.shynigsy.player.mpv.MpvPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import io.mirr.shynigsy.R
import io.mirr.shynigsy.deviceDisplayName
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.PlaybackCodecInfo
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackMarker
import io.mirr.shynigsy.data.SpoilerAccessRegistry
import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.ShyniClient
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.subtitles.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

private fun Player.reconcilePlaybackCompletion(): Boolean =
    (this as? MpvPlayer)?.reconcilePlaybackCompletion() ?: (playbackState == Player.STATE_ENDED)

private fun Player.selectedAudioStreamIndex(): Int? = (this as? MpvPlayer)?.selectedAudioStreamIndex()
private fun Player.subtitleTrackIdForStreamIndex(index: Int): String? =
    (this as? MpvPlayer)?.subtitleTrackIdForStreamIndex(index)

/** MPV decodes media while the original Shyni controls and subtitle Canvas remain native. */
class NativePlayerActivity : AppCompatActivity() {
    private val television by lazy { isTelevision() }
    private lateinit var spec: PlaybackSpec
    private lateinit var client: ShyniClient
    private lateinit var playerView: PlayerView
    private lateinit var gestures: Media3GestureView
    private lateinit var lockButton: Button
    private lateinit var segmentSkipButton: Button
    private lateinit var segmentActions: LinearLayout
    private lateinit var creditsNextButton: Button
    private val tvSegmentFocusGate = TvSegmentFocusGate()
    private var remoteSeek: RemoteSeekTimeline? = null
    private var remoteSeekKey = KeyEvent.KEYCODE_UNKNOWN
    private var remoteSeekLastEventAt = 0L
    private val remoteSeekFrame = object : Runnable {
        override fun run() {
            val timeline = remoteSeek ?: return
            val now = SystemClock.elapsedRealtime()
            if (!visible || exiting || !gestures.isSeekGestureActive || now - remoteSeekLastEventAt > 1500) {
                finishRemoteSeeking()
                return
            }
            gestures.previewRemoteSeek(timeline.targetAt(now))
            handler.postDelayed(this, 16)
        }
    }
    private var activeSkipMarker: PlaybackMarker? = null
    private var playerGestureActive = false
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var subtitleOverlay: PlexSubtitleOverlayView
    private var subtitleTimeline: SubtitleTimeline? = null
    private var subtitleJob: Job? = null
    private var subtitleListJob: Job? = null
    private var subtitleListFailed = false
    private var subtitleManuallySelected = false
    private var deferredSubtitlePreference: SubtitleTrack? = null
    private var playbackFirstFrameReported = false
    private var subtitleDialog: AlertDialog? = null
    private var tvSubtitlePositionDialog: TvSubtitlePositionDialog? = null
    private var subtitleSelectionRevision = 0
    private var externalSubtitleId: Int? = null
    private var embeddedServerSubtitleId: Int? = null
    private var pendingEmbeddedSubtitle: SubtitleTrack? = null
    private var subtitlesEnabled = true
    private var awaitingEmbeddedSubtitle = false
    private var subtitleOffsetMs = 0L
    private var subtitleFont = "system"
    private var subtitleAppearance = PlexSubtitleAppearance()
    private lateinit var subtitleLoader: SubtitleLoader
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var resizeScale = 1f
    private var resizeNotice: Toast? = null
    private var player: Player? = null
    private val mpvSubtitleController = MpvSubtitleController { native -> (player as? MpvPlayer)?.nativeSubtitleVisibility = native }
    private var engineSelectionJob: Job? = null
    private var engineGeneration = 0L
    private var exoValidated = false
    private var forceMpvSession: String? = null
    private var mpvProbePending = false
    private var mpvProbeJob: Job? = null
    private var finishMpvProbe: (() -> Unit)? = null
    private var exoRestoreSubtitlePending = false
    private data class EngineCandidate(val info: PlaybackCodecInfo, val subtitles: List<SubtitleTrack>)
    private var speed = 1f
    private var conversionLoading = false
    private var conversionJob: Job? = null
    private var conversionResumeWhenVisible: Boolean? = null
    private var conversionRestore: ConversionRestore? = null
    private var playbackNeighbors: PlaybackNeighbors? = null
    private var neighborsJob: Job? = null
    private var navigationLoading = false
    private var playbackMetadataJob: Job? = null
    private val playbackMetadataGate = PlaybackMetadataGate()
    private var pendingAudioPreference: PlaybackTrackPreference? = null
    private var pendingTextPreference: PlaybackTrackPreference? = null
    private var unlockKeyPending = false
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var prepared = false
    private var completed = false
    private var endingAfterCompletion = false
    private val watchedReached = ConcurrentHashMap.newKeySet<String>()
    private val watchedConfirmed = ConcurrentHashMap.newKeySet<String>()
    private val watchedQueued = ConcurrentHashMap.newKeySet<String>()
    private val watchedAttemptAt = mutableMapOf<String, Long>()
    private val completionGates = mutableMapOf<String, PlaybackCompletionGate>()
    private var visible = false
    private var activityStarted = false
    private var spoilerReauthenticationJob: Job? = null
    private var spoilerRotationJob: Job? = null
    private var spoilerRecovery: ConversionRestore? = null
    private var spoilerPreparing = false
    private val playbackAuthentication = PlaybackAuthenticationEpoch()
    private val presenceLifecycle: PlaybackPresenceLifecycle by lazy {
        PlaybackPresenceLifecycle(open = { source, onForceStop ->
            val label = deviceDisplayName(android.os.Build.MANUFACTURER, android.os.Build.MODEL, television)
                .removeSuffix(if (television) " (shyniflex TV)" else " (shyniflex)")
            client.createPlaybackPresence(source, label, if (television) "tv" else "mobile", onForceStop)?.let { connection ->
                object : PlaybackPresenceConnection {
                    override fun update(positionMs: Long, durationMs: Long, state: String) =
                        connection.update(positionMs, durationMs, state)
                    override fun close(endSession: Boolean) = connection.close(endSession)
                }
            }
        }, onForceStop = { source, revision, _ ->
            handler.post {
                if (!isDestroyed && !exiting && ::spec.isInitialized &&
                    samePlaybackPresenceSource(source, spec) && presenceLifecycle.isCurrent(source, revision)) {
                    toast("서버에서 재생을 중지했습니다.")
                    leavePlayer()
                }
            }
        })
    }
    @Volatile private var progressDeliveryJob: Job? = null
    private var exiting = false
    private var refreshing = false
    private var resumeWhenVisible = true
    private var watchedMs = 0L
    private var watchingSince: Long? = null
    private var lastSyncAt = 0L
    private var syncErrorShown: String? = null
    private var pendingSeek: Long? = null
    private var subtitleDragOrigin: PlexSubtitleAppearance? = null
    private var subtitleDragBounds: SubtitleDragBounds? = null
    private var subtitleDragOffset: SubtitleDragOffset? = null
    private val handler = Handler(Looper.getMainLooper())
    private val engineSelectionTimeout = Runnable {
        engineSelectionJob?.cancel(); engineSelectionJob = null
        engineGeneration++
        if (player == null && !exiting && !isDestroyed && ::spec.isInitialized && ::client.isInitialized &&
            !closeLockedSpoilerPlayback()) startPreparedPlayer(null, false)
    }
    private val engineProbeTimeout = Runnable { finishMpvProbe?.invoke() }
    private val hideUnlock = Runnable { if (::gestures.isInitialized && gestures.locked) hideLockControl() }
    private val conversionTimeout = Runnable { restoreConversion("영상 응답 시간이 초과되었습니다.") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = PlaybackProgressQueue<ProgressUpdate>()
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val font = withContext(Dispatchers.IO) { SubtitleFontStore(this@NativePlayerActivity).importFont(uri) }
                subtitleFont = font.key
                applySubtitleStyle()
                toast("${font.label} 글꼴을 적용했습니다.")
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (error: Exception) {
                toast(error.message ?: "글꼴을 불러오지 못했습니다. TTF 또는 OTF 파일을 선택하세요.")
            }
        }
    }

    private data class ProgressUpdate(val spec: PlaybackSpec, val position: Long, val duration: Long,
        val state: String, val watched: Long, val acknowledgement: CompletableDeferred<Boolean>? = null,
        val thresholdAttempt: Boolean = false, val completionEligible: Boolean = false,
        val authenticationEpoch: Long = 0, val accountScope: String? = null,
        val privateGrantRevision: Long? = null, val choiceRevision: Long = 0)

    private data class ConversionRestore(val spec: PlaybackSpec, val position: Long, val playing: Boolean,
        val subtitle: SubtitleTrack?, val audio: PlaybackTrackPreference?, val text: PlaybackTrackPreference?,
        val audioIndex: Int?, val duration: Long)

    private val ticker = object : Runnable {
        override fun run() {
            if (exiting || !visible) return
            if (closeLockedSpoilerPlayback()) return
            ensureSpoilerPlaybackReauthentication()
            val active = player
            if (active != null && playbackEngineSettled(mpvProbePending, active is ExoPlayer && !exoValidated) &&
                !refreshing && !gestures.isSeekGestureActive && active.reconcilePlaybackCompletion()) {
                finishCompletedPlayback()
                return
            }
            // Use one clock sample for both visibility and its next deadline.
            // Resampling after crossing the boundary could hide the action until the next 500 ms tick.
            val segmentPosition = active?.currentPosition ?: lastPosition
            if (active != null && prepared) {
                capturePosition()
                updateSubtitleText()
                updateSegmentSkipButton(segmentPosition)
                settleWatchTime(visible && active.isPlaying)
                if (!gestures.isSeekGestureActive && active.playbackState == Player.STATE_READY &&
                    completionGate().eligible) {
                    val key = watchedKey(spec)
                    val now = SystemClock.elapsedRealtime()
                    if (key !in watchedConfirmed && key !in watchedQueued && now - (watchedAttemptAt[key] ?: Long.MIN_VALUE / 2) >= 5_000) {
                        watchedAttemptAt[key] = now
                        enqueueProgress(if (active.isPlaying) "playing" else "paused")
                    }
                }
                if (SystemClock.elapsedRealtime() - lastSyncAt >= 10_000 && !gestures.isSeekGestureActive) {
                    enqueueProgress(if (active.isPlaying) "playing" else "paused")
                    lastSyncAt = SystemClock.elapsedRealtime()
                }
            }
            val needsSubtitleUpdates = externalSubtitleId != null && subtitleTimeline != null &&
                (active?.isPlaying == true || gestures.isSeekGestureActive)
            val markerDelay = if (active != null && prepared && !refreshing && !navigationLoading && !conversionLoading &&
                !playerGestureActive && !gestures.isSeekGestureActive && !gestures.locked)
                playbackMarkerRefreshDelayMs(spec.markers, segmentPosition, lastDuration,
                    active.playbackParameters.speed, active.isPlaying) else 500L
            handler.postDelayed(this, minOf(if (needsSubtitleUpdates) 50L else 500L, markerDelay))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("endingAfterCompletion") == true ||
            savedInstanceState?.getBoolean("endingToHome") == true) {
            endingAfterCompletion = true; exiting = true; completed = true
            val source = savedInstanceState.getString("spec")?.let { runCatching { PlaybackSpec.fromJson(it) }.getOrNull() }
                ?.sourceItem?.takeIf { it.type == "episode" }
            returnToCaller(source?.toJson())
            return
        }
        val server = intent.getStringExtra("server").orEmpty()
        val payload = savedInstanceState?.getString("spec") ?: intent.getStringExtra("spec")
        val session = SessionStore(this).load(server)
        if (payload.isNullOrBlank() || server.isBlank() || session == null) {
            toast("서버에 다시 로그인한 뒤 재생해 주세요."); finish(); return
        }
        spec = runCatching { PlaybackSpec.fromJson(payload) }.getOrElse {
            toast("재생 정보를 읽을 수 없습니다."); finish(); return
        }
        savedInstanceState?.getStringArrayList("watchedReached")?.let { watchedReached.addAll(it) }
        val settings = getSharedPreferences("gsy_settings", MODE_PRIVATE)
        val deviceId = settings.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            settings.edit().putString("device_id", it).apply()
        }
        client = ShyniClient(server, session.token, deviceId = deviceId)
        if (spec.sourceItem?.isSpoiler == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        subtitleLoader = SubtitleLoader(headersFor = client::headersFor)
        lastPosition = savedInstanceState?.getLong("position", spec.resumeMs) ?: spec.resumeMs
        lastDuration = maxOf(0L, spec.durationMs, savedInstanceState?.getLong("duration", spec.durationMs) ?: 0L)
        speed = (savedInstanceState?.getFloat("speed", settings.getFloat("playback_speed", 1f)) ?: settings.getFloat("playback_speed", 1f))
            .takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f
        resizeMode = settings.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
            .takeIf { it in resizeModes } ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
        resizeScale = settings.getFloat("resize_scale", 1f).takeIf { it in setOf(1f, 1.1f, 1.25f, 1.5f) } ?: 1f
        subtitlesEnabled = savedInstanceState?.getBoolean("subtitles", true) ?: true
        subtitleManuallySelected = savedInstanceState?.getBoolean("subtitleManuallySelected", false) ?: false
        externalSubtitleId = if (savedInstanceState != null) savedInstanceState.getInt("externalSubtitle", -1).takeIf { it >= 0 }
            else preferredSubtitle(spec)?.id
        embeddedServerSubtitleId = savedInstanceState?.getInt("embeddedSubtitle", -1)?.takeIf { it >= 0 }
        subtitleOffsetMs = savedInstanceState?.getLong("subtitleOffset", 0) ?: 0
        subtitleFont = settings.getString("subtitle_font", "system") ?: "system"
        subtitleAppearance = PlexSubtitleAppearance(sizePercent = settings.getInt("subtitle_size", 100),
            horizontalOffsetPercent = settings.getInt("subtitle_x", 0), verticalOffsetPercent = settings.getInt("subtitle_y", 0),
            verticalWriting = settings.getBoolean("subtitle_vertical", false),
            verticalSpacingPercent = normalizedVerticalSubtitleSpacingPercent(settings.getInt("subtitle_vertical_spacing", 100)))
        watchedMs = savedInstanceState?.getLong("watched", 0) ?: 0
        resumeWhenVisible = savedInstanceState?.getBoolean("playing", true) ?: true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildViews()
        if (closeLockedSpoilerPlayback()) return
        scope.launch {
            SpoilerAccessRegistry.changes.collect { closeLockedSpoilerPlayback() }
        }
        hideSystemBars()
        progressScope.launch {
            try {
                while (true) {
                    val update = updates.next() ?: break
                    val key = watchedKey(update.spec)
                    if (!playbackAuthentication.isCurrent(update.authenticationEpoch)) {
                        update.acknowledgement?.complete(false)
                        if (update.thresholdAttempt) watchedQueued.remove(key)
                        continue
                    }
                    var positionFailed = false
                    var completionFailed = false
                    fun logProgressFailure(stage: PlaybackProgressFailureStage, error: Exception) {
                        val failure = playbackProgressFailure(stage, error)
                        Log.w("ShyniFlixProgress", "stage=${failure.stage.name} status=${failure.httpStatus ?: "none"} type=${failure.exceptionType}")
                    }
                    val delivery = progressScope.async(start = CoroutineStart.LAZY) {
                        deliverPlaybackProgress(update.acknowledgement, onSaved = {
                            if (playbackAuthentication.isCurrent(update.authenticationEpoch) &&
                                update.state in setOf("paused", "stopped", "completed")) PlaybackCompletionSignals.notifyChanged()
                        }) {
                            var committed = true
                            try {
                                retryPlaybackProgress {
                                    ensureProgressAuthentication(update)
                                    client.saveProgress(update.spec, update.position, update.duration, update.state, update.watched,
                                        completionEligible = update.completionEligible)
                                }
                                if (update.state == "completed" && playbackAuthentication.isCurrent(update.authenticationEpoch))
                                    PlaybackCompletionSignals.notifyChanged()
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                positionFailed = true
                                logProgressFailure(PlaybackProgressFailureStage.POSITION, error)
                                committed = false
                            }
                            if (update.completionEligible) {
                                try {
                                    retryPlaybackProgress {
                                        ensureProgressAuthentication(update)
                                        client.markPlaybackWatched(update.spec.copy(durationMs = update.duration))
                                    }
                                    if (playbackAuthentication.isCurrent(update.authenticationEpoch) && watchedConfirmed.add(key)) {
                                        val item = update.spec.sourceItem?.takeIf { it.id == update.spec.itemId &&
                                            it.libraryId == update.spec.libraryId && it.viewItemId == update.spec.viewItemId }
                                        PlaybackCompletionSignals.confirm(update.accountScope, item,
                                            update.privateGrantRevision, update.choiceRevision)
                                        PlaybackCompletionSignals.notifyChanged()
                                    }
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    completionFailed = true
                                    logProgressFailure(PlaybackProgressFailureStage.COMPLETION, error)
                                    if (playbackAuthentication.isCurrent(update.authenticationEpoch)) watchedConfirmed.remove(key)
                                    committed = false
                                }
                            }
                            committed && playbackAuthentication.isCurrent(update.authenticationEpoch)
                        }
                    }
                    progressDeliveryJob = delivery
                    if (!playbackAuthentication.isCurrent(update.authenticationEpoch)) delivery.cancel()
                    delivery.start()
                    val saved = try {
                        delivery.await()
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        if (playbackAuthentication.isCurrent(update.authenticationEpoch)) throw cancel
                        update.acknowledgement?.complete(false)
                        false
                    } finally {
                        if (progressDeliveryJob === delivery) progressDeliveryJob = null
                        if (update.thresholdAttempt) watchedQueued.remove(key)
                    }
                    if (!playbackAuthentication.isCurrent(update.authenticationEpoch)) continue
                    if (saved) syncErrorShown = null
                    else {
                        val message = playbackProgressFailureMessage(positionFailed, completionFailed)
                        if (syncErrorShown != message) {
                            syncErrorShown = message
                            runOnUiThread {
                                if (!isDestroyed && !exiting && playbackAuthentication.isCurrent(update.authenticationEpoch))
                                    toast(message)
                            }
                        }
                    }
                }
            } finally { progressScope.cancel() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (television && ::segmentActions.isInitialized && segmentActions.hasFocus()) {
                    playerView.hideController()
                    restoreSegmentPlaybackFocus()
                } else if (television && playerView.isControllerFullyVisible) playerView.hideController()
                else leavePlayer()
            }
        })
        preparePlayback(resumeWhenVisible)
    }

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = layoutInflater.inflate(R.layout.shyniflix_player, root, false) as PlayerView
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        subtitleOverlay = PlexSubtitleOverlayView(this)
        root.addView(subtitleOverlay, FrameLayout.LayoutParams(-1, -1))
        applySubtitleStyle()
        playerView.controllerShowTimeoutMs = 3500
        playerView.setControllerAnimationEnabled(false)
        playerView.setControllerAutoShow(false)
        playerView.setControllerHideOnTouch(false)
        playerView.hideController()
        playerView.findViewById<TextView>(R.id.player_title).text = spec.controllerTitle
        playerView.findViewById<Button>(R.id.player_back).setOnClickListener { leavePlayer() }
        playerView.findViewById<ImageButton>(R.id.player_settings).setOnClickListener { showSettings() }
        playerView.findViewById<ImageButton>(R.id.player_resize).setOnClickListener { cycleResizeMode() }
        playerView.findViewById<Button>(R.id.player_speed).setOnClickListener { showSpeed() }
        playerView.findViewById<Button>(R.id.player_conversion).setOnClickListener { showConversion() }
        playerView.findViewById<ImageButton>(R.id.player_subtitles).setOnClickListener {
            if (it.isEnabled && it.isShown) showTracks(C.TRACK_TYPE_TEXT)
        }
        playerView.findViewById<ImageButton>(R.id.player_previous).setOnClickListener { switchNeighbor(false) }
        playerView.findViewById<ImageButton>(R.id.player_next).setOnClickListener { switchNeighbor(true) }
        updatePlaybackButtons()
        applyResizeMode()
        gestures = Media3GestureView(this).apply {
            onControllerToggle = { if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController() }
            onLockedTap = ::showUnlockControl
            onSeekCommitted = { target -> completionGate().seek(target); pendingSeek = target; lastPosition = target; updateSubtitleText(); refreshSegmentActions() }
            onSeekSettled = { target -> pendingSeek = null; lastPosition = target; updateSubtitleText(); refreshSegmentActions(); enqueueProgress(if (player?.isPlaying == true) "playing" else "paused") }
            onGestureActive = { active ->
                playerGestureActive = active
                if (active) {
                    subtitleDialog?.dismiss()
                    dismissTvSubtitlePosition()
                    hideSegmentActions()
                    playerView.hideController()
                    hideLockControl()
                    settleWatchTime(visible && player?.isPlaying == true)
                    handler.removeCallbacks(ticker); handler.post(ticker)
                } else refreshSegmentActions()
            }
            onSubtitleDragStart = ::startSubtitleDrag
            onSubtitleDragged = ::moveSubtitleDrag
            onSubtitleDragEnd = ::finishSubtitleDrag
            onSeekFailed = { target ->
                lastPosition = target; pendingSeek = target
                if (!spoilerPreparing) showError("이동한 위치를 불러오지 못했습니다. 이 위치에서 다시 재생해 주세요.")
            }
        }
        root.addView(gestures, FrameLayout.LayoutParams(-1, -1))
        lockButton = Button(this).apply {
            id = R.id.player_lock
            text = "잠금"; textSize = 12f; setTextColor(Color.WHITE); applyControlHighlight(this)
            visibility = View.GONE
            contentDescription = "화면 잠금"
            setOnClickListener {
                handler.removeCallbacks(hideUnlock)
                unlockKeyPending = false
                gestures.locked = !gestures.locked
                text = if (gestures.locked) "해제" else "잠금"
                contentDescription = if (gestures.locked) "화면 잠금 해제" else "화면 잠금"
                playerView.useController = !gestures.locked
                if (gestures.locked) {
                    subtitleDialog?.dismiss()
                    dismissTvSubtitlePosition()
                    playerView.hideController()
                    showUnlockControl()
                } else playerView.showController()
                refreshSegmentActions()
            }
        }
        root.addView(lockButton, FrameLayout.LayoutParams(dp(62), dp(48), Gravity.TOP or Gravity.END))
        segmentSkipButton = Button(this).apply {
            id = R.id.player_segment_skip
            visibility = View.GONE
            textSize = 14f; setTextColor(Color.WHITE); applyControlHighlight(this)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { skipCurrentSegment() }
        }
        creditsNextButton = Button(this).apply {
            id = R.id.player_credits_next
            visibility = View.GONE
            text = "다음 화"; textSize = 14f
            setTextColor(Color.WHITE); applyControlHighlight(this)
            setPadding(dp(12), 0, dp(16), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_player_next_episode, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            contentDescription = "다음 화 재생"
            setOnClickListener { switchNeighbor(forward = true, creditsEpisode = true) }
        }
        segmentActions = LinearLayout(this).apply {
            id = R.id.player_segment_actions
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            descendantFocusability = if (television) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            visibility = View.GONE
            addView(segmentSkipButton, LinearLayout.LayoutParams(-2, dp(48)))
            addView(creditsNextButton, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        }
        root.addView(segmentActions, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            updateSegmentActionInsets(insets)
            insets
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            gestures.controllerVisible = visibility == View.VISIBLE && !gestures.locked
            if (gestures.locked) {
                if (visibility == View.VISIBLE) playerView.hideController()
            } else lockButton.visibility = if (!television && visibility == View.VISIBLE) View.VISIBLE else View.GONE
            updateSegmentSkipButton()
            updateSegmentActionInsets(ViewCompat.getRootWindowInsets(root))
            updateSubtitleButton()
        })
        gestures.controllerVisible = playerView.isControllerFullyVisible
        errorText = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xED14202D.toInt()); visibility = View.GONE
            addView(errorText, LinearLayout.LayoutParams(-1, -2))
            addView(Button(this@NativePlayerActivity).apply { text = "이 위치에서 다시 재생"; setTextColor(Color.WHITE); applyControlHighlight(this); setOnClickListener { refreshTicketAndRestart() } })
            addView(Button(this@NativePlayerActivity).apply { text = "라이브러리로 돌아가기"; setTextColor(Color.WHITE); applyControlHighlight(this); setOnClickListener { leavePlayer() } })
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(dp(410), -2, Gravity.CENTER))
        if (television) {
            playerView.isFocusable = true
            playerView.isFocusableInTouchMode = true
            // The screen lock is a touch control; a TV must always retain remote navigation.
            lockButton.visibility = View.GONE
            playerView.controllerShowTimeoutMs = 6000
            listOf(R.id.player_back, R.id.player_resize, R.id.player_settings, R.id.player_speed,
                R.id.player_conversion, R.id.player_subtitles, R.id.player_previous, R.id.player_next,
                androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
                androidx.media3.ui.R.id.exo_ffwd).forEach { id ->
                playerView.findViewById<View>(id)?.apply {
                    background = androidx.core.content.ContextCompat.getDrawable(this@NativePlayerActivity, R.drawable.tv_player_button)
                    backgroundTintList = null
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            }
            applyControlHighlight(segmentSkipButton)
            applyControlHighlight(creditsNextButton)
            segmentSkipButton.isFocusableInTouchMode = true
            creditsNextButton.isFocusableInTouchMode = true
            listOf(segmentSkipButton, creditsNextButton).forEach { button ->
                button.setOnFocusChangeListener { _, focused -> if (focused) tvSegmentFocusGate.focused() }
            }
            segmentSkipButton.setOnKeyListener { _, key, event -> handleSegmentDirectionKey(false, key, event) }
            creditsNextButton.setOnKeyListener { _, key, event -> handleSegmentDirectionKey(true, key, event) }
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun hideLockControl() {
        handler.removeCallbacks(hideUnlock)
        if (::lockButton.isInitialized) lockButton.visibility = View.GONE
    }

    private fun showUnlockControl() {
        if (!::lockButton.isInitialized || !gestures.locked) return
        playerView.hideController()
        lockButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3500)
    }

    private fun currentSegmentMarker(active: Player?, positionMs: Long? = null): PlaybackMarker? =
        if (!visible || !prepared || exiting || refreshing || navigationLoading || conversionLoading ||
            playerGestureActive || gestures.isSeekGestureActive || pendingSeek != null || gestures.locked ||
            active == null || !active.isCurrentMediaItemSeekable ||
            active.playbackState != Player.STATE_READY ||
            !active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
            active.playerError != null || (::errorPanel.isInitialized && errorPanel.visibility == View.VISIBLE)) null
        else currentSkipMarker(spec.markers, positionMs ?: active.currentPosition, lastDuration)

    private fun creditsNextEpisodeTarget() = verifiedCreditsNextEpisode(spec, playbackNeighbors)

    private fun updateSegmentSkipButton(positionMs: Long? = null) {
        if (!::segmentActions.isInitialized) return
        val candidate = currentSegmentMarker(player, positionMs)
        if (television && activeSkipMarker != candidate) {
            // An UP belonging to the old interval must not activate a reused
            // button after its label/action has changed to another interval.
            segmentSkipButton.isPressed = false; segmentSkipButton.cancelLongPress()
            creditsNextButton.isPressed = false; creditsNextButton.cancelLongPress()
        }
        activeSkipMarker = candidate
        val marker = activeSkipMarker
        val nextHadFocus = creditsNextButton.hasFocus()
        if (television && marker == null && segmentActions.hasFocus()) restoreSegmentPlaybackFocus()
        segmentSkipButton.visibility = if (marker == null) View.GONE else View.VISIBLE
        if (marker != null) {
            segmentSkipButton.text = if (marker.type == "intro") "인트로 건너뛰기" else "크레딧 건너뛰기"
            segmentSkipButton.contentDescription = segmentSkipButton.text
        }
        val next = if (marker?.type == "credits") creditsNextEpisodeTarget() else null
        creditsNextButton.visibility = if (next == null) View.GONE else View.VISIBLE
        creditsNextButton.isEnabled = next != null
        creditsNextButton.contentDescription = next?.let {
            "다음 화 재생, 시즌 ${it.seasonNumber} ${it.episodeNumber}화, ${it.title}"
        } ?: "다음 화 재생"
        creditsNextButton.tooltipText = creditsNextButton.contentDescription
        segmentActions.visibility = if (marker == null) View.GONE else View.VISIBLE
        if (television) {
            segmentActions.descendantFocusability = if (marker != null) ViewGroup.FOCUS_AFTER_DESCENDANTS
                else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Timeline identity survives temporary loading/seek hides; only a genuine
            // interval exit or another physical file rearms automatic focus.
            val timelineMarker = currentSkipMarker(spec.markers, positionMs ?: player?.currentPosition ?: lastPosition, lastDuration)
            val key = timelineMarker?.let { TvSegmentMarkerKey(spec.partId, it.type, it.startMs, it.endMs) }
            if (marker != null && next == null && nextHadFocus) focusSegmentSkip()
            tvSegmentFocusGate.offer(key, marker != null,
                visible && hasWindowFocus() && !playerView.isControllerFullyVisible && remoteSeek == null) {
                focusSegmentSkip()
            }
        }
    }

    private fun hideSegmentActions() {
        activeSkipMarker = null
        if (television && ::segmentActions.isInitialized && segmentActions.hasFocus()) restoreSegmentPlaybackFocus()
        if (::segmentSkipButton.isInitialized) segmentSkipButton.visibility = View.GONE
        if (::creditsNextButton.isInitialized) creditsNextButton.visibility = View.GONE
        if (::segmentActions.isInitialized) segmentActions.visibility = View.GONE
    }

    private fun focusSegmentSkip(): Boolean {
        if (!::segmentActions.isInitialized || !segmentActions.isShown || !segmentSkipButton.isShown || !segmentSkipButton.isEnabled) return false
        segmentActions.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        return segmentSkipButton.requestFocus().also { if (it) tvSegmentFocusGate.focused() }
    }

    private fun restoreSegmentPlaybackFocus(showControls: Boolean = false) {
        if (!::playerView.isInitialized) return
        if (showControls) playerView.showController()
        val target = if (playerView.isControllerFullyVisible)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause) else playerView
        if (target?.requestFocus() != true) {
            if (::segmentActions.isInitialized) segmentActions.clearFocus()
            playerView.requestFocus()
        }
    }

    private fun handleSegmentDirectionKey(fromNext: Boolean, key: Int, event: KeyEvent): Boolean {
        if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
        // Move only on release, so a held key cannot escape into seeking or click
        // a different newly focused control with the remainder of the same press.
        if (event.action == KeyEvent.ACTION_UP) {
            when {
                fromNext && key == KeyEvent.KEYCODE_DPAD_LEFT -> focusSegmentSkip()
                !fromNext && key == KeyEvent.KEYCODE_DPAD_RIGHT && creditsNextButton.isShown -> creditsNextButton.requestFocus()
                else -> restoreSegmentPlaybackFocus(showControls = true)
            }
        }
        return true
    }

    private fun refreshSegmentActions() {
        if (!::gestures.isInitialized) return
        updateSegmentSkipButton()
        if (visible && !exiting) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    private fun updateSegmentActionInsets(insets: WindowInsetsCompat?) {
        if (!::segmentActions.isInitialized) return
        val safe = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        (segmentActions.layoutParams as? FrameLayout.LayoutParams)?.let { layout ->
            layout.leftMargin = dp(24) + (safe?.left ?: 0)
            layout.rightMargin = dp(24) + (safe?.right ?: 0)
            layout.bottomMargin = dp(if (playerView.isControllerFullyVisible) 148 else 124) + (safe?.bottom ?: 0)
            segmentActions.layoutParams = layout
        }
    }

    private fun skipCurrentSegment() {
        val active = player ?: return
        if (closeLockedSpoilerPlayback() || gestures.locked || playerGestureActive || refreshing || navigationLoading || conversionLoading ||
            active.playbackState != Player.STATE_READY ||
            !active.isCurrentMediaItemSeekable || !active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val marker = currentSegmentMarker(active) ?: return
        hideSegmentActions()
        playerView.hideController(); hideLockControl()
        lastPosition = marker.endMs; pendingSeek = marker.endMs
        completionGate().seek(marker.endMs)
        subtitleOverlay.subtitleText = ""
        active.seekTo(marker.endMs)
        updateSubtitleText()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) abandonEnginePreflightForInteraction()
        if (television && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN) tvSegmentFocusGate.keyDown(event.keyCode)
            else if (event.action == KeyEvent.ACTION_UP) {
                tvSegmentFocusGate.keyUp(event.keyCode)
                // A deferred automatic focus runs after this UP has reached its
                // original target, never during the old confirm press.
                handler.post { if (visible && !exiting) updateSegmentSkipButton() }
            }
        }
        if (television && ::playerView.isInitialized && ::errorPanel.isInitialized && errorPanel.isShown)
            return super.dispatchKeyEvent(event)
        if (television && ::gestures.isInitialized && !gestures.locked && handleTvKey(event)) return true
        if (::gestures.isInitialized && !gestures.locked && event.keyCode in
            listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                switchNeighbor(event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
            return true
        }
        if (::gestures.isInitialized && gestures.locked) {
            if (event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) return true
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (event.action == KeyEvent.ACTION_DOWN && !lockButton.isShown) {
                    showUnlockControl(); lockButton.requestFocus(); unlockKeyPending = true
                    return true
                }
                if (unlockKeyPending) {
                    if (event.action == KeyEvent.ACTION_UP) unlockKeyPending = false
                    return true
                }
            } else if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) abandonEnginePreflightForInteraction()
        return super.dispatchTouchEvent(event)
    }

    /** Apply a remote/touch action to the existing MPV immediately, without a later surprise switch. */
    private fun abandonEnginePreflightForInteraction() {
        when (engineInteraction(player != null, engineSelectionJob?.isActive == true, mpvProbePending,
            player is ExoPlayer && !exoValidated)) {
            EngineInteraction.START_MPV -> engineSelectionTimeout.run()
            EngineInteraction.ACCEPT_MPV -> finishMpvProbe?.invoke()
            EngineInteraction.FALLBACK_FROM_EXO -> player?.let(::fallbackFromExo)
            EngineInteraction.NONE -> Unit
        }
    }

    private fun handleTvKey(event: KeyEvent): Boolean {
        val active = player ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val firstDown = down && event.repeatCount == 0
        val ready = prepared && !exiting && !refreshing && !navigationLoading && !conversionLoading
        val canSetPlaybackIntent = prepared && !exiting && !navigationLoading && !conversionLoading &&
            (!refreshing || spoilerPreparing)
        if (playerView.isControllerFullyVisible && segmentSkipButton.isShown &&
            currentFocus?.id == androidx.media3.ui.R.id.exo_play_pause && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.action == KeyEvent.ACTION_UP) focusSegmentSkip()
            return true
        }
        if (::segmentActions.isInitialized && segmentActions.isShown && segmentActions.hasFocus() &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) return false
        if (remoteSeek != null && event.keyCode == remoteSeekKey) {
            remoteSeekLastEventAt = SystemClock.elapsedRealtime()
            if (event.action == KeyEvent.ACTION_UP) finishRemoteSeeking()
            return true
        }
        if (remoteSeek != null && firstDown && event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && event.keyCode != KeyEvent.KEYCODE_MEDIA_REWIND &&
            event.keyCode != KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) finishRemoteSeeking()
        when (val action = tvRemoteAction(event.keyCode, playerView.isControllerFullyVisible)) {
            TvRemoteAction.PLAY, TvRemoteAction.PAUSE, TvRemoteAction.TOGGLE_PLAY -> if (firstDown && canSetPlaybackIntent) {
                val play = when (action) {
                    TvRemoteAction.PLAY -> true
                    TvRemoteAction.PAUSE -> false
                    else -> !(gestures.playWhenReadyBeforeSeek ?: active.playWhenReady)
                }
                if (!gestures.updateSeekPlaybackIntent(play)) active.playWhenReady = play
            }
            TvRemoteAction.SEEK_BACK, TvRemoteAction.SEEK_FORWARD -> if (down && ready && active.isCurrentMediaItemSeekable) {
                val forward = action == TvRemoteAction.SEEK_FORWARD
                val now = SystemClock.elapsedRealtime()
                val origin = remoteSeek?.targetAt(now) ?: gestures.pendingSeekPositionMs ?: active.currentPosition
                if (remoteSeek != null || gestures.beginRemoteSeek(forward)) {
                    remoteSeek = RemoteSeekTimeline(origin, active.duration, forward, now)
                    remoteSeekKey = event.keyCode
                    remoteSeekLastEventAt = now
                    gestures.previewRemoteSeek(remoteSeek!!.targetAt(now))
                    handler.removeCallbacks(remoteSeekFrame)
                    handler.postDelayed(remoteSeekFrame, 16)
                }
            }
            TvRemoteAction.SETTINGS -> if (firstDown && ready) showSettings()
            TvRemoteAction.SHOW_CONTROLS -> {
                // Opening on release prevents the same press activating a newly focused button.
                if (event.action == KeyEvent.ACTION_UP) {
                    playerView.showController()
                    segmentActions.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    if (!focusSegmentSkip()) restoreSegmentPlaybackFocus()
                }
            }
            null -> return false
        }
        return true
    }

    private fun finishRemoteSeeking() {
        if (remoteSeek == null) return
        remoteSeek = null
        remoteSeekKey = KeyEvent.KEYCODE_UNKNOWN
        handler.removeCallbacks(remoteSeekFrame)
        gestures.finishRemoteSeek()
    }

    private fun preparePlayback(autoStart: Boolean) {
        prepared = false
        releasePlayer()
        playbackFirstFrameReported = false
        playerView.visibility = View.VISIBLE
        errorPanel.getChildAt(1).apply { visibility = View.VISIBLE; isEnabled = true }
        completionGate().seek(lastPosition)
        completed = false; prepared = false; errorPanel.visibility = View.GONE
        resumeWhenVisible = autoStart
        val current = spec
        val account = client.token
        val generation = engineGeneration
        val key = "${current.sessionId}:${current.partId}"
        if (current.sessionId.isNullOrBlank() || forceMpvSession == key || current.sourceItem?.isSpoiler != false ||
            current.isServerConversion || current.preferSoftwareDecode ||
            pendingTextPreference != null || resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT || resizeScale != 1f) {
            startPreparedPlayer(null, false)
            return
        }
        engineSelectionJob = scope.launch(start = CoroutineStart.LAZY) {
            val candidate = try {
                kotlinx.coroutines.coroutineScope {
                    val information = async { client.playbackCodecInfo(current) }
                    // Inspect every raw row, including formats omitted from the regular menu.
                    val subtitles = async { client.playbackSubtitlesForEngine(current) }
                    val info = information.await()
                    val inspected = subtitles.await()
                    val list = inspected.tracks
                    val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                    if (!inspected.safeForExo || (savedId != null && list.none { it.id == savedId })) null
                    else info?.takeIf { isPotentialExoCandidate(current, it, list, true) }
                        ?.let { EngineCandidate(it, list) }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { null }
            if (generation != engineGeneration || player != null || exiting || isDestroyed || client.token != account || spec != current ||
                closeLockedSpoilerPlayback()) return@launch
            handler.removeCallbacks(engineSelectionTimeout)
            engineSelectionJob = null
            // Apply the checked list only for this exact playback, preserving a
            // restored/manual choice and the same default selection as MPV.
            val adopted = candidate.takeIf { visible }
            if (adopted != null) {
                spec = spec.copy(subtitles = adopted.subtitles, subtitlesPending = false)
                val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                val preferred = deferredSubtitleSelection(spec, savedId, deferredSubtitlePreference,
                    subtitlesEnabled, subtitleManuallySelected, pendingTextPreference != null, true)
                deferredSubtitlePreference = null
                if (preferred != null) {
                    if (preferred.isEmbedded) { embeddedServerSubtitleId = preferred.id; externalSubtitleId = null }
                    else { externalSubtitleId = preferred.id; embeddedServerSubtitleId = null }
                }
            }
            startPreparedPlayer(adopted?.info, false)
        }
        // A main-thread timer can start MPV even if an IO child does not cooperate
        // with cancellation. Late results are fenced by generation and identity.
        handler.postDelayed(engineSelectionTimeout, 750)
        engineSelectionJob?.start()
    }

    private fun startPreparedPlayer(candidate: PlaybackCodecInfo?, useExo: Boolean) {
        val headers = client.playbackHeaders(spec)
        val created: Player = if (useExo) try {
            // Media3 normally logs exception stack traces that can include signed URLs.
            // Keep its logs off; this activity reports only sanitized error codes.
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            val data = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
            ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(data))
                .setHandleAudioBecomingNoisy(true).build().apply { setAudioAttributes(AudioAttributes.DEFAULT, true) }
        } catch (_: Exception) {
            if (exoRestoreSubtitlePending) spec = spec.copy(subtitlesPending = true)
            exoRestoreSubtitlePending = false
            MpvPlayer(this, headers, preferSoftwareDecode = spec.preferSoftwareDecode,
                serverConversion = spec.isServerConversion, sourceDurationMs = lastDuration)
        } else MpvPlayer(this, headers, preferSoftwareDecode = spec.preferSoftwareDecode,
            serverConversion = spec.isServerConversion, sourceDurationMs = lastDuration)
        mpvProbePending = candidate != null && created is MpvPlayer
        exoValidated = created !is ExoPlayer
        gestures.seekTimeoutMs = if (spec.isServerConversion) 30_000 else 8_000
        player = created
        playbackMetadataGate.reset()
        playbackNeighbors = null
        updateNavigationButtons()
        applyResizeMode()
        mpvSubtitleController.reset()
        (created as? MpvPlayer)?.nativeSubtitleVisibility = false
        created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(spec.selectedAudioLanguage)
            .setPreferredTextLanguages("ko", "kor")
            .setSelectUndeterminedTextLanguage(true).build()
        created.setPlaybackSpeed(speed)
        if (mpvProbePending) {
            finishMpvProbe = {
                if (mpvProbePending && player === created && !exiting && !isDestroyed) {
                    mpvProbePending = false
                    finishMpvProbe = null
                    handler.removeCallbacks(engineProbeTimeout)
                    mpvProbeJob?.cancel(); mpvProbeJob = null
                    forceMpvSession = "${spec.sessionId}:${spec.partId}"
                    if (created.playbackState == Player.STATE_READY) acceptPreparedPlayer(created)
                    created.playWhenReady = resumeWhenVisible && visible
                    refreshSegmentActions()
                }
            }
            handler.postDelayed(engineProbeTimeout, 600)
        }
        var initialAudioApplied = false
        created.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (player !== created) return
                renderEmbeddedCues(cueGroup)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (player !== created) return
                if (created is ExoPlayer && exoValidated && !exoTracksSafe(tracks, spec.subtitles.any(::exoEmbeddedSubtitle))) {
                    fallbackFromExo(created)
                    return
                }
                resolvePendingEmbeddedSubtitle()
                restorePendingTextPreference()
                if (awaitingEmbeddedSubtitle && externalSubtitleId == null) selectEmbeddedSubtitleFallback()
                renderEmbeddedCues(created.currentCues)
                if (initialAudioApplied) return
                val audioTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                    (0 until group.length).map { index -> group to index }
                }
                if (audioTracks.isEmpty()) return
                initialAudioApplied = true
                val restored = pendingAudioPreference?.let { preference ->
                    matchingPlaybackTrack(preference, audioTracks.map { (group, index) ->
                        group.getTrackFormat(index).let { PlaybackTrackPreference(it.language, it.label) }
                    })?.let(audioTracks::get)
                }
                pendingAudioPreference = null
                val selected = restored ?: spec.selectedAudioStreamOrdinal?.let { audioTracks.getOrNull(it) }
                if (selected != null && selected.first.isTrackSupported(selected.second)) {
                    created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .addOverride(TrackSelectionOverride(selected.first.mediaTrackGroup, listOf(selected.second))).build()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== created) return
                if (state == Player.STATE_READY) {
                    if (mpvProbePending && created is MpvPlayer && candidate != null) {
                        if (playbackFirstFrameReported) checkMpvProbe(created, candidate)
                        return
                    }
                    if (created is ExoPlayer && !exoValidated) {
                        if (!exoTracksSafe(created.currentTracks, spec.subtitles.any(::exoEmbeddedSubtitle))) { fallbackFromExo(created); return }
                        exoValidated = true
                        created.playWhenReady = resumeWhenVisible && visible
                    }
                    acceptPreparedPlayer(created)
                } else if (state == Player.STATE_ENDED && !exiting && !refreshing) {
                    if (mpvProbePending) { finishMpvProbe?.invoke(); return }
                    if (created is ExoPlayer && !exoValidated) { fallbackFromExo(created); return }
                    handler.post {
                        if (player === created && !exiting && !refreshing && created.reconcilePlaybackCompletion())
                            finishCompletedPlayback()
                    }
                }
                refreshSegmentActions()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== created) return
                if (mpvProbePending) return
                if (!isPlaying) capturePosition()
                settleWatchTime(visible && isPlaying)
                if (prepared && !isPlaying && !gestures.isSeekGestureActive && !refreshing && !exiting) enqueueProgress("paused")
                if (isPlaying && playbackFirstFrameReported && !subtitleListFailed) loadDeferredSubtitles(created)
                refreshSegmentActions()
            }
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                if (player === created) refreshSegmentActions()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== created) return
                if (mpvProbePending) {
                    mpvProbePending = false; finishMpvProbe = null
                    handler.removeCallbacks(engineProbeTimeout)
                    mpvProbeJob?.cancel(); mpvProbeJob = null
                }
                settleWatchTime(false)
                if (created is ExoPlayer && shouldFallbackFromExo(error.errorCode) && !exiting && !refreshing) {
                    fallbackFromExo(created)
                    return
                }
                // Unlock revokes the old grant. If the old connection closes while
                // its replacement is being prepared, finish that one handoff.
                if (spoilerPreparing && spoilerRotationJob?.isActive == true) {
                    capturePosition()
                    return
                }
                if (conversionRestore != null && !exiting) {
                    handler.post { if (player === created) restoreConversion("서버에서 변환 영상을 재생하지 못했습니다.") }
                    return
                }
                if (!exiting && !refreshing) {
                    showError("영상을 재생할 수 없습니다.\n${error.errorCodeName}\n같은 위치에서 다시 시도해 주세요.")
                    enqueueProgress("paused")
                }
            }
            override fun onRenderedFirstFrame() {
                if (player !== created) return
                playbackFirstFrameReported = true
                if (mpvProbePending && created is MpvPlayer && candidate != null) {
                    if (created.playbackState == Player.STATE_READY) checkMpvProbe(created, candidate)
                    return
                }
                if (conversionRestore != null) {
                    conversionRestore = null
                    conversionLoading = false
                    handler.removeCallbacks(conversionTimeout)
                    updatePlaybackButtons()
                }
                if (!subtitleListFailed) loadDeferredSubtitles(created)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player !== created) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) completionGate().seek(newPosition.positionMs)
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !gestures.isSeekGestureActive) {
                    lastPosition = newPosition.positionMs.coerceAtLeast(0)
                    pendingSeek = null
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK && externalSubtitleId == null) subtitleOverlay.subtitleText = ""
                if (reason == Player.DISCONTINUITY_REASON_SEEK) updateSubtitleText()
                refreshSegmentActions()
            }
        })
        created.setMediaItem(buildMediaItem(), lastPosition.coerceAtLeast(0))
        playerView.player = created
        gestures.attachPlayer(created)
        created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled || externalSubtitleId != null).build()
        if (subtitlesEnabled) {
            val selectedId = embeddedServerSubtitleId ?: externalSubtitleId
            selectedId?.let { id -> spec.subtitles.firstOrNull { it.id == id }?.let(::selectServerSubtitle) }
        }
        created.prepare()
        created.playWhenReady = resumeWhenVisible && visible && created !is ExoPlayer && !mpvProbePending
    }

    private fun fallbackFromExo(active: Player) {
        if (active !is ExoPlayer || player !== active || exiting || isDestroyed || refreshing) return
        val playing = if (exoValidated) active.playWhenReady else resumeWhenVisible
        // Before track validation Exo's default position can still be zero;
        // preserve the authorized resume point until it has a valid timeline.
        lastPosition = exoFallbackPosition(lastPosition, active.currentPosition, exoValidated, playbackFirstFrameReported)
        if (exoRestoreSubtitlePending && spec.subtitles.isEmpty()) spec = spec.copy(subtitlesPending = true)
        exoRestoreSubtitlePending = false
        forceMpvSession = "${spec.sessionId}:${spec.partId}"
        preparePlayback(playing)
    }

    private fun acceptPreparedPlayer(created: Player) {
        if (player !== created || exiting || isDestroyed) return
        prepared = true
        completed = false
        resolvePendingEmbeddedSubtitle()
        restorePendingTextPreference()
        if (created.duration != C.TIME_UNSET && created.duration > 0) preserveFullDuration(created.duration)
        if (playbackFirstFrameReported && !subtitleListFailed) loadDeferredSubtitles(created)
        refreshPlaybackMetadata(created)
    }

    /** Probe with MPV's raw track list before Exo, which can hide unknown MP4 subtitle tracks. */
    private fun checkMpvProbe(created: MpvPlayer, candidate: PlaybackCodecInfo) {
        if (!mpvProbePending || player !== created || mpvProbeJob?.isActive == true || exiting || isDestroyed) return
        val evidence = created.exoProbeEvidence() ?: run { finishMpvProbe?.invoke(); return }
        val revision = engineGeneration
        val current = spec
        val account = client.token
        mpvProbeJob = scope.launch {
            val supported = withContext(Dispatchers.Default) { isSafeExoProbe(candidate, evidence, ::hasAvcHardwareDecoder, current.subtitles) }
            if (!mayCommitMpvProbe(mpvProbePending, player === created, revision == engineGeneration,
                    spec == current, client.token == account, visible, exiting || isDestroyed) ||
                closeLockedSpoilerPlayback()) return@launch
            if (!supported) { finishMpvProbe?.invoke(); return@launch }
            val pendingSubtitles = spec.subtitlesPending
            mpvProbeJob = null
            finishMpvProbe = null
            handler.removeCallbacks(engineProbeTimeout)
            releasePlayer()
            exoRestoreSubtitlePending = pendingSubtitles
            spec = spec.copy(subtitlesPending = false)
            playbackFirstFrameReported = false
            startPreparedPlayer(null, true)
        }
    }

    private fun buildMediaItem(): MediaItem {
        return MediaItem.Builder().setMediaId("${spec.partId}").setUri(spec.url).build()
    }

    private fun refreshTicketAndRestart() {
        if (refreshing || navigationLoading || exiting || closeLockedSpoilerPlayback()) return
        capturePosition(); settleWatchTime(false); enqueueProgress("paused")
        val previous = specWithCurrentSourceAudioCodec()
        val audioIndex = if (previous.isServerConversion) previous.selectedAudioStreamIndex else
            player?.selectedAudioStreamIndex() ?: previous.selectedAudioStreamIndex
        releasePlayer(); refreshing = true
        showError("서버에서 재생 정보를 새로 받고 있습니다…")
        scope.launch {
            try {
                val refreshed = client.renewPlayback(previous, lastPosition, audioIndex)
                if (exiting || isDestroyed) return@launch
                spec = refreshed.copy(resumeMs = lastPosition, requestedPartId = spec.requestedPartId); watchedMs = 0
                updatePlaybackButtons()
                refreshing = false; pendingSeek = null
                preparePlayback(true)
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (_: Exception) {
                refreshing = false
                if (!exiting && !isDestroyed) showError("재생 정보를 다시 받지 못했습니다. 서버 연결을 확인해 주세요.")
            }
        }
    }

    private data class TrackChoice(val label: String, val selected: Boolean, val changesSelection: Boolean = true, val apply: () -> Unit)

    private fun applyControlHighlight(view: View) {
        view.background = getDrawable(R.drawable.tv_player_button)
        view.backgroundTintList = null
        view.elevation = 0f
        view.stateListAnimator = null
        (view as? TextView)?.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
    }

    private fun applyMenuHighlight(view: View) {
        view.background = getDrawable(R.drawable.tv_control_background)
        view.backgroundTintList = null
        view.elevation = 0f
        view.stateListAnimator = null
    }

    private fun showSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(0xFFF5F7F8.toInt())
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        panel.addView(TextView(this).apply {
            text = "재생 설정"; textSize = 16f; setTextColor(0xFF18242F.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val focusRows = mutableListOf<View>()
        var selectedTab = 0
        panel.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        tabs.visibility = View.GONE
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels - dp(210)).coerceAtLeast(dp(100))
                val offered = View.MeasureSpec.getSize(heightMeasureSpec)
                val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit
                    else minOf(offered, limit)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this).setView(panel).create()
        val close = Button(this).apply {
            id = View.generateViewId()
            text = "닫기"; textSize = 14f; setTextColor(0xFF18242F.toInt()); applyMenuHighlight(this)
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, dp(48)))

        fun addRow(label: String, value: String = "", action: () -> Unit) {
            val row = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true; isFocusable = true
                background = getDrawable(R.drawable.tv_control_background)
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                contentDescription = listOf(label, value).filter { it.isNotBlank() }.joinToString(", ")
                setOnClickListener { dialog.dismiss(); action() }
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(0xFF18242F.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = value; textSize = 12f; setTextColor(0xFF006B5B.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(0xFF52616F.toInt()); gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(18), -2))
            rows.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
            focusRows.add(row)
            rows.addView(View(this).apply { setBackgroundColor(0xFFDDE5E8.toInt()) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }

        val titles = arrayOf("재생", "영상", "음성", "자막")
        val buttons = titles.map { title ->
            Button(this).apply {
                id = View.generateViewId()
                text = title; textSize = 13f; isAllCaps = false
                setPadding(0, 0, 0, 0)
            }.also { tabs.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        }
        fun selectTab(index: Int) {
            selectedTab = index
            buttons.forEachIndexed { selected, button ->
                button.isSelected = selected == index
                button.setTextColor(if (selected == index) 0xFF006B5B.toInt() else 0xFF18242F.toInt())
                applyMenuHighlight(button)
            }
            rows.removeAllViews()
            focusRows.clear()
            when (index) {
                -1 -> {
                    addRow("배속", speedLabel()) { showSpeed() }
                    addRow("화면 크기", resizeLabel()) {
                        AlertDialog.Builder(this).setTitle("화면 크기")
                            .setSingleChoiceItems(resizeChoices.map { it.label }.toTypedArray(), resizeChoiceIndex()) { choice, position ->
                                resizeMode = resizeChoices[position].mode
                                resizeScale = resizeChoices[position].scale
                                applyResizeMode()
                                saveResizeMode()
                                choice.dismiss()
                                showResizeNotice()
                            }.setNegativeButton("닫기", null).show()
                    }
                    addRow("변환 (데이터 절약)", conversionLabel()) { showConversion() }
                    addRow("오디오 트랙", selectedTrackLabel(C.TRACK_TYPE_AUDIO)) { showTracks(C.TRACK_TYPE_AUDIO) }
                    addRow("자막 선택", if (!subtitlesEnabled) "끔" else selectedTrackLabel(C.TRACK_TYPE_TEXT)) { showTracks(C.TRACK_TYPE_TEXT) }
                    addRow("자막 글꼴", PlexSubtitleFonts.options(this).firstOrNull { it.key == subtitleFont }?.label.orEmpty()) { showSubtitleFont() }
                    addRow("자막 위치") { showSubtitlePosition() }
                    addRow("자막 모양") { showSubtitleAppearance() }
                    if (externalSubtitleId != null) addRow("자막 싱크", String.format(Locale.KOREAN, "%+.1f초", subtitleOffsetMs / 1000.0)) { showSubtitleOffset() }
                    addRow("싱크 다시 맞추기") { resynchronize() }
                }
                0 -> addRow("배속", speedLabel()) { showSpeed() }
                1 -> {
                    addRow("변환 (데이터 절약)", conversionLabel()) { showConversion() }
                    addRow("화면 크기", resizeLabel()) {
                        cycleResizeMode(); selectTab(1)
                    }
                }
                2 -> {
                    addRow("오디오 트랙", selectedTrackLabel(C.TRACK_TYPE_AUDIO)) { showTracks(C.TRACK_TYPE_AUDIO) }
                    addRow("싱크 다시 맞추기") { resynchronize() }
                }
                3 -> {
                    val subtitleLabel = if (!subtitlesEnabled) "끔" else
                        spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) }?.label
                            ?: selectedTrackLabel(C.TRACK_TYPE_TEXT)
                    addRow("자막 선택", subtitleLabel) { showTracks(C.TRACK_TYPE_TEXT) }
                    addRow("자막 글꼴", PlexSubtitleFonts.options(this).firstOrNull { it.key == subtitleFont }?.label.orEmpty()) { showSubtitleFont() }
                    addRow("자막 위치", if (television) "방향별 위치 조절" else "길게 눌러 이동") { showSubtitlePosition() }
                    addRow("자막 모양") { showSubtitleAppearance() }
                    if (externalSubtitleId != null) addRow("자막 싱크", String.format(Locale.KOREAN, "%+.1f초", subtitleOffsetMs / 1000.0)) { showSubtitleOffset() }
                }
            }
            if (television) {
                buttons.forEachIndexed { position, button ->
                    button.nextFocusLeftId = buttons[(position - 1).coerceAtLeast(0)].id
                    button.nextFocusRightId = buttons[(position + 1).coerceAtMost(buttons.lastIndex)].id
                    button.nextFocusUpId = button.id
                    button.nextFocusDownId = focusRows.firstOrNull()?.id ?: close.id
                }
                focusRows.forEachIndexed { position, row ->
                    row.nextFocusUpId = if (position == 0) (if (selectedTab < 0) row.id else buttons[selectedTab].id) else focusRows[position - 1].id
                    row.nextFocusDownId = focusRows.getOrNull(position + 1)?.id ?: close.id
                    row.nextFocusLeftId = row.id
                    row.nextFocusRightId = row.id
                }
                close.nextFocusUpId = focusRows.lastOrNull()?.id ?: buttons.first().id
                close.nextFocusDownId = close.id
                close.background = getDrawable(R.drawable.tv_control_background)
                close.backgroundTintList = null
            }
        }
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener { selectTab(index) }
            if (television) button.setOnFocusChangeListener { _, focused -> if (focused) selectTab(index) }
        }
        selectTab(-1)
        dialog.show()
        if (television) focusRows.firstOrNull()?.requestFocus()
        dialog.window?.apply {
            setGravity(if (television) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
            setLayout(minOf(dp(360), resources.displayMetrics.widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun selectedTrackLabel(type: Int): String {
        if (type == C.TRACK_TYPE_TEXT) {
            if (!subtitlesEnabled) return "끔"
            spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) }
                ?.let { return it.label }
        }
        val format = player?.currentTracks?.groups?.firstNotNullOfOrNull { group ->
            if (group.type != type) null else (0 until group.length)
                .firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat)
        } ?: return "자동"
        return format.label?.takeIf { it.isNotBlank() } ?: when (
            format.language?.lowercase(Locale.ROOT)?.substringBefore('-')) {
            "ko", "kor" -> "한국어"; "en", "eng" -> "영어"; "ja", "jpn" -> "일본어"
            null, "und" -> "자동"; else -> format.language.orEmpty()
        }
    }

    private fun showTracks(type: Int) {
        val active = player ?: return
        if (type == C.TRACK_TYPE_TEXT && (exiting || refreshing || navigationLoading || conversionLoading ||
                playerGestureActive || gestures.locked || closeLockedSpoilerPlayback())) return
        if (type == C.TRACK_TYPE_TEXT && spec.subtitlesPending && !subtitleListFailed) loadDeferredSubtitles(active)
        val choices = mutableListOf<TrackChoice>()
        val nativeText = if (type == C.TRACK_TYPE_TEXT) active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        } else emptyList()
        val nativeIds = nativeText.mapNotNull { (group, index) -> group.getTrackFormat(index).id }.toSet()
        // Converted responses can renumber their streams. Only the original file
        // and an exact FFmpeg stream-index mapping establish a duplicate track.
        val aliases = if (type == C.TRACK_TYPE_TEXT && !spec.isServerConversion) spec.subtitles
            .filter { it.isEmbedded }.mapNotNull { track ->
                (track.streamIndex?.takeIf { it >= 0 }?.let(active::subtitleTrackIdForStreamIndex)
                    ?: if (active is ExoPlayer && exoEmbeddedSubtitle(track) && nativeText.size == 1 &&
                        nativeText.single().first.getTrackFormat(nativeText.single().second).let { exoMovTextMime(it.sampleMimeType, it.codecs) })
                        nativeText.single().first.getTrackFormat(nativeText.single().second).id else null)
                    ?.takeIf { it in nativeIds }?.let { track.id to it }
            }.toMap() else emptyMap()
        val selectedNativeIds = nativeText.filter { (group, index) -> group.isTrackSelected(index) }
            .mapNotNull { (group, index) -> group.getTrackFormat(index).id }.toSet()
        if (type == C.TRACK_TYPE_TEXT) {
            choices += TrackChoice("자막 끄기", !subtitlesEnabled) {
                subtitleManuallySelected = true
                subtitlesEnabled = false; clearExternalSubtitle()
                active.trackSelectionParameters = active.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
                playerView.subtitleView?.visibility = View.GONE
            }
            spec.subtitles.forEach { track ->
                val selectedId = embeddedServerSubtitleId ?: externalSubtitleId
                val selected = subtitlesEnabled && (selectedId == track.id ||
                    (selectedId == null && aliases[track.id]?.let { it in selectedNativeIds } == true))
                choices += TrackChoice(subtitleMenuLabel(track.label, track.language, track.format ?: track.mimeType,
                    if (track.isEmbedded) "서버 내장" else "서버 외부", track.forced == true, track.sdh == true), selected) {
                    subtitleManuallySelected = true
                    selectServerSubtitle(track)
                }
            }
            if (spec.subtitlesPending) {
                choices += TrackChoice(if (subtitleListFailed) "서버 자막 다시 불러오기" else "서버 자막 불러오는 중", false, changesSelection = false) {
                    if (subtitleListFailed) loadDeferredSubtitles(active)
                    else toast("서버 자막을 불러오고 있습니다.")
                }
            }
        }
        active.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                if (type == C.TRACK_TYPE_TEXT && format.id != null && format.id in aliases.values) continue
                val language = when (format.language?.lowercase(Locale.ROOT)?.substringBefore('-')) {
                    "ko", "kor" -> "한국어"; "en", "eng" -> "영어"; "ja", "jpn" -> "일본어"; null, "und" -> null; else -> format.language
                }
                val label = if (type == C.TRACK_TYPE_TEXT) subtitleMenuLabel(format.label, format.language,
                    format.codecs ?: format.sampleMimeType, "영상 내장",
                    format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0)
                else listOfNotNull(format.label, language, format.codecs ?: format.sampleMimeType?.substringAfter('/'))
                    .filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "트랙 ${choices.size + 1}" }
                choices += TrackChoice(label, group.isTrackSelected(index) && (type != C.TRACK_TYPE_TEXT ||
                    (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null))) {
                    if (type == C.TRACK_TYPE_TEXT) {
                        subtitleManuallySelected = true
                        subtitlesEnabled = true; externalSubtitleId = null; clearExternalSubtitle()
                        playerView.subtitleView?.setCues(emptyList())
                        playerView.subtitleView?.visibility = View.VISIBLE
                    }
                    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                        .clearOverridesOfType(type).addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                    if (type == C.TRACK_TYPE_TEXT) renderEmbeddedCues(active.currentCues)
                }
            }
        }
        if (choices.isEmpty()) { toast("선택할 수 있는 트랙이 없습니다."); return }
        val selected = choices.indexOfFirst { it.selected }
        val choose = android.content.DialogInterface.OnClickListener { dialog, index ->
            if (player === active && !exiting && (type != C.TRACK_TYPE_TEXT ||
                    (!gestures.locked && !playerGestureActive && !refreshing && !conversionLoading &&
                        !navigationLoading && !closeLockedSpoilerPlayback()))) {
                if (type == C.TRACK_TYPE_TEXT && choices[index].changesSelection) pendingTextPreference = null
                choices[index].apply()
            }
            dialog.dismiss()
        }
        val builder = AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_AUDIO) "오디오 트랙" else "자막 선택")
            .setNegativeButton("취소", null)
        if (type != C.TRACK_TYPE_TEXT) {
            builder.setSingleChoiceItems(choices.map { it.label }.toTypedArray(), selected, choose).show().apply {
                listView.selector = getDrawable(R.drawable.tv_control_background)
            }
            return
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, choices.map { it.label }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    (this as TextView).apply {
                        textSize = 13f; minHeight = dp(48); setSingleLine(false); maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(0xFF18242F.toInt()); setPadding(dp(12), dp(4), dp(12), dp(4))
                        background = getDrawable(R.drawable.tv_control_background)
                    }
                }
        }
        subtitleDialog?.dismiss()
        val dialog = builder.setSingleChoiceItems(adapter, selected, choose).create()
        subtitleDialog = dialog
        dialog.setOnDismissListener { if (subtitleDialog === dialog) subtitleDialog = null }
        dialog.show()
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            textSize = 16f; setTextColor(0xFF18242F.toInt())
        }
        dialog.listView.selector = getDrawable(R.drawable.tv_control_background)
        dialog.listView.setSelection(selected.coerceAtLeast(0))
        if (television) dialog.listView.requestFocus()
        dialog.window?.apply {
            setLayout(minOf(dp(360), resources.displayMetrics.widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF5F7F8.toInt()); cornerRadius = dp(3).toFloat()
            })
        }
    }

    private fun showSpeed() {
        val speeds = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
        AlertDialog.Builder(this).setTitle("배속").setSingleChoiceItems(speeds.map(::speedLabel).toTypedArray(), speeds.indexOf(speed)) { dialog, index ->
            speed = speeds[index]; player?.setPlaybackSpeed(speed)
            getSharedPreferences("gsy_settings", MODE_PRIVATE).edit().putFloat("playback_speed", speed).apply()
            updatePlaybackButtons(); dialog.dismiss()
        }.setNegativeButton("취소", null).show().apply { listView.selector = getDrawable(R.drawable.tv_control_background) }
    }

    private fun speedLabel(value: Float = speed) = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + "×"

    private fun selectedTrackPreference(type: Int): PlaybackTrackPreference? = player?.currentTracks?.groups
        ?.firstNotNullOfOrNull { group ->
            if (group.type != type) null else (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                ?.let(group::getTrackFormat)?.let { PlaybackTrackPreference(it.language, it.label) }
        }

    private fun specWithCurrentSourceAudioCodec(): PlaybackSpec {
        // A converted response exposes its AAC output, not the original selected track.
        if (spec.isServerConversion) return spec
        val format = player?.currentTracks?.groups?.firstNotNullOfOrNull { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) null else (0 until group.length)
                .firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat)
        }
        val codec = format?.codecs?.takeIf { it.isNotBlank() } ?: when (format?.sampleMimeType) {
            "audio/eac3", "audio/eac3-joc" -> "eac3"
            "audio/ac3" -> "ac3"
            "audio/mp4a-latm" -> "aac"
            else -> null
        }
        return codec?.let { spec.copy(selectedAudioCodec = it) } ?: spec
    }

    private fun restorePendingTextPreference() {
        val preference = pendingTextPreference ?: return
        val active = player ?: return
        if (!subtitlesEnabled || externalSubtitleId != null || embeddedServerSubtitleId != null) {
            pendingTextPreference = null; return
        }
        val tracks = active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        if (tracks.isEmpty() && active.playbackState != Player.STATE_READY) return
        pendingTextPreference = null
        val index = matchingPlaybackTrack(preference, tracks.map { (group, index) ->
            group.getTrackFormat(index).let { PlaybackTrackPreference(it.language, it.label) }
        }) ?: return
        val (group, track) = tracks[index]
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(track))).build()
    }

    private fun conversionLabel(): String = spec.conversionHeight?.let { "${it}p" }
        ?: if (spec.isServerConversion) "서버 자동" else "원본"

    private fun closeLockedSpoilerPlayback(): Boolean {
        if (!::spec.isInitialized || !::client.isInitialized || spec.sourceItem?.isSpoiler != true || client.spoilerUnlocked) return false
        if (activityStarted && spoilerRecovery != null && client.spoilerPlaybackReauthenticationFailed && !exiting) {
            showSpoilerReauthenticationFailure()
            return true
        }
        if (!exiting) {
            exiting = true
            presenceLifecycle.close(endSession = false)
            cancelSpoilerPlaybackReauthentication()
            settleWatchTime(false)
            neighborsJob?.cancel(); conversionJob?.cancel()
            releasePlayer()
            toast("스포일러 표시가 잠겼습니다. 라이브러리에서 다시 열어 주세요.")
            finish()
        }
        return true
    }

    private fun ensureSpoilerPlaybackReauthentication() {
        if (!activityStarted || exiting || !prepared || refreshing || conversionLoading || navigationLoading ||
            spoilerRecovery != null || spec.sourceItem?.isSpoiler != true || player == null ||
            player?.playbackState == Player.STATE_ENDED || spoilerReauthenticationJob?.isActive == true) return
        val current = player
        val currentSpec = spec
        spoilerReauthenticationJob = scope.launch {
            while (activityStarted && !exiting && player === current && prepared &&
                spec.partId == currentSpec.partId && spec.libraryId == currentSpec.libraryId &&
                spec.viewItemId == currentSpec.viewItemId && spec.sessionId == currentSpec.sessionId) {
                if (refreshing || conversionLoading || navigationLoading || gestures.isSeekGestureActive) { delay(500); continue }
                val remaining = client.spoilerRemainingMs
                if (remaining <= 0) { closeLockedSpoilerPlayback(); return@launch }
                val wait = SpoilerPlaybackReauthenticationPolicy.delayMs(remaining, client.spoilerLifetimeMs)
                if (wait > 0) { delay(wait); continue }
                reauthenticateSpoilerPlayback()
                return@launch
            }
        }
    }

    private fun reauthenticateSpoilerPlayback() {
        val active = player ?: return
        if (!activityStarted || exiting || !prepared || refreshing || conversionLoading || navigationLoading ||
            spoilerRotationJob?.isActive == true || spec.sourceItem?.isSpoiler != true) return
        fun snapshot(): ConversionRestore {
            capturePosition()
            return ConversionRestore(specWithCurrentSourceAudioCodec(), lastPosition,
                gestures.playWhenReadyBeforeSeek ?: active.playWhenReady,
                spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) },
                selectedTrackPreference(C.TRACK_TYPE_AUDIO),
                if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null)
                    selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
                if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
                lastDuration)
        }
        val previous = snapshot()
        spoilerRecovery = previous
        spoilerPreparing = true; refreshing = true
        conversionResumeWhenVisible = null
        resumeWhenVisible = previous.playing
        updatePlaybackButtons()
        val acknowledgement = CompletableDeferred<Boolean>()
        settleWatchTime(visible && active.isPlaying)
        submitProgress(ProgressUpdate(previous.spec, previous.position, previous.duration,
            if (active.isPlaying) "playing" else "paused", watchedMs, acknowledgement))
        spoilerRotationJob = scope.launch {
            var rotation: Long? = null
            try {
                val flushBudget = SpoilerPlaybackReauthenticationPolicy.flushTimeoutMs(client.spoilerRemainingMs)
                if (flushBudget > 0) withTimeoutOrNull(flushBudget) { acknowledgement.await() }
                if (!activityStarted || exiting || player !== active || spoilerRecovery !== previous) return@launch
                val epoch = playbackAuthentication.beginRotation()
                presenceLifecycle.close(endSession = false)
                rotation = epoch
                progressDeliveryJob?.cancel()
                neighborsJob?.cancel()
                cancelPlaybackMetadata()
                settleWatchTime(visible && active.isPlaying)
                val watchedBeforePreparation = watchedMs
                // Keep the existing decoder and buffered frames while the network
                // prepares fresh authority. No old-context progress crosses unlock.
                val budget = SpoilerPlaybackReauthenticationPolicy.timeoutMs(client.spoilerRemainingMs)
                if (budget <= 0) throw ApiException("비공개 재생 권한을 다시 확인해야 합니다.", 403)
                val refreshed = withTimeoutOrNull(budget) {
                    client.reauthenticateSpoilerPlayback(previous.spec, previous.position, previous.audioIndex)
                } ?: throw ApiException("비공개 재생 권한 확인 시간이 초과되었습니다.")
                if (!activityStarted || exiting || player !== active || spoilerRecovery !== previous ||
                    !playbackAuthentication.isCurrent(epoch)) return@launch
                if (refreshed.itemId != previous.spec.itemId || refreshed.partId != previous.spec.partId ||
                    refreshed.libraryId != previous.spec.libraryId || refreshed.viewItemId != previous.spec.viewItemId ||
                    refreshed.sourceItem?.isSpoiler != true) throw ApiException("비공개 재생 정보를 다시 확인해야 합니다.")
                if (closeLockedSpoilerPlayback()) return@launch
                val ended = !gestures.isSeekGestureActive && active.reconcilePlaybackCompletion()
                finishRemoteSeeking()
                gestures.cancelSeekGesture()?.let { lastPosition = it; pendingSeek = it }
                val latest = snapshot()
                settleWatchTime(false)
                val watchedDuringPreparation = (watchedMs - watchedBeforePreparation).coerceAtLeast(0)
                active.pause()
                spoilerPreparing = false
                subtitleJob?.cancel(); subtitleSelectionRevision++; subtitleTimeline = null
                if (!playbackAuthentication.completeRotation(epoch)) return@launch
                refreshing = false; conversionResumeWhenVisible = null
                if (ended) {
                    // The old source can finish during a slow authorization reply.
                    // Record its end with fresh authority rather than reload its tail.
                    spec = refreshed.copy(resumeMs = latest.position)
                    lastPosition = latest.position
                    lastDuration = maxOf(latest.duration, refreshed.durationMs)
                    watchedMs = watchedDuringPreparation
                    spoilerRecovery = null
                    spoilerRotationJob = null
                    finishCompletedPlayback()
                } else {
                    // MPV rewrites converted start_ms from this latest source position.
                    lastSyncAt = 0
                    applyConversionPlayback(refreshed, latest)
                    watchedMs = watchedDuringPreparation
                    spoilerRecovery = null
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (activityStarted && !exiting && spoilerRecovery === previous &&
                    (rotation == null || playbackAuthentication.isCurrent(rotation))) {
                    if (!client.spoilerUnlocked && !client.spoilerPlaybackReauthenticationFailed) closeLockedSpoilerPlayback()
                    else showSpoilerReauthenticationFailure()
                }
            } finally {
                if (spoilerRotationJob === currentCoroutineContext()[Job]) {
                    spoilerRotationJob = null
                    spoilerPreparing = false
                }
                if (!exiting && !isDestroyed) updatePlaybackButtons()
            }
        }
    }

    private fun hideSpoilerPlaybackMedia() {
        playerView.visibility = View.INVISIBLE
        subtitleOverlay.subtitleText = ""; subtitleOverlay.visibility = View.INVISIBLE
        hideSegmentActions()
        playerView.hideController(); hideLockControl()
    }

    private fun showSpoilerReauthenticationFailure() {
        presenceLifecycle.close(endSession = false)
        capturePosition(); settleWatchTime(false)
        prepared = false; refreshing = false; spoilerPreparing = false; conversionResumeWhenVisible = null
        if (playbackAuthentication.captureProgress() != null) {
            playbackAuthentication.beginRotation()
            progressDeliveryJob?.cancel()
        }
        releasePlayer()
        hideSpoilerPlaybackMedia()
        showError("비공개 재생 권한을 다시 확인하지 못했습니다.\n라이브러리로 돌아가 PIN을 다시 입력해 주세요. 현재 위치는 유지했습니다.")
        errorPanel.getChildAt(1).apply { visibility = View.GONE; isEnabled = false }
        if (television) errorPanel.getChildAt(2).requestFocus()
    }

    private fun cancelSpoilerPlaybackReauthentication() {
        spoilerPreparing = false
        spoilerReauthenticationJob?.cancel(); spoilerReauthenticationJob = null
        if (spoilerRotationJob?.isActive == true || spoilerRecovery != null) {
            playbackAuthentication.beginRotation()
            progressDeliveryJob?.cancel()
        }
        spoilerRotationJob?.cancel(); spoilerRotationJob = null
    }

    private fun refreshPlaybackMetadata(readyPlayer: Player) {
        if (player !== readyPlayer || exiting || isDestroyed || closeLockedSpoilerPlayback()) return
        val revision = playbackMetadataGate.takePending() ?: return
        val current = spec
        fun isCurrent() = player === readyPlayer && !exiting && !isDestroyed && playbackMetadataGate.isCurrent(revision)
        playbackMetadataJob = scope.launch {
            loadPlaybackMetadataInOrder(
                loadMarkers = {
                    if (isCurrent() && !closeLockedSpoilerPlayback() && current.partId > 0 &&
                        (current.url.startsWith("https://", true) || current.url.startsWith("http://", true))) {
                        // Keep the server's source timestamps intact until the
                        // player's actual full duration is known.
                        val markers = client.playbackMarkers(current, durationMs = 0)
                        currentCoroutineContext().ensureActive()
                        if (isCurrent() && !closeLockedSpoilerPlayback()) {
                            spec = spec.copy(markers = markers)
                            refreshSegmentActions()
                        }
                    }
                },
                loadNeighbors = {
                    if (isCurrent() && current.sourceItem != null && !closeLockedSpoilerPlayback()) {
                        val neighbors = client.playbackNeighbors(current)
                        currentCoroutineContext().ensureActive()
                        if (isCurrent() && !closeLockedSpoilerPlayback()) {
                            playbackNeighbors = neighbors
                            updateNavigationButtons()
                        }
                    }
                },
                // A brief server failure at startup must not suppress credits for the
                // entire video. Never infer a missing marker; retry only transient errors.
                markerRetryDelaysMs = listOf(2_000, 10_000, 30_000, 60_000),
                markerAttemptTimeoutMs = 3_000,
                markerRetryTimeoutMs = 20_000,
            )
        }
    }

    /** Subtitle discovery is optional and must never delay the first video frame. */
    private fun loadDeferredSubtitles(readyPlayer: Player) {
        if (!::spec.isInitialized || !spec.subtitlesPending || subtitleListJob?.isActive == true ||
            player !== readyPlayer || !prepared || exiting || refreshing || conversionLoading || navigationLoading ||
            isDestroyed || closeLockedSpoilerPlayback()) return
        val current = spec
        val selectionRevision = subtitleSelectionRevision
        fun isCurrent(): Boolean = player === readyPlayer && !exiting && !isDestroyed && !refreshing &&
            !conversionLoading && !navigationLoading && sameDeferredSubtitleSource(current, spec)
        subtitleListFailed = false
        subtitleListJob = scope.launch {
            try {
                val tracks = client.playbackSubtitles(current)
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || closeLockedSpoilerPlayback()) return@launch
                spec = spec.copy(subtitles = tracks, subtitlesPending = false)
                val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                val preferred = deferredSubtitleSelection(spec, savedId, deferredSubtitlePreference,
                    subtitlesEnabled, subtitleManuallySelected, pendingTextPreference != null,
                    subtitleSelectionRevision == selectionRevision)
                deferredSubtitlePreference = null
                preferred?.let(::selectServerSubtitle)
                if (subtitleDialog?.isShowing == true) toast("서버 자막을 불러왔습니다. 자막 선택을 다시 열어 주세요.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent() && !closeLockedSpoilerPlayback()) {
                    subtitleListFailed = true
                    toast("서버 자막을 불러오지 못했습니다. 자막 선택에서 다시 시도해 주세요.")
                }
            }
        }
    }

    private fun cancelPlaybackMetadata() {
        playbackMetadataGate.cancel()
        playbackMetadataJob?.cancel()
        playbackMetadataJob = null
    }

    private fun updateNavigationButtons() {
        if (!::playerView.isInitialized) return
        val available = !navigationLoading && !refreshing && !conversionLoading && !exiting
        listOf(R.id.player_previous to playbackNeighbors?.previous, R.id.player_next to playbackNeighbors?.next)
            .forEach { (id, item) ->
                playerView.findViewById<ImageButton>(id).apply {
                    isEnabled = available && item != null
                    alpha = if (isEnabled) 1f else .35f
                    val label = if (id == R.id.player_previous) "이전 영상" else "다음 영상"
                    contentDescription = item?.let { "$label, ${it.showTitle ?: it.title}" } ?: label
                    tooltipText = contentDescription
                }
            }
        updateSegmentSkipButton()
    }

    private fun switchNeighbor(forward: Boolean, creditsEpisode: Boolean = false) {
        val active = player ?: return
        if (navigationLoading || refreshing || conversionLoading || exiting || closeLockedSpoilerPlayback()) return
        val target = if (creditsEpisode) {
            if (!forward || currentSegmentMarker(active)?.type != "credits") return
            creditsNextEpisodeTarget() ?: return
        } else (if (forward) playbackNeighbors?.next else playbackNeighbors?.previous) ?: return
        val wasPlaying = gestures.playWhenReadyBeforeSeek ?: active.playWhenReady
        val retainedSeek = gestures.cancelSeekGesture()
        retainedSeek?.let { lastPosition = it; pendingSeek = it }
        capturePosition()
        val previous = ConversionRestore(specWithCurrentSourceAudioCodec(), lastPosition, wasPlaying,
            spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) },
            selectedTrackPreference(C.TRACK_TYPE_AUDIO),
            if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null)
                selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
            if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
            lastDuration)
        enqueueProgress(if (completed) "completed" else "stopped")
        navigationLoading = true; refreshing = true
        conversionResumeWhenVisible = wasPlaying; resumeWhenVisible = wasPlaying
        active.pause(); playerView.hideController(); hideLockControl(); updatePlaybackButtons()
        val loading = AlertDialog.Builder(this).setTitle(if (forward) "다음 영상" else "이전 영상")
            .setMessage("영상을 불러오고 있습니다…")
            .setNegativeButton("취소") { _, _ -> neighborsJob?.cancel() }.create()
        loading.setOnCancelListener { neighborsJob?.cancel() }
        loading.show()
        neighborsJob = scope.launch {
            var replaced = false
            try {
                var next = client.playback(target, deferSubtitles = true).copy(resumeMs = 0, navigationQuery = previous.spec.navigationQuery)
                previous.spec.conversionHeight?.let { height -> next = client.convertPlayback(next, height, 0) }
                if (exiting || isDestroyed || closeLockedSpoilerPlayback()) return@launch
                replaced = true
                conversionRestore = previous
                conversionLoading = true
                refreshing = false
                applyConversionPlayback(next, previous.copy(position = 0, playing = creditsEpisode || previous.playing))
                handler.postDelayed(conversionTimeout, 45_000)
                if (spec.sourceItem?.isSpoiler == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (_: Exception) {
                if (!exiting && !isDestroyed) {
                    if (replaced) restoreConversion("영상을 불러오지 못했습니다.")
                    else toast("영상을 불러오지 못했습니다. 현재 영상을 유지합니다.")
                }
            } finally {
                loading.dismiss()
                navigationLoading = false
                if (conversionRestore != null || !replaced) refreshing = false
                if (!refreshing) conversionResumeWhenVisible = null
                if (!replaced && !exiting && !isDestroyed && player === active) {
                    if (retainedSeek != null) { pendingSeek = null; active.seekTo(previous.position) }
                    active.playWhenReady = wasPlaying && visible; resumeWhenVisible = wasPlaying
                }
                if (!isDestroyed) {
                    updatePlaybackButtons()
                }
            }
        }
    }

    private fun updatePlaybackButtons() {
        if (!::playerView.isInitialized) return
        playerView.findViewById<Button>(R.id.player_speed).apply {
            text = speedLabel(); contentDescription = "배속 설정, $text"; tooltipText = contentDescription
        }
        playerView.findViewById<Button>(R.id.player_conversion).apply {
            text = "변환"; contentDescription = "변환 (데이터 절약), ${conversionLabel()}"; tooltipText = contentDescription
            isEnabled = !conversionLoading && !refreshing
        }
        updateNavigationButtons()
        updateSubtitleButton()
    }

    private fun updateSubtitleButton() {
        if (!::playerView.isInitialized) return
        val controllerVisible = if (::gestures.isInitialized) gestures.controllerVisible else playerView.isControllerFullyVisible
        val available = controllerVisible && !playerGestureActive &&
            (!::gestures.isInitialized || !gestures.locked) && !exiting && !refreshing &&
            !conversionLoading && !navigationLoading
        playerView.findViewById<ImageButton>(R.id.player_subtitles).apply {
            visibility = if (available) View.VISIBLE else View.INVISIBLE
            isEnabled = available && player != null
            tooltipText = "자막 선택"
        }
    }

    private fun showConversion() {
        if (conversionLoading || refreshing || navigationLoading || exiting) return
        val serverMedia = spec.url.startsWith("https://", true) || spec.url.startsWith("http://", true)
        val heights = if (serverMedia) listOf(null, 1080, 720, 480) else listOf(null)
        val labels = heights.map { height -> when (height) {
            null -> "원본"
            1080 -> "1080p"
            720 -> "720p"
            else -> "480p"
        } }
        val selected = if (spec.isServerConversion && spec.conversionHeight == null) -1 else heights.indexOf(spec.conversionHeight)
        val dialog = AlertDialog.Builder(this).setTitle("변환 (데이터 절약)")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                val height = heights[index]
                if (height == spec.conversionHeight && (height != null || !spec.isServerConversion)) return@setSingleChoiceItems
                switchConversion(height)
            }.setNegativeButton("취소", null).show()
        if (television) dialog.window?.setLayout(dp(360), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun switchConversion(height: Int?) {
        val active = player ?: return
        if (refreshing || conversionLoading || navigationLoading || exiting) return
        val wasPlaying = gestures.playWhenReadyBeforeSeek ?: active.playWhenReady
        val retainedSeek = gestures.cancelSeekGesture()
        retainedSeek?.let { lastPosition = it; pendingSeek = it }
        capturePosition()
        val snapshot = ConversionRestore(specWithCurrentSourceAudioCodec(), lastPosition, wasPlaying,
            spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) },
            selectedTrackPreference(C.TRACK_TYPE_AUDIO),
            if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null)
                selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
            if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
            lastDuration)
        settleWatchTime(false); enqueueProgress("paused")
        refreshing = true; conversionLoading = true
        conversionResumeWhenVisible = snapshot.playing; resumeWhenVisible = snapshot.playing
        active.pause(); playerView.hideController(); hideLockControl(); updatePlaybackButtons()
        val loading = AlertDialog.Builder(this).setTitle("변환 (데이터 절약)")
            .setMessage("현재 시청 위치에서 ${height?.let { "${it}p" } ?: "원본 재생"}을 준비하고 있습니다…")
            .setNegativeButton("취소") { _, _ -> conversionJob?.cancel() }.create()
        loading.setOnCancelListener { conversionJob?.cancel() }
        loading.show()
        conversionJob = scope.launch {
            var replaced = false
            try {
                val refreshed = client.convertPlayback(snapshot.spec, height, snapshot.position, snapshot.audioIndex)
                if (exiting || isDestroyed) return@launch
                conversionRestore = snapshot
                replaced = true
                refreshing = false
                applyConversionPlayback(refreshed, snapshot)
                handler.postDelayed(conversionTimeout, 45_000)
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (error: Exception) {
                if (!exiting && !isDestroyed) {
                    if (replaced) restoreConversion("변환 영상을 준비하지 못했습니다.")
                    else toast(conversionFailureMessage(error))
                }
            } finally {
                loading.dismiss()
                if (!replaced) {
                    refreshing = false; conversionLoading = false
                    if (!exiting && !isDestroyed && player === active) {
                        if (retainedSeek != null) { pendingSeek = null; active.seekTo(snapshot.position) }
                        active.playWhenReady = snapshot.playing && visible; resumeWhenVisible = snapshot.playing
                    }
                }
                if (!refreshing) conversionResumeWhenVisible = null
                if (!isDestroyed) updatePlaybackButtons()
            }
        }
    }

    private fun applyConversionPlayback(refreshed: PlaybackSpec, previous: ConversionRestore) {
        clearExternalSubtitle(); externalSubtitleId = null
        subtitleManuallySelected = refreshed.subtitlesPending && previous.text != null
        deferredSubtitlePreference = if (refreshed.subtitlesPending) previous.subtitle else null
        val nativeText = previous.text.takeUnless { refreshed.isServerConversion }
        val preferredText = previous.subtitle ?: previous.text?.let { preference ->
            matchingPlaybackTrack(preference, refreshed.subtitles.map { PlaybackTrackPreference(it.language, it.title ?: it.label) })
                ?.let(refreshed.subtitles::get)
        }
        val subtitle = if (!subtitlesEnabled || nativeText != null) null else
            replacementSubtitle(preferredText, refreshed.subtitles) ?: preferredSubtitle(refreshed)
        spec = refreshed.copy(resumeMs = previous.position)
        val sameSource = refreshed.itemId == previous.spec.itemId && refreshed.partId == previous.spec.partId &&
            refreshed.libraryId == previous.spec.libraryId && refreshed.viewItemId == previous.spec.viewItemId
        lastDuration = maxOf(0L, refreshed.durationMs, if (sameSource) previous.duration else 0L)
        playerView.findViewById<TextView>(R.id.player_title).text = spec.controllerTitle
        externalSubtitleId = subtitle?.takeUnless { it.isEmbedded }?.id
        embeddedServerSubtitleId = subtitle?.takeIf { it.isEmbedded }?.id
        pendingAudioPreference = previous.audio; pendingTextPreference = nativeText
        lastPosition = previous.position; pendingSeek = null; watchedMs = 0
        updatePlaybackButtons()
        preparePlayback(previous.playing)
    }

    private fun restoreConversion(reason: String) {
        val previous = conversionRestore ?: return
        conversionRestore = null
        handler.removeCallbacks(conversionTimeout)
        if (exiting || isDestroyed) return
        refreshing = true; conversionLoading = true
        conversionResumeWhenVisible = previous.playing
        releasePlayer()
        // A ticket may have expired while the encoder was starting. Renew the old
        // transport once, keeping its original source position and selected tracks.
        showError("$reason\n이전 재생 방식으로 돌아가고 있습니다…")
        conversionJob = scope.launch {
            try {
                val restored = client.renewPlayback(previous.spec, previous.position, previous.audioIndex)
                if (exiting || isDestroyed) return@launch
                refreshing = false
                applyConversionPlayback(restored, previous)
                toast("이전 재생 방식으로 돌아왔습니다.")
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (_: Exception) {
                spec = previous.spec; lastPosition = previous.position; lastDuration = previous.duration
                playerView.findViewById<TextView>(R.id.player_title).text = spec.controllerTitle
                clearExternalSubtitle()
                externalSubtitleId = previous.subtitle?.takeUnless { it.isEmbedded }?.id
                embeddedServerSubtitleId = previous.subtitle?.takeIf { it.isEmbedded }?.id
                pendingAudioPreference = previous.audio; pendingTextPreference = previous.text
                if (!exiting && !isDestroyed) showError("$reason\n이전 재생 방식을 다시 불러오지 못했습니다. 서버 연결을 확인한 뒤 다시 재생해 주세요.")
            } finally {
                refreshing = false; conversionLoading = false; conversionResumeWhenVisible = null
                if (!isDestroyed) updatePlaybackButtons()
            }
        }
    }

    private fun conversionFailureMessage(error: Exception): String = when ((error as? ApiException)?.status) {
        401 -> "로그인이 만료되었습니다. 서버에 다시 로그인해 주세요."
        403 -> "서버에서 영상 변환이 허용되지 않습니다. 기존 재생을 유지합니다."
        503 -> "서버의 영상 변환 기능을 사용할 수 없습니다. 기존 재생을 유지합니다."
        else -> "변환을 시작하지 못했습니다. 기존 재생을 유지합니다."
    }

    private val resizeModes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    private val resizeLabels = arrayOf("화면 맞춤", "화면 채움", "화면 늘림")
    private data class ResizeChoice(val mode: Int, val scale: Float, val label: String)
    private val resizeChoices get() = resizeModes.mapIndexed { index, mode -> ResizeChoice(mode, 1f, resizeLabels[index]) } +
        listOf(ResizeChoice(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.1f, "확대 110%"),
            ResizeChoice(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.25f, "확대 125%"),
            ResizeChoice(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.5f, "확대 150%"))
    private fun resizeChoiceIndex() = resizeChoices.indexOfFirst { it.mode == resizeMode && it.scale == resizeScale }.coerceAtLeast(0)
    private fun resizeLabel() = resizeChoices[resizeChoiceIndex()].label
    private fun saveResizeMode() {
        getSharedPreferences("gsy_settings", MODE_PRIVATE).edit().putInt("resize_mode", resizeMode)
            .putFloat("resize_scale", resizeScale).apply()
    }
    private fun showResizeNotice() {
        resizeNotice?.cancel()
        resizeNotice = Toast.makeText(this, resizeLabel(), Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun applyResizeMode() {
        // Keep the TV output surface at screen size, then let the renderer fit/crop/stretch.
        // Otherwise MPV letterboxes again inside the already resized Media3 surface.
        playerView.resizeMode = if (player is ExoPlayer) AspectRatioFrameLayout.RESIZE_MODE_FIT
            else AspectRatioFrameLayout.RESIZE_MODE_FILL
        (player as? MpvPlayer)?.setVideoLayout(
            keepAspect = resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FILL,
            crop = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM, zoom = resizeScale)
        val selected = resizeModes.indexOf(resizeMode)
        playerView.findViewById<ImageButton>(R.id.player_resize).apply {
            setImageResource(arrayOf(R.drawable.ic_player_fit, R.drawable.ic_player_zoom,
                R.drawable.ic_player_fill)[selected])
            contentDescription = resizeLabel()
            tooltipText = resizeLabel()
        }
    }

    private fun cycleResizeMode() {
        val choice = resizeChoices[(resizeChoiceIndex() + 1) % resizeChoices.size]
        resizeMode = choice.mode; resizeScale = choice.scale
        if (mpvProbePending) finishMpvProbe?.invoke()
        val exo = player as? ExoPlayer
        if (exo != null) {
            saveResizeMode()
            fallbackFromExo(exo)
            showResizeNotice()
            return
        }
        applyResizeMode()
        saveResizeMode()
        showResizeNotice()
    }

    private fun loadExternalSubtitle(track: SubtitleTrack) {
        pendingEmbeddedSubtitle = null; embeddedServerSubtitleId = null; externalSubtitleId = track.id
        awaitingEmbeddedSubtitle = false
        subtitleJob?.cancel(); val revision = ++subtitleSelectionRevision
        subtitleTimeline = null; subtitleOverlay.subtitleText = ""
        playerView.subtitleView?.visibility = View.GONE
        subtitleOverlay.visibility = if (subtitlesEnabled) View.VISIBLE else View.GONE
        subtitleJob = scope.launch {
            try {
                val cues = subtitleLoader.load(track)
                val timeline = withContext(Dispatchers.Default) { SubtitleTimeline(cues) }
                if (revision == subtitleSelectionRevision && !exiting) {
                    subtitleTimeline = timeline; updateSubtitleText()
                    Log.i("ShyniFlixSubtitle", "downloaded id=${track.id} cues=${cues.size}")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (error: Exception) {
                if (revision == subtitleSelectionRevision && !exiting) {
                    externalSubtitleId = null; clearExternalSubtitle()
                    selectEmbeddedSubtitleFallback()
                    val failure = error as? SubtitleLoadException
                    Log.w("ShyniFlixSubtitle", "failed id=${track.id} source=${track.source} type=${error.javaClass.simpleName} status=${failure?.status} empty=${failure?.empty}")
                    toast(when {
                        failure?.status != null -> "자막 서버 오류 (HTTP ${failure.status})"
                        failure?.empty == true -> "자막 내용이 없습니다."
                        error is java.net.SocketTimeoutException -> "자막 응답 시간이 초과되었습니다."
                        error is javax.net.ssl.SSLException -> "자막 서버 보안 연결에 실패했습니다."
                        else -> "자막 연결에 실패했습니다."
                    })
                }
            }
        }
    }

    private fun clearExternalSubtitle() {
        pendingEmbeddedSubtitle = null; embeddedServerSubtitleId = null
        awaitingEmbeddedSubtitle = false
        subtitleJob?.cancel(); subtitleSelectionRevision++; subtitleTimeline = null
        subtitleOverlay.subtitleText = ""; subtitleOverlay.visibility = View.GONE
    }

    private fun selectServerSubtitle(track: SubtitleTrack) {
        val active = player ?: return
        subtitlesEnabled = true; clearExternalSubtitle()
        playerView.subtitleView?.setCues(emptyList())
        if (track.isEmbedded) {
            externalSubtitleId = null; embeddedServerSubtitleId = track.id; pendingEmbeddedSubtitle = track
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            resolvePendingEmbeddedSubtitle()
        } else {
            externalSubtitleId = track.id
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            loadExternalSubtitle(track)
        }
    }

    private fun resolvePendingEmbeddedSubtitle() {
        val track = pendingEmbeddedSubtitle ?: return
        val active = player ?: return
        if (active.currentTracks.groups.isEmpty()) return
        val pairs = active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).map { group to it } }
        val options = pairs.map { (group, index) ->
            val format = group.getTrackFormat(index)
            EmbeddedSubtitleOption(group.isTrackSupported(index), format.language, format.label,
                originalMimeType = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType,
                isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                isSdh = format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0)
        }
        val nativeId = track.streamIndex?.let(active::subtitleTrackIdForStreamIndex)
        val matched = nativeId?.let { id -> pairs.indexOfFirst { (group, index) ->
            group.getTrackFormat(index).id == id
        }.takeIf { it >= 0 } } ?: if (active is ExoPlayer && exoEmbeddedSubtitle(track) && pairs.size == 1 &&
            options.single().supported && options.single().originalMimeType == MimeTypes.APPLICATION_TX3G) 0
        else embeddedSubtitleIndex(track, options)
        if (matched != null) {
            pendingEmbeddedSubtitle = null
            val (group, index) = pairs[matched]
            val format = group.getTrackFormat(index)
            Log.i("ShyniFlixSubtitle", "native id=${track.id} language=${format.language} mime=${format.sampleMimeType}")
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
            renderEmbeddedCues(active.currentCues)
        } else if (active.playbackState == Player.STATE_READY) {
            pendingEmbeddedSubtitle = null
            Log.i("ShyniFlixSubtitle", "conversion id=${track.id} language=${track.language} native=${options.size} supported=${options.count { it.supported }}")
            loadExternalSubtitle(track)
        }
    }

    private fun renderEmbeddedCues(cues: androidx.media3.common.text.CueGroup) {
        if (::gestures.isInitialized && gestures.isSubtitleGestureActive) return
        val active = player ?: return
        val route = mpvSubtitleController.update(active, subtitlesEnabled, externalSubtitleId != null,
            cues.cues.any { it.text != null })
        // Bitmap subtitles are drawn by MPV on the video surface. Plain text
        // belongs to the exact Shyni Player horizontal/vertical Canvas renderer.
        playerView.subtitleView?.visibility = View.GONE
        subtitleOverlay.visibility = if (route.showOverlay) View.VISIBLE else View.GONE
        if (!route.showOverlay) subtitleOverlay.subtitleText = ""
        else if (externalSubtitleId == null) subtitleOverlay.subtitleText =
            cues.cues.mapNotNull { it.text?.toString() }.joinToString("\n\n")
    }

    private fun selectEmbeddedSubtitleFallback() {
        val active = player ?: return
        awaitingEmbeddedSubtitle = active.currentTracks.groups.isEmpty()
        if (awaitingEmbeddedSubtitle) {
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            return
        }
        val tracks = active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
        val selected = tracks.firstOrNull { (group, index) ->
            group.getTrackFormat(index).language?.lowercase(Locale.ROOT)?.substringBefore('-') in setOf("ko", "kor")
        } ?: tracks.firstOrNull { (group, index) ->
            group.getTrackFormat(index).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        } ?: tracks.firstOrNull()
        subtitlesEnabled = selected != null
        val parameters = active.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selected == null)
        selected?.let { (group, index) -> parameters.addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))) }
        active.trackSelectionParameters = parameters.build()
        playerView.subtitleView?.visibility = if (selected != null) View.VISIBLE else View.GONE
        renderEmbeddedCues(active.currentCues)
    }

    private fun updateSubtitleText() {
        if (::gestures.isInitialized && gestures.isSubtitleGestureActive) return
        if (subtitlesEnabled && externalSubtitleId != null) {
            val position = gestures.pendingSeekPositionMs ?: player?.currentPosition ?: lastPosition
            subtitleOverlay.subtitleText = subtitleTimeline?.textAt((position + subtitleOffsetMs).coerceAtLeast(0)).orEmpty()
        }
    }

    private fun applySubtitleStyle() {
        subtitleAppearance = subtitleAppearance.copy(
            verticalSpacingPercent = normalizedVerticalSubtitleSpacingPercent(subtitleAppearance.verticalSpacingPercent))
        subtitleFont = PlexSubtitleFonts.normalizedKey(this, subtitleFont)
        val typeface = PlexSubtitleFonts.typeface(this, subtitleFont)
        subtitleOverlay.appearance = subtitleAppearance; subtitleOverlay.subtitleTypeface = typeface
        playerView.subtitleView?.let { applyPlexSubtitleStyle(it, subtitleAppearance, typeface) }
        player?.let { renderEmbeddedCues(it.currentCues) }
        getSharedPreferences("gsy_settings", MODE_PRIVATE).edit().putString("subtitle_font", subtitleFont)
            .putInt("subtitle_size", subtitleAppearance.sizePercent).putInt("subtitle_x", subtitleAppearance.horizontalOffsetPercent)
            .putInt("subtitle_y", subtitleAppearance.verticalOffsetPercent).putBoolean("subtitle_vertical", subtitleAppearance.verticalWriting)
            .putInt("subtitle_vertical_spacing", subtitleAppearance.verticalSpacingPercent).apply()
    }

    private fun showSubtitleAppearance() {
        AlertDialog.Builder(this).setTitle("자막 모양")
            .setItems(arrayOf("글꼴", "크기 (${subtitleAppearance.sizePercent}%)", "위치", "세로 쓰기 ${if (subtitleAppearance.verticalWriting) "켜짐" else "꺼짐"}", "색상·테두리",
                "세로 자간 (${subtitleAppearance.verticalSpacingPercent}%)")) { _, index ->
                when (index) {
                    0 -> showSubtitleFont()
                    1 -> {
                        val sizes = listOf(60, 80, 100, 120, 140, 160, 180, 200)
                        AlertDialog.Builder(this).setTitle("자막 크기").setSingleChoiceItems(sizes.map { "$it%" }.toTypedArray(), sizes.indexOf(subtitleAppearance.sizePercent)) { dialog, selected ->
                            subtitleAppearance = subtitleAppearance.copy(sizePercent = sizes[selected]); applySubtitleStyle(); dialog.dismiss()
                        }.setNegativeButton("취소", null).show()
                    }
                    2 -> showSubtitlePosition()
                    3 -> { subtitleAppearance = subtitleAppearance.copy(verticalWriting = !subtitleAppearance.verticalWriting); applySubtitleStyle() }
                    4 -> AlertDialog.Builder(this).setTitle("자막 색상·테두리").setItems(arrayOf("흰색 · 검은 테두리", "노란색 · 검은 테두리", "흰색 · 반투명 배경")) { _, selected ->
                        subtitleAppearance = subtitleAppearance.copy(foregroundColor = if (selected == 1) Color.YELLOW else Color.WHITE,
                            backgroundColor = if (selected == 2) 0x80000000.toInt() else Color.TRANSPARENT)
                        applySubtitleStyle()
                    }.setNegativeButton("닫기", null).show()
                    5 -> showSubtitleVerticalSpacing()
                }
            }.setNegativeButton("닫기", null).show()
    }

    private fun showSubtitleVerticalSpacing() {
        val current = normalizedVerticalSubtitleSpacingPercent(subtitleAppearance.verticalSpacingPercent)
        val values = ((50..200 step 10).toList() + current).distinct().sorted()
        val labels = values.map { if (it == 100) "100% (기본)" else "$it%" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("세로 자막 자간")
            .setSingleChoiceItems(labels, values.indexOf(current)) { dialog, selected ->
                subtitleAppearance = subtitleAppearance.copy(verticalSpacingPercent = values[selected])
                applySubtitleStyle()
                dialog.dismiss()
            }.setNegativeButton("취소", null).show()
    }

    private fun showSubtitleFont() {
        val fonts = PlexSubtitleFonts.options(this)
        val labels = fonts.map { it.label } + "폰트 파일 추가 (TTF / OTF)"
        AlertDialog.Builder(this).setTitle("자막 글꼴")
            .setSingleChoiceItems(labels.toTypedArray(), fonts.indexOfFirst { it.key == subtitleFont }) { dialog, index ->
                dialog.dismiss()
                if (index == fonts.size) fontPicker.launch(arrayOf("*/*"))
                else { subtitleFont = fonts[index].key; applySubtitleStyle() }
            }.setNegativeButton("취소", null).show()
    }

    private fun startSubtitleDrag(x: Float, y: Float): Boolean {
        if (!subtitlesEnabled || subtitleOverlay.visibility != View.VISIBLE || subtitleOverlay.subtitleText.isBlank()) return false
        val bounds = subtitleOverlay.subtitleBounds()
        if (bounds.isEmpty) return false
        val touchBounds = android.graphics.RectF(bounds).apply { inset(-dp(24).toFloat(), -dp(24).toFloat()) }
        if (!touchBounds.contains(x, y)) return false
        subtitleDragOrigin = subtitleAppearance
        subtitleDragBounds = SubtitleDragBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        subtitleDragOffset = null
        return true
    }

    private fun moveSubtitleDrag(dx: Float, dy: Float) {
        val origin = subtitleDragOrigin ?: return
        val bounds = subtitleDragBounds ?: return
        val offset = SubtitleDragMath.move(origin.horizontalOffsetPercent, origin.verticalOffsetPercent,
            dx, dy, subtitleOverlay.width, subtitleOverlay.height, bounds, dp(8).toFloat())
        subtitleDragOffset = offset
        // Translate the existing text layer while dragging; no font parsing,
        // text reflow or preference writes are needed for each pointer event.
        subtitleOverlay.translationX = offset.translationX
        subtitleOverlay.translationY = offset.translationY
    }

    private fun finishSubtitleDrag(commit: Boolean) {
        val origin = subtitleDragOrigin ?: return
        val offset = subtitleDragOffset
        subtitleOverlay.translationX = 0f; subtitleOverlay.translationY = 0f
        subtitleAppearance = if (commit && offset != null) origin.copy(
            horizontalOffsetPercent = offset.x, verticalOffsetPercent = offset.y) else origin
        subtitleDragOrigin = null; subtitleDragBounds = null; subtitleDragOffset = null
        applySubtitleStyle()
        updateSubtitleText()
    }

    private fun showSubtitlePosition() {
        if (television) {
            if (exiting || isFinishing || isDestroyed) return
            if (tvSubtitlePositionDialog?.dialog?.isShowing == true) return
            lateinit var positionDialog: TvSubtitlePositionDialog
            positionDialog = TvSubtitlePositionDialog(this,
                subtitleAppearance.horizontalOffsetPercent, subtitleAppearance.verticalOffsetPercent,
                onPreview = ::previewTvSubtitlePosition,
                onCommit = { x, y ->
                    previewTvSubtitlePosition(x, y)
                    applySubtitleStyle()
                },
                onDismiss = { if (tvSubtitlePositionDialog === positionDialog) tvSubtitlePositionDialog = null })
            tvSubtitlePositionDialog = positionDialog
            positionDialog.show()
            return
        }
        AlertDialog.Builder(this).setTitle("자막 위치 · 길게 눌러 드래그")
            .setItems(arrayOf("위로", "아래로", "왼쪽으로", "오른쪽으로", "기본 위치")) { _, index ->
                subtitleAppearance = when (index) {
                    0 -> subtitleAppearance.copy(verticalOffsetPercent = (subtitleAppearance.verticalOffsetPercent - 3).coerceAtLeast(-80))
                    1 -> subtitleAppearance.copy(verticalOffsetPercent = (subtitleAppearance.verticalOffsetPercent + 3).coerceAtMost(80))
                    2 -> subtitleAppearance.copy(horizontalOffsetPercent = (subtitleAppearance.horizontalOffsetPercent - 3).coerceAtLeast(-80))
                    3 -> subtitleAppearance.copy(horizontalOffsetPercent = (subtitleAppearance.horizontalOffsetPercent + 3).coerceAtMost(80))
                    else -> subtitleAppearance.copy(horizontalOffsetPercent = 0, verticalOffsetPercent = 0)
                }; applySubtitleStyle(); showSubtitlePosition()
            }.setNegativeButton("닫기", null).show()
    }

    /** Held remote keys update only placement; fonts and preferences are applied once per gesture. */
    private fun previewTvSubtitlePosition(x: Int, y: Int) {
        val next = subtitleAppearance.copy(horizontalOffsetPercent = x.coerceIn(-80, 80),
            verticalOffsetPercent = y.coerceIn(-80, 80))
        if (next == subtitleAppearance) return
        subtitleAppearance = next
        if (::subtitleOverlay.isInitialized) subtitleOverlay.appearance = next
        if (::playerView.isInitialized) playerView.subtitleView?.let { view ->
            view.translationX = view.width * next.horizontalOffsetPercent / 100f
            view.translationY = view.height * next.verticalOffsetPercent / 100f
            view.invalidate()
        }
    }

    private fun dismissTvSubtitlePosition() {
        val dialog = tvSubtitlePositionDialog
        tvSubtitlePositionDialog = null
        dialog?.dismiss()
    }

    private fun showSubtitleOffset() {
        if (externalSubtitleId == null) { toast("서버 자막을 선택한 뒤 싱크를 조절할 수 있습니다."); return }
        AlertDialog.Builder(this).setTitle("자막 싱크: ${String.format(Locale.KOREAN, "%+.1f", subtitleOffsetMs / 1000.0)}초")
            .setItems(arrayOf("0.5초 앞당기기", "0.5초 늦추기", "기본값")) { _, index ->
                subtitleOffsetMs = when (index) { 0 -> subtitleOffsetMs + 500; 1 -> subtitleOffsetMs - 500; else -> 0 }
                updateSubtitleText(); showSubtitleOffset()
            }.setNegativeButton("닫기", null).show()
    }

    private fun resynchronize() {
        val active = player ?: return
        val position = active.currentPosition.coerceAtLeast(0)
        val wasPlaying = active.playWhenReady
        active.pause()
        active.stop()
        active.prepare()
        active.seekTo(position)
        active.playWhenReady = wasPlaying
        toast("현재 위치에서 음성과 영상을 다시 맞춥니다.")
    }

    /** A decoder estimate or a renewed fragment cannot shorten this source's full timeline. */
    private fun preserveFullDuration(observedDurationMs: Long) {
        lastDuration = maxOf(0L, lastDuration, spec.durationMs, observedDurationMs)
    }

    private fun capturePosition() {
        val active = player ?: return
        if (completed) return
        pendingSeek?.let { lastPosition = it; return }
        if (active.duration != C.TIME_UNSET && active.duration > 0) preserveFullDuration(active.duration)
        if (!gestures.isSeekGestureActive && prepared) {
            lastPosition = active.currentPosition.coerceAtLeast(0)
            completionGate().observe(lastPosition, lastDuration, watchingSince != null || (visible && active.isPlaying))
        }
    }

    private fun settleWatchTime(running: Boolean) {
        val now = SystemClock.elapsedRealtime()
        watchingSince?.let { watchedMs += (now - it).coerceAtLeast(0) }
        watchingSince = if (running) now else null
    }

    private fun enqueueProgress(state: String) {
        if (endingAfterCompletion || !prepared || !::spec.isInitialized || (completed && state != "completed")) return
        capturePosition(); settleWatchTime(visible && !exiting && player?.isPlaying == true)
        submitProgress(ProgressUpdate(spec, lastPosition, lastDuration, state, watchedMs))
    }

    private fun watchedKey(value: PlaybackSpec) = "${value.libraryId}:${value.itemId}:${value.viewItemId}"

    private fun completionGate(): PlaybackCompletionGate = completionGates.getOrPut(watchedKey(spec)) {
        PlaybackCompletionGate(lastPosition, watchedKey(spec) in watchedReached)
    }

    private fun submitProgress(update: ProgressUpdate): Boolean {
        val epoch = playbackAuthentication.captureProgress() ?: run {
            update.acknowledgement?.complete(false)
            return false
        }
        val key = watchedKey(update.spec)
        val reached = completionGates[key]?.eligible == true || key in watchedReached
        if (reached) watchedReached.add(key)
        val firstThreshold = reached && key !in watchedConfirmed && watchedQueued.add(key)
        val accepted = updates.submit(update.copy(thresholdAttempt = firstThreshold, completionEligible = reached,
            authenticationEpoch = epoch, accountScope = PlaybackCompletionSignals.scope(client.server, client.token),
            privateGrantRevision = if (update.spec.sourceItem?.isSpoiler == true) SpoilerAccessRegistry.changes.value else null,
            choiceRevision = PlaybackCompletionSignals.choiceRevision()),
            important = firstThreshold || update.acknowledgement != null || update.state in setOf("stopped", "completed"),
            supersedes = if (firstThreshold || update.state == "completed") { older ->
                update.spec.sessionId != null && older.authenticationEpoch == epoch && older.spec.sessionId == update.spec.sessionId &&
                    watchedKey(older.spec) == key
            } else null)
        if (!accepted && firstThreshold) watchedQueued.remove(key)
        // Presence is optional and must never block progress recording or playback.
        if (accepted && ::spec.isInitialized && samePlaybackPresenceSource(update.spec, spec) &&
            (!exiting || update.state in setOf("stopped", "completed"))) {
            presenceLifecycle.update(update.spec, update.position, update.duration, update.state)
        }
        return accepted
    }

    private suspend fun ensureProgressAuthentication(update: ProgressUpdate) {
        currentCoroutineContext().ensureActive()
        if (!playbackAuthentication.isCurrent(update.authenticationEpoch))
            throw kotlinx.coroutines.CancellationException("Playback authentication changed")
    }

    private fun finishCompletedPlayback() {
        if (exiting || endingAfterCompletion || isDestroyed) return
        capturePosition()
        completionGate().finish()
        endingAfterCompletion = true; exiting = true; completed = true; resumeWhenVisible = false
        cancelSpoilerPlaybackReauthentication()
        settleWatchTime(false)
        if (lastDuration > 0) lastPosition = lastDuration
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(conversionTimeout)
        cancelPlaybackMetadata()
        neighborsJob?.cancel(); conversionJob?.cancel()
        playerView.hideController(); hideLockControl()
        hideSegmentActions()
        updatePlaybackButtons()
        // The independent writer drains this critical snapshot after Activity exit.
        // A finished frame must never wait on the server before returning to the caller.
        submitProgress(ProgressUpdate(spec, lastPosition, lastDuration, "completed", watchedMs))
        returnToCaller(spec.sourceItem?.takeIf { it.type == "episode" && it.id == spec.itemId &&
            it.libraryId == spec.libraryId && it.viewItemId == spec.viewItemId }?.toJson())
    }

    private fun returnToCaller(completedEpisode: String? = null) {
        setResult(RESULT_OK, Intent().apply { if (completedEpisode != null) putExtra("completedEpisode", completedEpisode) })
        finish()
    }

    private fun finishAfterProgress(acknowledgement: CompletableDeferred<Boolean>) {
        scope.launch {
            // Manual Back gives partial progress a bounded chance to commit first.
            withTimeoutOrNull(4_000) { acknowledgement.await() }
            if (!isDestroyed) {
                finish()
            }
        }
    }

    private fun releasePlayer() {
        engineGeneration++
        engineSelectionJob?.cancel(); engineSelectionJob = null
        handler.removeCallbacks(engineSelectionTimeout)
        handler.removeCallbacks(engineProbeTimeout)
        mpvProbeJob?.cancel(); mpvProbeJob = null
        finishMpvProbe = null; mpvProbePending = false
        exoValidated = false
        exoRestoreSubtitlePending = false
        dismissTvSubtitlePosition()
        cancelPlaybackMetadata()
        subtitleListJob?.cancel(); subtitleListJob = null
        subtitleListFailed = false
        spoilerReauthenticationJob?.cancel(); spoilerReauthenticationJob = null
        subtitleDialog?.dismiss(); subtitleDialog = null
        playerGestureActive = false
        hideSegmentActions()
        if (::subtitleOverlay.isInitialized) subtitleOverlay.subtitleText = ""
        if (::gestures.isInitialized) {
            gestures.cancelSeekGesture()?.let { lastPosition = it }
            gestures.attachPlayer(null)
        }
        if (::playerView.isInitialized) playerView.player = null
        val previous = player
        player = null
        previous?.release()
        hideSegmentActions()
    }

    private fun leavePlayer() {
        if (exiting) return
        gestures.cancelSeekGesture()?.let { lastPosition = it; pendingSeek = it }
        exiting = true; resumeWhenVisible = false
        hideSegmentActions()
        cancelPlaybackMetadata()
        cancelSpoilerPlaybackReauthentication()
        handler.removeCallbacks(ticker)
        capturePosition(); settleWatchTime(false)
        val acknowledgement = CompletableDeferred<Boolean>()
        val sent = prepared && ::spec.isInitialized && submitProgress(ProgressUpdate(
            spec, lastPosition, lastDuration, if (completed) "completed" else "stopped", watchedMs, acknowledgement))
        if (!sent) acknowledgement.complete(false)
        player?.pause()
        finishAfterProgress(acknowledgement)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (::spec.isInitialized && ::client.isInitialized) ensureSpoilerPlaybackReauthentication()
    }

    override fun onStop() {
        dismissTvSubtitlePosition()
        val interruptedSpoilerRotation = spoilerRecovery != null || spoilerRotationJob?.isActive == true
        activityStarted = false
        cancelSpoilerPlaybackReauthentication()
        if (interruptedSpoilerRotation && ::spec.isInitialized && ::client.isInitialized &&
            spec.sourceItem?.isSpoiler == true) {
            client.clearSpoilerGrant()
            closeLockedSpoilerPlayback()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume(); visible = true; hideSystemBars()
        if (closeLockedSpoilerPlayback()) return
        if (endingAfterCompletion || exiting) return
        if (!refreshing && playbackEngineSettled(mpvProbePending, player is ExoPlayer && !exoValidated) &&
            player?.reconcilePlaybackCompletion() == true) {
            finishCompletedPlayback()
            return
        }
        player?.playWhenReady = resumeWhenVisible && !refreshing && !mpvProbePending &&
            (player !is ExoPlayer || exoValidated)
        if (::playerView.isInitialized) { handler.removeCallbacks(ticker); handler.post(ticker) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!television) return
        if (!hasFocus) tvSegmentFocusGate.clearHeldKeys()
        else handler.post { if (visible && !exiting) updateSegmentSkipButton() }
    }

    override fun onPause() {
        dismissTvSubtitlePosition()
        tvSegmentFocusGate.clearHeldKeys()
        finishRemoteSeeking()
        visible = false
        playerGestureActive = false
        unlockKeyPending = false
        handler.removeCallbacks(ticker)
        if (::playerView.isInitialized) playerView.hideController()
        hideLockControl()
        hideSegmentActions()
        if (::gestures.isInitialized) {
            resumeWhenVisible = if (endingAfterCompletion || exiting) false else
                conversionResumeWhenVisible ?: gestures.playWhenReadyBeforeSeek ?:
                    (if (mpvProbePending || (player is ExoPlayer && !exoValidated)) resumeWhenVisible
                        else player?.playWhenReady ?: resumeWhenVisible)
            if (mpvProbePending) finishMpvProbe?.invoke()
            gestures.cancelSeekGesture()?.let { lastPosition = it; pendingSeek = it }
            pendingSeek?.let { target -> player?.seekTo(target) }
            capturePosition(); settleWatchTime(false); enqueueProgress(if (completed) "completed" else if (exiting) "stopped" else "paused")
            player?.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("endingAfterCompletion", endingAfterCompletion)
        if (::spec.isInitialized) {
            capturePosition(); outState.putString("spec", spec.toJson()); outState.putLong("position", lastPosition)
            outState.putLong("duration", lastDuration); outState.putLong("watched", watchedMs); outState.putFloat("speed", speed)
            outState.putStringArrayList("watchedReached", ArrayList(watchedReached + completionGates.filterValues { it.eligible }.keys))
            val desiredPlayback = if (mpvProbePending || (player is ExoPlayer && !exoValidated)) resumeWhenVisible
                else player?.playWhenReady ?: resumeWhenVisible
            outState.putBoolean("playing", conversionResumeWhenVisible ?: if (visible)
                gestures.playWhenReadyBeforeSeek ?: desiredPlayback else resumeWhenVisible)
            outState.putBoolean("subtitles", subtitlesEnabled); outState.putInt("externalSubtitle", externalSubtitleId ?: -1)
            outState.putBoolean("subtitleManuallySelected", subtitleManuallySelected)
            outState.putInt("embeddedSubtitle", embeddedServerSubtitleId ?: -1)
            outState.putLong("subtitleOffset", subtitleOffsetMs)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        resizeNotice?.cancel()
        handler.removeCallbacksAndMessages(null); scope.cancel()
        if (::spec.isInitialized) { settleWatchTime(false); if (!isChangingConfigurations) enqueueProgress(if (completed) "completed" else "stopped") }
        presenceLifecycle.close(endSession = !isChangingConfigurations)
        releasePlayer(); updates.close(); super.onDestroy()
    }

    private fun showError(message: String) {
        hideSegmentActions()
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        if (television) {
            playerView.hideController()
            finishRemoteSeeking()
            errorPanel.getChildAt(1)?.requestFocus()
        }
    }
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
