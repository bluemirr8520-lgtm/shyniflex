package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.sourceNeedsNativeEngine

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
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
import io.mirr.shynigsy.player.mpv.exoNativeSubtitleKind
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import io.mirr.shynigsy.R
import io.mirr.shynigsy.BuildConfig
import io.mirr.shynigsy.deviceDisplayName
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.PlaybackCodecInfo
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackMarker
import io.mirr.shynigsy.data.SpoilerAccessRegistry
import io.mirr.shynigsy.data.ApiException
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.PlayerServer
import io.mirr.shynigsy.data.PlexPlayerServer
import io.mirr.shynigsy.data.ShyniPlayerServer
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.data.hasKnownMediaOrigin
import io.mirr.shynigsy.ui.CinemaAppearancePreferences
import io.mirr.shynigsy.ui.cinemaColorScheme
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
    private var diagnosticDialog: AlertDialog? = null
    private var lastDiagnosticAt = 0L
    private var diagnosticStartedAt = 0L
    private lateinit var spec: PlaybackSpec
    private lateinit var client: PlayerServer
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
    private val localSubtitleStore by lazy { LocalSubtitleStore(applicationContext) }
    private var localSubtitleOwnerKey: String? = null
    private var localSubtitleRecord: LocalSubtitleRecord? = null
    private var localSubtitleActiveId: String? = null
    private var localSubtitleTimeline: SubtitleTimeline? = null
    private var localSubtitleReadJob: Job? = null
    private var localSubtitleLaunchOwner: String? = null
    private var localSubtitleLaunchRevision = 0
    private var localSubtitleChoiceRevision = 0
    private var restoredLocalSubtitleId: String? = null
    private var subtitlePolicyContentKey: String? = null
    private var subtitleOriginJob: Job? = null
    private var subtitleDefaultAudioLanguage: String? = null
    private var subtitleAudioDefaultOff = false
    private var subtitleAutomaticRestoreRevision: Int? = null
    private var subtitleOriginAttemptedKey: String? = null
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
    private val pictureStore by lazy { PicturePresetStore(getSharedPreferences("gsy_settings", MODE_PRIVATE)) }
    private var pictureSettings = PictureSettings()
    private var pictureDialog: PictureSettingsDialog? = null
    private var exoPictureEffect: ExoPictureEffect? = null
    private var exoPictureOwner: ExoPlayer? = null
    private var exoPictureRestartPending: ExoPlayer? = null
    private var exoPictureRedraw: Runnable? = null
    private var exoPictureFrameTimeout: Runnable? = null
    private var exoPictureBypassContent: String? = null
    private var player: Player? = null
    // Kept outside PlaybackSpec: it is neither a server marker nor reusable across files.
    private var chapterCreditsOwner: Player? = null
    private var chapterCreditsEvidence: OriginalChapterCredits? = null
    private val chapterCreditsCaptureGate = OriginalChapterCaptureGate()
    private var chapterCreditsRetry: Runnable? = null
    private val mpvSubtitleController = MpvSubtitleController { native -> (player as? MpvPlayer)?.nativeSubtitleVisibility = native }
    private var engineSelectionJob: Job? = null
    private var engineGeneration = 0L
    private var manualExoSelectionPending = false
    private var manualExoProbePending = false
    private var manualExoInUse = false
    private var manualExoSourceHasAudio = false
    private var headerExoInUse = false
    private var mpvHeaderHandoffPending = false
    private var enginePreferenceContentKey: String? = null
    private var enginePreferenceForContent = PlaybackEnginePreference.AUTO
    private var exoValidated = false
    private var pendingExoTextSeekTarget: Long? = null
    private var pendingExoTextSeekIntent: Boolean? = null
    private var committedExoTextSeekIntent: Boolean? = null
    private var forceMpvSession: String? = null
    private var mpvProbePending = false
    private var mpvProbeJob: Job? = null
    private var finishMpvProbe: (() -> Unit)? = null
    private var exoRestoreSubtitlePending = false
    private data class EngineCandidate(val info: PlaybackCodecInfo, val subtitles: List<SubtitleTrack>)
    private var speed = 1f
    private val studyRepeat = LanguageStudyRepeat()
    private var studyContentKey: String? = null
    private var studyAutoOpenedContent: String? = null
    private var studySentenceSourceKey: String? = null
    private var studySentenceOffsetMs = 0L
    private var studyDialog: LanguageStudyDialog? = null
    private lateinit var studySecondaryOverlay: PlexSubtitleOverlayView
    private var studySecondaryOffsetMs = 0L
    private var studySecondaryAtTop = true
    private val studySecondary by lazy {
        LanguageStudySecondary(this, scope, ::updateStudySecondaryText, ::toast) {
            val owner = localSubtitleOwnerKey
            owner != null && ::client.isInitialized && localSubtitleCurrent(owner, client.token)
        }
    }
    private var conversionLoading = false
    private var conversionJob: Job? = null
    private var conversionResumeWhenVisible: Boolean? = null
    private var conversionRestore: ConversionRestore? = null
    private val continuousPlaybackGate = ContinuousPlaybackGate()
    // Kept until the replacement renders, so a failure returns to the completed series.
    private var continuousPrevious: ConversionRestore? = null
    private var continuousLoadingOverlay: View? = null
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
    private val localCompletionReceipts = ConcurrentHashMap.newKeySet<Long>()
    private val watchedAttemptAt = mutableMapOf<String, Long>()
    private val progressChoiceRevisions = mutableMapOf<String, Long>()
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
            client.shyni?.createPlaybackPresence(source, label, if (television) "tv" else "mobile", onForceStop)?.let { connection ->
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
        val manual = manualExoSelectionPending
        manualExoSelectionPending = false
        if (player == null && !exiting && !isDestroyed && ::spec.isInitialized && ::client.isInitialized &&
            !closeLockedSpoilerPlayback()) {
            if (!manual && spec.sourceItem?.isSpoiler == false)
                Log.i("ShyniflexPlayback", "stage=auto_candidate available=false reason=timeout")
            if (manual) toast("ExoPlayer의 자막을 확인하지 못해 MPV로 재생합니다.")
            startPreparedPlayer(null, false)
        }
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
    private val localSubtitlePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        receiveLocalSubtitleResult(result.resultCode, result.data)
    }
    private val studySubtitlePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val owner = result.data?.getStringExtra(SubtitleImportActivity.EXTRA_OWNER_KEY)
            val id = result.data?.getStringExtra(SubtitleImportActivity.EXTRA_SUBTITLE_ID)
            if (owner != null && id != null) studySecondary.acceptImported(owner, id)
        }
    }
    private val onlineSubtitlePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        receiveLocalSubtitleResult(result.resultCode, result.data)
    }

    private data class ProgressUpdate(val spec: PlaybackSpec, val position: Long, val duration: Long,
        val state: String, val watched: Long, val acknowledgement: CompletableDeferred<Boolean>? = null,
        val thresholdAttempt: Boolean = false, val completionEligible: Boolean = false,
        val authenticationEpoch: Long = 0, val accountScope: String? = null,
        val privateGrantRevision: Long? = null, val choiceRevision: Long = 0,
        val localCompletionSequence: Long? = null)

    private data class ConversionRestore(val spec: PlaybackSpec, val position: Long, val playing: Boolean,
        val subtitle: SubtitleTrack?, val audio: PlaybackTrackPreference?, val text: PlaybackTrackPreference?,
        val audioIndex: Int?, val duration: Long, val subtitleEnabled: Boolean, val subtitleManual: Boolean,
        val localSubtitle: String?)

    private val ticker = object : Runnable {
        override fun run() {
            if (exiting || !visible) return
            if (closeLockedSpoilerPlayback()) return
            ensureSpoilerPlaybackReauthentication()
            val active = player
            if (active != null) samplePlaybackDiagnostics(active)
            if (active != null && maybeRepeatStudy(active)) {
                updateStudySecondaryText()
                handler.postDelayed(this, 50)
                return
            }
            if (active != null && playbackEngineSettled(mpvProbePending, active is ExoPlayer && !exoValidated) &&
                !refreshing && !gestures.isSeekGestureActive && !isExoEmbeddedSeekPending(active) && active.reconcilePlaybackCompletion()) {
                finishCompletedPlayback()
                return
            }
            // Use one clock sample for both visibility and its next deadline.
            // Resampling after crossing the boundary could hide the action until the next 500 ms tick.
            val segmentPosition = active?.currentPosition ?: lastPosition
            if (active != null && prepared) {
                capturePosition()
                updateSubtitleText()
                updateStudySecondaryText()
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
            val needsSubtitleUpdates = ((externalSubtitleId != null && subtitleTimeline != null) ||
                (localSubtitleActiveId != null && localSubtitleTimeline != null)) &&
                (active?.isPlaying == true || gestures.isSeekGestureActive)
            val markerDelay = if (active != null && prepared && !refreshing && !navigationLoading && !conversionLoading &&
                !playerGestureActive && !gestures.isSeekGestureActive && !gestures.locked)
                playbackMarkerRefreshDelayMs(effectiveSegmentMarkers(), segmentPosition, lastDuration,
                    active.playbackParameters.speed, active.isPlaying) else 500L
            handler.postDelayed(this, minOf(if (needsSubtitleUpdates || studyRepeat.range != null || studySecondary.timeline != null) 50L else 500L, markerDelay))
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
        pictureSettings = pictureStore.readCurrent()
        val deviceId = settings.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            settings.edit().putString("device_id", it).apply()
        }
        client = if (BuildConfig.IS_PLEX) PlexPlayerServer(server, session.token, deviceId)
            else ShyniPlayerServer(server, session.token, deviceId)
        if (spec.sourceItem?.isSpoiler == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        subtitleLoader = SubtitleLoader(headersFor = client::headersFor)
        lastPosition = savedInstanceState?.getLong("position", spec.resumeMs) ?: spec.resumeMs
        lastDuration = maxOf(0L, spec.durationMs, savedInstanceState?.getLong("duration", spec.durationMs) ?: 0L)
        speed = (savedInstanceState?.getFloat("speed", settings.getFloat("playback_speed", 1f)) ?: settings.getFloat("playback_speed", 1f))
            .takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f
        resizeMode = settings.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
            .takeIf { it in resizeModes } ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
        resizeScale = settings.getFloat("resize_scale", 1f).takeIf { it in setOf(1f, 1.1f, 1.25f, 1.5f) } ?: 1f
        subtitlePolicyContentKey = subtitlePlaybackContentKey(spec)
        subtitleDefaultAudioLanguage = savedInstanceState?.getString("subtitleDefaultAudioLanguage")
        subtitleAudioDefaultOff = savedInstanceState?.getBoolean("subtitleAudioDefaultOff", false) ?: false
        subtitlesEnabled = savedInstanceState?.getBoolean("subtitles", defaultSubtitlesEnabled(spec)) ?: defaultSubtitlesEnabled(spec)
        subtitleManuallySelected = savedInstanceState?.getBoolean("subtitleManuallySelected", false) ?: false
        restoredLocalSubtitleId = savedInstanceState?.getString("localSubtitle")
        externalSubtitleId = if (!subtitlesEnabled) null else if (savedInstanceState != null)
            savedInstanceState.getInt("externalSubtitle", -1).takeIf { it >= 0 } else preferredSubtitle(spec)?.id
        embeddedServerSubtitleId = savedInstanceState?.getInt("embeddedSubtitle", -1)?.takeIf { subtitlesEnabled && it >= 0 }
        subtitleOffsetMs = savedInstanceState?.getLong("subtitleOffset", 0) ?: 0
        subtitleFont = settings.getString("subtitle_font", "system") ?: "system"
        subtitleAppearance = PlexSubtitleAppearance(sizePercent = settings.getInt("subtitle_size", 100),
            horizontalOffsetPercent = settings.getInt("subtitle_x", 0), verticalOffsetPercent = settings.getInt("subtitle_y", 0),
            verticalWriting = settings.getBoolean("subtitle_vertical", false),
            verticalSpacingPercent = normalizedVerticalSubtitleSpacingPercent(settings.getInt("subtitle_vertical_spacing", 100)),
            horizontalAlignment = SubtitleHorizontalAlignment.values().firstOrNull {
                it.name == settings.getString("subtitle_horizontal_alignment", null)
            } ?: SubtitleHorizontalAlignment.DEFAULT,
            verticalAlignment = SubtitleVerticalAlignment.values().firstOrNull {
                it.name == settings.getString("subtitle_vertical_alignment", null)
            } ?: SubtitleVerticalAlignment.DEFAULT)
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
        PlaybackCompletionSignals.progressWriterStarted()
        progressScope.launch {
            try {
                while (true) {
                    val update = updates.next() ?: break
                    val key = watchedKey(update.spec)
                    if (!playbackAuthentication.isCurrent(update.authenticationEpoch) || !isProgressChoiceCurrent(update)) {
                        update.acknowledgement?.complete(false)
                        if (update.thresholdAttempt) watchedQueued.remove(key)
                        discardLocalCompletion(update)
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
                                    PlaybackCompletionSignals.withWatchMutation {
                                        ensureProgressAuthentication(update)
                                        client.saveProgress(update.spec, update.position, update.duration, update.state, update.watched,
                                            completionEligible = update.completionEligible)
                                    }
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
                                        PlaybackCompletionSignals.withWatchMutation {
                                            ensureProgressAuthentication(update)
                                            client.markPlaybackWatched(update.spec.copy(durationMs = update.duration))
                                        }
                                    }
                                    if (playbackAuthentication.isCurrent(update.authenticationEpoch) && isProgressChoiceCurrent(update)) {
                                        // The watch write can succeed even when the position write failed.
                                        // Resolve every receipt, including repeated EOF snapshots.
                                        PlaybackCompletionSignals.acknowledgeLocal(update.localCompletionSequence)
                                        if (watchedConfirmed.add(key)) {
                                            val item = update.spec.sourceItem?.takeIf { it.id == update.spec.itemId &&
                                                it.libraryId == update.spec.libraryId && it.viewItemId == update.spec.viewItemId }
                                            PlaybackCompletionSignals.confirm(update.accountScope, item,
                                                update.privateGrantRevision, update.choiceRevision)
                                            PlaybackCompletionSignals.notifyChanged()
                                        }
                                    }
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    completionFailed = true
                                    logProgressFailure(PlaybackProgressFailureStage.COMPLETION, error)
                                    if (playbackAuthentication.isCurrent(update.authenticationEpoch)) watchedConfirmed.remove(key)
                                    PlaybackCompletionSignals.failLocal(update.localCompletionSequence,
                                        showFailure = playbackAuthentication.isCurrent(update.authenticationEpoch) && isProgressChoiceCurrent(update))
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
                        if (playbackAuthentication.isCurrent(update.authenticationEpoch) && isProgressChoiceCurrent(update)) throw cancel
                        update.acknowledgement?.complete(false)
                        false
                    } finally {
                        if (progressDeliveryJob === delivery) progressDeliveryJob = null
                        if (update.thresholdAttempt) watchedQueued.remove(key)
                        discardLocalCompletion(update)
                    }
                    if (!playbackAuthentication.isCurrent(update.authenticationEpoch) || !isProgressChoiceCurrent(update)) continue
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
            } finally {
                updates.close()
                // Normal activity destruction lets this writer drain. Only a finished
                // or cancelled writer withdraws any receipts it could not deliver.
                PlaybackCompletionSignals.discardLocals(localCompletionReceipts.toList())
                localCompletionReceipts.clear()
                progressScope.cancel()
            }
        }.invokeOnCompletion { PlaybackCompletionSignals.progressWriterFinished() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (continuousPrevious != null) leavePlayer()
                else if (television && ::segmentActions.isInitialized && segmentActions.hasFocus()) {
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
        studySecondaryOverlay = PlexSubtitleOverlayView(this).apply { visibility = View.GONE }
        root.addView(studySecondaryOverlay, FrameLayout.LayoutParams(-1, -1))
        applySubtitleStyle()
        playerView.controllerShowTimeoutMs = 3500
        playerView.setControllerAnimationEnabled(false)
        playerView.setControllerAutoShow(false)
        playerView.setControllerHideOnTouch(false)
        playerView.hideController()
        playerView.findViewById<TextView>(R.id.player_title).text = spec.controllerTitle
        playerView.findViewById<ImageButton>(R.id.player_back).setOnClickListener { leavePlayer() }
        playerView.findViewById<ImageButton>(R.id.player_settings).setOnClickListener { showSettings() }
        playerView.findViewById<ImageButton>(R.id.player_resize).setOnClickListener { cycleResizeMode() }
        playerView.findViewById<Button>(R.id.player_speed).setOnClickListener { showSpeed() }
        playerView.findViewById<Button>(R.id.player_conversion).setOnClickListener { showConversion() }
        playerView.findViewById<ImageButton>(R.id.player_subtitles).setOnClickListener {
            if (it.isEnabled && it.isShown) showTracks(C.TRACK_TYPE_TEXT)
        }
        playerView.findViewById<ImageButton>(R.id.player_previous).setOnClickListener { switchNeighbor(false) }
        playerView.findViewById<ImageButton>(R.id.player_next).setOnClickListener { switchNeighbor(true) }
        val continuousSettings = getSharedPreferences("gsy_settings", MODE_PRIVATE)
        val continuousButton = playerView.findViewById<Button>(R.id.player_continuous)
        fun updateContinuousButton() {
            val enabled = continuousSettings.getBoolean("continuous_playback", false)
            continuousButton.text = if (enabled) "연속재생\n켜짐" else "연속재생\n꺼짐"
            continuousButton.contentDescription = if (enabled) "연속 재생 켜짐" else "연속 재생 꺼짐"
            continuousButton.isSelected = enabled
        }
        continuousButton.setOnClickListener {
            val enabled = !continuousSettings.getBoolean("continuous_playback", false)
            continuousSettings.edit().putBoolean("continuous_playback", enabled).apply()
            updateContinuousButton()
        }
        updateContinuousButton()
        updatePlaybackButtons()
        applyResizeMode()
        val touchControls = listOf(R.id.player_back, R.id.player_bottom_controls,
            androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
            androidx.media3.ui.R.id.exo_ffwd).map { playerView.findViewById<View>(it) }
        val controlBounds = android.graphics.Rect()
        gestures = Media3GestureView(this).apply {
            // This transparent layer fills the root, including when the controls have cutout padding.
            hitsController = { x, y ->
                touchControls.any { control ->
                    control.getDrawingRect(controlBounds)
                    root.offsetDescendantRectToMyCoords(control, controlBounds)
                    control.isShown && controlBounds.contains(x.toInt(), y.toInt())
                }
            }
            onControllerToggle = { if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController() }
            onLockedTap = ::showUnlockControl
            onSeekCommitted = { target ->
                completionGate().seek(target); pendingSeek = target; lastPosition = target; updateSubtitleText(); refreshSegmentActions()
                if (player is ExoPlayer && requiresMpvForEmbeddedTextResume(spec.subtitles, 1))
                    committedExoTextSeekIntent = gestures.playWhenReadyBeforeSeek
            }
            onSeekSettled = { target ->
                pendingSeek = null; lastPosition = target; updateSubtitleText(); refreshSegmentActions()
                enqueueProgress(if (player?.isPlaying == true) "playing" else "paused")
                (player as? ExoPlayer)?.let { scheduleEmbeddedTextMpvAfterSeek(it, target) }
                committedExoTextSeekIntent = null
            }
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
                val active = player as? ExoPlayer
                if (active != null && requiresMpvForEmbeddedTextResume(spec.subtitles, 1))
                    scheduleEmbeddedTextMpvAfterSeek(active, target, committedExoTextSeekIntent)
                else if (!spoilerPreparing) showError("이동한 위치를 불러오지 못했습니다. 이 위치에서 다시 재생해 주세요.")
                committedExoTextSeekIntent = null
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
            textSize = 14f; setTextColor(Color.WHITE); applySegmentActionHighlight(this)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { skipCurrentSegment() }
        }
        creditsNextButton = Button(this).apply {
            id = R.id.player_credits_next
            visibility = View.GONE
            text = "다음 화"; textSize = 14f
            setTextColor(Color.WHITE); applySegmentActionHighlight(this)
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
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            playerView.findViewById<View>(R.id.player_overlay_controls).setPadding(safe.left, safe.top, safe.right, safe.bottom)
            (lockButton.layoutParams as FrameLayout.LayoutParams).let { layout ->
                layout.topMargin = safe.top + dp(9)
                layout.marginEnd = dp(16) + if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) safe.left else safe.right
                lockButton.layoutParams = layout
            }
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
                R.id.player_conversion, R.id.player_subtitles, R.id.player_previous, R.id.player_next, R.id.player_continuous,
                androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
                androidx.media3.ui.R.id.exo_ffwd).forEach { id ->
                playerView.findViewById<View>(id)?.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            }
            applySegmentActionHighlight(segmentSkipButton)
            applySegmentActionHighlight(creditsNextButton)
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
        else currentSkipMarker(effectiveSegmentMarkers(), positionMs ?: active.currentPosition, lastDuration)

    private fun effectiveSegmentMarkers(): List<PlaybackMarker> =
        markersWithOriginalChapter(spec, chapterCreditsEvidence.takeIf { chapterCreditsOwner === player })

    private fun captureOriginalChapterCredits(created: Player, allowFixedHeaders: Boolean = false) {
        if (player !== created || created !is MpvPlayer || exiting || isDestroyed || closeLockedSpoilerPlayback()) return
        val source = originalChapterSource(spec) ?: return
        if (!chapterCreditsCaptureGate.begin(created, source)) return
        chapterCreditsRetry?.let(handler::removeCallbacks)
        chapterCreditsRetry = null
        val marker = runCatching { created.nativeChapterCredits(lastDuration, allowFixedHeaders) }.getOrNull()
        if (player !== created || !sameOriginalChapterSource(spec, source) || exiting || isDestroyed) return
        if (marker != null) {
            chapterCreditsCaptureGate.finish(created, source)
            chapterCreditsOwner = created
            chapterCreditsEvidence = OriginalChapterCredits(source, marker)
            refreshSegmentActions()
        } else if (chapterCreditsCaptureGate.canRetry(created, source)) {
            val retry = Runnable {
                chapterCreditsRetry = null
                if (player === created && !exiting && !isDestroyed && sameOriginalChapterSource(spec, source))
                    captureOriginalChapterCredits(created)
            }
            chapterCreditsRetry = retry
            handler.postDelayed(retry, 1_500)
        }
    }

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
            val timelineMarker = currentSkipMarker(effectiveSegmentMarkers(), positionMs ?: player?.currentPosition ?: lastPosition, lastDuration)
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
        if (continuousLoadingOverlay?.isShown == true) return super.dispatchKeyEvent(event)
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
        if (continuousLoadingOverlay?.isShown == true) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) abandonEnginePreflightForInteraction()
        return super.dispatchTouchEvent(event)
    }

    private fun manualEnginePreparing(): Boolean = manualExoSelectionPending || manualExoProbePending ||
        (manualExoInUse && !exoValidated)

    /** Apply a remote/touch action to the existing MPV immediately, without a later surprise switch. */
    private fun abandonEnginePreflightForInteraction() {
        val action = engineInteraction(player != null, engineSelectionJob?.isActive == true, mpvProbePending,
            player is ExoPlayer && !exoValidated)
        if (manualEnginePreparing() && action != EngineInteraction.NONE) {
            // Do not block close, pause or seek while waiting on the network.
            // A later Exo handoff would override that interaction, so keep MPV.
            manualExoSelectionPending = false
            manualExoProbePending = false
            manualExoInUse = false
            when (action) {
                EngineInteraction.START_MPV -> engineSelectionTimeout.run()
                EngineInteraction.ACCEPT_MPV -> finishMpvProbe?.invoke()
                EngineInteraction.FALLBACK_FROM_EXO -> player?.let(::fallbackFromExo)
                EngineInteraction.NONE -> Unit
            }
            toast("조작을 위해 MPV로 재생합니다. 다시 열면 ExoPlayer를 재시도합니다.")
            return
        }
        when (action) {
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
        ensureSubtitleDefaults()
        ensureLocalSubtitleSource()
        ensureStudySource()
        prepared = false
        releasePlayer()
        diagnosticStartedAt = SystemClock.elapsedRealtime()
        lastDiagnosticAt = 0L
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
        // Keep the current choice across transport retries/conversion. An explicit
        // engine menu selection updates it immediately for this item as well.
        val contentKey = "${current.libraryId}:${current.itemId}:${current.partId}:${current.viewItemId}"
        if (enginePreferenceContentKey != contentKey) {
            enginePreferenceContentKey = contentKey
            enginePreferenceForContent = playbackEnginePreference()
        }
        val preference = enginePreferenceForContent
        val start = engineStart(preference, forceMpvSession == key,
            current.isServerConversion, current.sourceItem?.isSpoiler != false, current.preferSoftwareDecode,
            pendingTextPreference != null, current.sessionId.isNullOrBlank(),
            nativeFormat = sourceNeedsNativeEngine(current.sourceContainer, current.sourceVideoCodec, current.selectedAudioCodec))
        if (start == EngineStart.MPV || start == EngineStart.SAFE_MPV ||
            (start == EngineStart.AUTO && client.shyni == null)) {
            if (start == EngineStart.SAFE_MPV) toast("이 재생에는 MPV가 필요해 MPV로 재생합니다.")
            startPreparedPlayer(null, false)
            return
        }
        if (start == EngineStart.MANUAL_EXO) {
            manualExoSelectionPending = true
            engineSelectionJob = scope.launch(start = CoroutineStart.LAZY) {
                val inspected = try { client.playbackSubtitlesForEngine(current) }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                if (generation != engineGeneration || player != null || exiting || isDestroyed ||
                    client.token != account || spec != current || closeLockedSpoilerPlayback()) return@launch
                handler.removeCallbacks(engineSelectionTimeout)
                engineSelectionJob = null
                manualExoSelectionPending = false
                val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                if (inspected == null || !inspected.safeForExo || !visible ||
                    (savedId != null && inspected.tracks.none { it.id == savedId })) {
                    toast("ExoPlayer의 자막을 확인하지 못해 MPV로 재생합니다.")
                    startPreparedPlayer(null, false)
                    return@launch
                }
                spec = spec.copy(subtitles = inspected.tracks, subtitlesPending = false)
                val preferred = if (localSubtitleActiveId != null) null else deferredSubtitleSelection(spec, savedId, deferredSubtitlePreference,
                    subtitlesEnabled, subtitleManuallySelected, pendingTextPreference != null, true)
                deferredSubtitlePreference = null
                if (preferred != null) {
                    if (preferred.isEmbedded) { embeddedServerSubtitleId = preferred.id; externalSubtitleId = null }
                    else { externalSubtitleId = preferred.id; embeddedServerSubtitleId = null }
                }
                // Native MPV sees embedded tracks that Media3 can silently omit.
                // Do not start the requested Exo engine until their identity matches.
                startPreparedPlayer(null, false, manualProbe = true)
            }
            handler.postDelayed(engineSelectionTimeout, 8_000)
            engineSelectionJob?.start()
            return
        }
        engineSelectionJob = scope.launch(start = CoroutineStart.LAZY) {
            var candidateReason = "candidate_policy"
            val candidate = try {
                kotlinx.coroutines.coroutineScope {
                    val information = async { client.shyni?.playbackCodecInfo(current) }
                    // Inspect every raw row, including formats omitted from the regular menu.
                    val subtitles = async { client.playbackSubtitlesForEngine(current) }
                    val info = information.await()
                    val inspected = subtitles.await()
                    val list = inspected.tracks
                    val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                    when {
                        !inspected.safeForExo -> { candidateReason = "subtitle_unverified"; null }
                        savedId != null && list.none { it.id == savedId } -> { candidateReason = "saved_subtitle_missing"; null }
                        info == null -> { candidateReason = "metadata_unavailable"; null }
                        !isPotentialExoCandidate(current, info, list, true) -> null
                        else -> EngineCandidate(info, list)
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { candidateReason = "request_failed"; null }
            if (generation != engineGeneration || player != null || exiting || isDestroyed || client.token != account || spec != current ||
                closeLockedSpoilerPlayback()) return@launch
            handler.removeCallbacks(engineSelectionTimeout)
            engineSelectionJob = null
            // Apply the checked list only for this exact playback, preserving a
            // restored/manual choice and the same default selection as MPV.
            val adopted = candidate.takeIf { visible }
            if (spec.sourceItem?.isSpoiler == false) {
                val reason = if (!visible) "not_visible" else candidateReason
                Log.i("ShyniflexPlayback", "stage=auto_candidate available=${adopted != null}" +
                    if (adopted == null) " reason=$reason" else "")
            }
            if (adopted != null) {
                spec = spec.copy(subtitles = adopted.subtitles, subtitlesPending = false)
                val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                val preferred = if (localSubtitleActiveId != null) null else deferredSubtitleSelection(spec, savedId, deferredSubtitlePreference,
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

    private fun startPreparedPlayer(candidate: PlaybackCodecInfo?, useExo: Boolean,
        chapterCarry: OriginalChapterCredits? = null, manualExo: Boolean = false, manualProbe: Boolean = false,
        sourceHasAudio: Boolean = false, headerExo: Boolean = false) {
        // Exo's Matroska seek can skip a subtitle that started in an earlier
        // cluster. Resume in MPV, whose demuxer retains the active subtitle.
        if ((useExo || candidate != null || manualProbe || manualExo || headerExo) &&
            requiresMpvForEmbeddedTextResume(spec.subtitles, lastPosition)) {
            forceMpvSession = "${spec.sessionId}:${spec.partId}"
            startPreparedPlayer(null, false, chapterCarry = chapterCarry)
            return
        }
        val mediaItem = try {
            MediaItem.Builder().setMediaId("${spec.partId}").setUri(client.playbackUrl(spec)).build()
        } catch (error: ApiException) {
            showError(error.message ?: "영상 주소를 확인할 수 없습니다.")
            return
        }
        val headers = client.playbackHeaders(spec)
        var initializingExo: ExoPlayer? = null
        val created: Player = try {
            if (useExo) try {
                // Media3 normally logs exception stack traces that can include signed URLs.
                // Keep its logs off; this activity reports only sanitized error codes.
                androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
                val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
                val data = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
                ExoPlayer.Builder(this).apply {
                    if (!pictureSettings.isNeutral && exoPictureBypassContent != subtitlePlaybackContentKey(spec))
                        setRenderersFactory(ExoPictureRenderersFactory(this@NativePlayerActivity))
                }.setMediaSourceFactory(DefaultMediaSourceFactory(data))
                    .setHandleAudioBecomingNoisy(true).build().apply {
                        initializingExo = this
                        setAudioAttributes(AudioAttributes.DEFAULT, true)
                        // A neutral first playback keeps Exo's direct surface. Install a real effect
                        // before prepare only when needed; never activate an empty effects pipeline.
                        if (!pictureSettings.isNeutral && exoPictureBypassContent != subtitlePlaybackContentKey(spec)) {
                            val effect = ExoPictureEffect(pictureSettings)
                            setVideoEffects(listOf(effect))
                            exoPictureEffect = effect; exoPictureOwner = this
                        }
                    }
            } catch (_: Exception) {
                initializingExo?.release()
                initializingExo = null
                exoPictureEffect = null; exoPictureOwner = null
                if (exoRestoreSubtitlePending) spec = spec.copy(subtitlesPending = true)
                exoRestoreSubtitlePending = false
                if (manualExo) toast("ExoPlayer를 시작하지 못해 MPV로 재생합니다.")
                MpvPlayer(this, headers, preferSoftwareDecode = spec.preferSoftwareDecode,
                    serverConversion = spec.isServerConversion, sourceDurationMs = lastDuration)
            } else MpvPlayer(this, headers, preferSoftwareDecode = spec.preferSoftwareDecode,
                serverConversion = spec.isServerConversion, sourceDurationMs = lastDuration)
        } catch (_: LinkageError) {
            initializingExo?.release()
            exoPictureEffect = null; exoPictureOwner = null
            onPlayerInitializationFailure()
            return
        } catch (_: Exception) {
            initializingExo?.release()
            exoPictureEffect = null; exoPictureOwner = null
            onPlayerInitializationFailure()
            return
        }
        manualExoProbePending = manualProbe && created is MpvPlayer
        mpvProbePending = (candidate != null || manualProbe) && created is MpvPlayer
        manualExoInUse = manualExo && created is ExoPlayer
        manualExoSourceHasAudio = manualExoInUse && sourceHasAudio
        headerExoInUse = headerExo && created is ExoPlayer
        mpvHeaderHandoffPending = false
        exoValidated = created !is ExoPlayer
        gestures.seekTimeoutMs = if (spec.isServerConversion) 30_000 else 8_000
        player = created
        applyPictureToPlayer()
        chapterCreditsOwner = created.takeIf { created is ExoPlayer && chapterCarry != null &&
            chapterCarry.source == originalChapterSource(spec) }
        chapterCreditsEvidence = chapterCarry.takeIf { chapterCreditsOwner === created }
        chapterCreditsCaptureGate.reset()
        if (created is MpvPlayer) originalChapterSource(spec)?.let { chapterCreditsCaptureGate.bind(created, it) }
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
                    val manual = manualExoProbePending
                    mpvProbePending = false
                    manualExoProbePending = false
                    mpvHeaderHandoffPending = false
                    finishMpvProbe = null
                    handler.removeCallbacks(engineProbeTimeout)
                    mpvProbeJob?.cancel(); mpvProbeJob = null
                    forceMpvSession = "${spec.sessionId}:${spec.partId}"
                    if (manual) toast("ExoPlayer의 영상·자막을 확인하지 못해 MPV로 재생합니다.")
                    if (created.playbackState == Player.STATE_READY) acceptPreparedPlayer(created)
                    finishReplacementAfterFirstFrame(created)
                    created.playWhenReady = resumeWhenVisible && visible
                    refreshSegmentActions()
                }
            }
            // A slow media header must not exhaust an arbitrary 600ms decoder
            // probe. Header validation usually finishes before the first frame;
            // a rendered MPV frame still immediately settles the old fast path.
            handler.postDelayed(engineProbeTimeout, 8_000)
            if (created is MpvPlayer) {
                val revision = engineGeneration
                val source = spec
                val account = client.token
                created.onNativeHeadersReady = {
                    // Native callbacks still have an active event stack. Release
                    // only after returning, and only for this exact playback.
                    handler.post {
                        if (!mayCommitMpvProbe(mpvProbePending, player === created,
                                revision == engineGeneration, spec == source, client.token == account,
                                visible, exiting || isDestroyed) || closeLockedSpoilerPlayback()) return@post
                        if (!created.hasFixedTrackHeaders()) {
                            if (spec.sourceItem?.isSpoiler == false)
                                Log.i("ShyniflexPlayback", "stage=header_checked mode=${if (manualExoProbePending) "manual" else "auto"} accepted=false")
                            finishMpvProbe?.invoke()
                            return@post
                        }
                        captureOriginalChapterCredits(created, allowFixedHeaders = true)
                        if (manualExoProbePending) checkManualMpvProbe(created, fromHeaders = true)
                        else if (candidate != null) checkMpvHeaderProbe(created, candidate)
                    }
                }
            }
        }
        var initialAudioApplied = false
        created.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (player !== created) return
                renderEmbeddedCues(cueGroup)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (player !== created) return
                if (created is ExoPlayer) applyExoPictureAspectRatio(created)
                if (created is ExoPlayer && !pictureSettings.isNeutral &&
                    handleUnsupportedExoPicture(created)) return
                if (created is ExoPlayer && exoValidated && !exoPlaybackTracksSafe(tracks)) {
                    fallbackFromExo(created)
                    return
                }
                applyAutomaticSubtitleDefault(created)
                resolvePendingEmbeddedSubtitle()
                restorePendingTextPreference()
                if (awaitingEmbeddedSubtitle && externalSubtitleId == null && localSubtitleActiveId == null) selectEmbeddedSubtitleFallback()
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
                    if (mpvProbePending && created is MpvPlayer) {
                        if (playbackFirstFrameReported) {
                            if (manualExoProbePending) checkManualMpvProbe(created)
                            else if (candidate != null) checkMpvProbe(created, candidate)
                        }
                        return
                    }
                    if (created is ExoPlayer && !exoValidated) {
                        if (!exoPlaybackTracksSafe(created.currentTracks)) { fallbackFromExo(created); return }
                        exoValidated = true
                        created.playWhenReady = resumeWhenVisible && visible
                    }
                    acceptPreparedPlayer(created)
                } else if (state == Player.STATE_ENDED && !exiting && !refreshing) {
                    if (created is ExoPlayer && requiresMpvForEmbeddedTextResume(spec.subtitles, 1) &&
                        (pendingExoTextSeekTarget != null || gestures.isSeekGestureActive)) return
                    if (mpvProbePending) { finishMpvProbe?.invoke(); return }
                    if (created is ExoPlayer && !exoValidated) { fallbackFromExo(created); return }
                    handler.post {
                        if (player === created && !exiting && !refreshing && !isExoEmbeddedSeekPending(created) && !maybeRepeatStudy(created) && created.reconcilePlaybackCompletion())
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
                    manualExoProbePending = false
                    mpvHeaderHandoffPending = false
                    handler.removeCallbacks(engineProbeTimeout)
                    mpvProbeJob?.cancel(); mpvProbeJob = null
                }
                settleWatchTime(false)
                if (created is ExoPlayer && exoPictureOwner === created &&
                    exoPictureUnsupportedReason(created.currentTracks) == ExoPictureUnsupportedReason.DRM &&
                    handleUnsupportedExoPicture(created)) return
                if (created is ExoPlayer && (shouldFallbackFromExo(error.errorCode) ||
                        (exoPictureOwner === created && isExoPictureProcessingError(error.errorCode))) && !exiting && !refreshing) {
                    if (exoPictureOwner === created) captureTrackPreferencesForReplacement()
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
                if (created is ExoPlayer) applyExoPictureAspectRatio(created)
                exoPictureFrameTimeout?.let(handler::removeCallbacks); exoPictureFrameTimeout = null
                if (!playbackFirstFrameReported && spec.sourceItem?.isSpoiler == false) {
                    Log.i("ShyniflexPlayback", "stage=first_frame engine=${if (created is ExoPlayer) "exo" else "mpv"}" +
                        " elapsed_ms=${(SystemClock.elapsedRealtime() - diagnosticStartedAt).coerceAtLeast(0)}")
                }
                playbackFirstFrameReported = true
                captureOriginalChapterCredits(created)
                if (mpvProbePending && created is MpvPlayer) {
                    if (created.playbackState == Player.STATE_READY) {
                        if (manualExoProbePending) checkManualMpvProbe(created)
                        else if (candidate != null) checkMpvProbe(created, candidate)
                    }
                    return
                }
                finishReplacementAfterFirstFrame(created)
                if (!subtitleListFailed) loadDeferredSubtitles(created)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player !== created) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    completionGate().seek(newPosition.positionMs)
                    studyRepeat.range?.let { range ->
                        if (newPosition.positionMs < range.startMs - 250 || newPosition.positionMs >= range.endMs)
                            studyRepeat.clear()
                    }
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !gestures.isSeekGestureActive) {
                    lastPosition = newPosition.positionMs.coerceAtLeast(0)
                    pendingSeek = null
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK && externalSubtitleId == null) subtitleOverlay.subtitleText = ""
                if (reason == Player.DISCONTINUITY_REASON_SEEK) updateSubtitleText()
                if (reason == Player.DISCONTINUITY_REASON_SEEK && created is ExoPlayer &&
                    !gestures.isSeekGestureActive) scheduleEmbeddedTextMpvAfterSeek(created, newPosition.positionMs)
                refreshSegmentActions()
            }
        })
        created.setMediaItem(mediaItem, lastPosition.coerceAtLeast(0))
        playerView.player = created
        gestures.attachPlayer(created)
        created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled || externalSubtitleId != null || localSubtitleActiveId != null).build()
        if (subtitlesEnabled && localSubtitleActiveId == null) {
            val selectedId = embeddedServerSubtitleId ?: externalSubtitleId
            selectedId?.let { id -> spec.subtitles.firstOrNull { it.id == id }?.let(::selectServerSubtitle) }
        }
        created.prepare()
        created.playWhenReady = resumeWhenVisible && visible && created !is ExoPlayer && !mpvProbePending
        if (localSubtitleActiveId != null) { renderEmbeddedCues(created.currentCues); updateSubtitleText() }
    }

    private fun onPlayerInitializationFailure() {
        val message = "재생 엔진을 시작하지 못했습니다.\n다시 시도하거나 앱을 다시 시작해 주세요."
        if (conversionRestore == null) {
            showError(message)
            return
        }
        // Let the handoff finish setting up its timeout before restoring the
        // previous source. A newer playback request invalidates this callback.
        val generation = engineGeneration
        handler.post {
            if (generation == engineGeneration && player == null && !exiting && !isDestroyed && conversionRestore != null)
                restoreConversion(message)
        }
    }

    private fun finishReplacementAfterFirstFrame(created: Player) {
        if (player !== created || !canFinishPlaybackHandoff(conversionRestore != null,
                playbackFirstFrameReported, mpvProbePending)) return
        conversionRestore = null
        continuousPrevious = null
        continuousPlaybackGate.reset()
        dismissContinuousLoading(restoreFocus = true)
        conversionLoading = false
        handler.removeCallbacks(conversionTimeout)
        updatePlaybackButtons()
    }

    private fun fallbackFromExo(active: Player) {
        fallbackFromExoAtPosition(active, null)
    }

    private fun isExoEmbeddedSeekPending(active: Player): Boolean = active is ExoPlayer &&
        requiresMpvForEmbeddedTextResume(spec.subtitles, 1) &&
        (pendingExoTextSeekTarget != null || gestures.isSeekGestureActive)

    private fun scheduleEmbeddedTextMpvAfterSeek(active: ExoPlayer, positionMs: Long, playIntent: Boolean? = null) {
        if (player !== active || !exoValidated || !requiresMpvForEmbeddedTextResume(spec.subtitles, 1)) return
        val alreadyPosted = pendingExoTextSeekTarget != null
        pendingExoTextSeekTarget = positionMs.coerceAtLeast(0)
        pendingExoTextSeekIntent = playIntent
        if (alreadyPosted) return
        val generation = engineGeneration
        handler.post {
            if (player !== active || engineGeneration != generation) return@post
            val target = pendingExoTextSeekTarget
            val playing = pendingExoTextSeekIntent
            pendingExoTextSeekTarget = null
            pendingExoTextSeekIntent = null
            if (target != null && !gestures.isSeekGestureActive)
                fallbackFromExoAtPosition(active, target, playing)
        }
    }

    private fun fallbackFromExoAtPosition(active: Player, requestedPosition: Long?, playIntent: Boolean? = null) {
        if (active !is ExoPlayer || player !== active || exiting || isDestroyed || refreshing) return
        if (manualExoInUse) toast("이 영상 또는 자막을 ExoPlayer에서 지원하지 않아 MPV로 재생합니다.")
        val playing = playIntent ?: if (!visible || !exoValidated) resumeWhenVisible else active.playWhenReady
        // Before track validation Exo's default position can still be zero;
        // preserve the authorized resume point until it has a valid timeline.
        lastPosition = requestedPosition ?: exoFallbackPosition(lastPosition, active.currentPosition, exoValidated, playbackFirstFrameReported)
        if (exoRestoreSubtitlePending && spec.subtitles.isEmpty()) spec = spec.copy(subtitlesPending = true)
        exoRestoreSubtitlePending = false
        forceMpvSession = "${spec.sessionId}:${spec.partId}"
        preparePlayback(playing)
    }

    private fun exoPlaybackTracksSafe(tracks: androidx.media3.common.Tracks): Boolean {
        val embedded = spec.subtitles.singleOrNull(::exoEmbeddedSubtitle)
        val expected = embedded != null
        return if (manualExoInUse) manualExoTracksSafe(tracks, expected, manualExoSourceHasAudio, embedded?.format)
        else if (headerExoInUse) exoHeaderTracksSafe(tracks, expected, embedded?.format)
        else exoTracksSafe(tracks, expected, embedded?.format)
    }

    private fun acceptPreparedPlayer(created: Player) {
        if (player !== created || exiting || isDestroyed) return
        prepared = true
        completed = false
        if (created is ExoPlayer) {
            applyExoPictureAspectRatio(created)
            watchExoPictureFirstFrame(created)
        }
        applyAutomaticSubtitleDefault(created)
        resolvePendingEmbeddedSubtitle()
        restorePendingTextPreference()
        if (created.duration != C.TIME_UNSET && created.duration > 0) preserveFullDuration(created.duration)
        if (playbackFirstFrameReported && !subtitleListFailed) loadDeferredSubtitles(created)
        resolveSubtitleOrigin(created)
        refreshPlaybackMetadata(created)
        if (getSharedPreferences("gsy_settings", MODE_PRIVATE).getBoolean("language_study_auto_open", false)) {
            val content = studyContentKey
            handler.post {
                if (player === created && visible && prepared && !exiting && !isDestroyed &&
                    content != null && content == studyContentKey && studyAutoOpenedContent != content) {
                    studyAutoOpenedContent = content
                    showLanguageStudy()
                }
            }
        }
    }

    /** Probe with MPV's raw track list before Exo, which can hide unknown MP4 subtitle tracks. */
    private fun checkMpvProbe(created: MpvPlayer, candidate: PlaybackCodecInfo) {
        if (!mpvProbePending || mpvHeaderHandoffPending || player !== created || mpvProbeJob?.isActive == true || exiting || isDestroyed) return
        if (requiresMpvForEmbeddedTextResume(spec.subtitles, lastPosition)) { finishMpvProbe?.invoke(); return }
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
            // Carry only a snapshot taken from this validated original MPV source.
            val chapterCarry = chapterCreditsEvidence.takeIf {
                chapterCreditsOwner === created && it?.source == originalChapterSource(spec)
            }
            captureTrackPreferencesForReplacement()
            releasePlayer()
            exoRestoreSubtitlePending = pendingSubtitles
            spec = spec.copy(subtitlesPending = false)
            playbackFirstFrameReported = false
            startPreparedPlayer(null, true, chapterCarry)
        }
    }

    /** Header-indexed originals need no decoded MPV frame to identify every
     * audio/subtitle track. Exo must still validate its own extracted profiles. */
    private fun checkMpvHeaderProbe(created: MpvPlayer, candidate: PlaybackCodecInfo) {
        if (!mpvProbePending || mpvHeaderHandoffPending || player !== created ||
            mpvProbeJob?.isActive == true || exiting || isDestroyed || !visible) return
        if (requiresMpvForEmbeddedTextResume(spec.subtitles, lastPosition)) { finishMpvProbe?.invoke(); return }
        val embedded = spec.subtitles.filter(::exoEmbeddedSubtitle).mapNotNull { it.streamIndex }
        val tracks = created.exoHeaderEvidence()
        val audio = created.manualExoEvidence(embedded, spec.selectedAudioStreamIndex, spec.selectedAudioStreamOrdinal)
        val accepted = tracks != null && audio?.hasAudio == true &&
            audio.subtitleCodec == exoNativeSubtitleKind(spec.subtitles.singleOrNull(::exoEmbeddedSubtitle)?.format) &&
            isSafeExoHeaderProbe(candidate, tracks, spec.subtitles)
        if (spec.sourceItem?.isSpoiler == false)
            Log.i("ShyniflexPlayback", "stage=header_checked mode=auto accepted=$accepted")
        if (!accepted) {
            finishMpvProbe?.invoke()
            return
        }
        mpvHeaderHandoffPending = true
        val revision = engineGeneration
        val source = spec
        val account = client.token
        handler.post {
            if (!mayCommitMpvProbe(mpvProbePending && mpvHeaderHandoffPending, player === created,
                    revision == engineGeneration, spec == source, client.token == account,
                    visible, exiting || isDestroyed) || closeLockedSpoilerPlayback()) {
                if (player === created) mpvHeaderHandoffPending = false
                return@post
            }
            val chapterCarry = chapterCreditsEvidence.takeIf {
                chapterCreditsOwner === created && it?.source == originalChapterSource(spec)
            }
            val pendingSubtitles = spec.subtitlesPending
            finishMpvProbe = null
            handler.removeCallbacks(engineProbeTimeout)
            captureTrackPreferencesForReplacement()
            releasePlayer()
            exoRestoreSubtitlePending = pendingSubtitles
            spec = spec.copy(subtitlesPending = false)
            playbackFirstFrameReported = false
            startPreparedPlayer(null, true, chapterCarry, headerExo = true)
        }
    }

    /** A deliberate Exo choice still checks MPV's full native tracks for hidden audio/text. */
    private fun checkManualMpvProbe(created: MpvPlayer, fromHeaders: Boolean = false) {
        if (!manualExoProbePending || !mpvProbePending || player !== created || exiting || isDestroyed ||
            !visible || closeLockedSpoilerPlayback()) return
        if (requiresMpvForEmbeddedTextResume(spec.subtitles, lastPosition)) { finishMpvProbe?.invoke(); return }
        val embedded = spec.subtitles.filter(::exoEmbeddedSubtitle).mapNotNull { it.streamIndex }
        val evidence = created.manualExoEvidence(embedded, spec.selectedAudioStreamIndex,
            spec.selectedAudioStreamOrdinal)
        if (fromHeaders && spec.sourceItem?.isSpoiler == false)
            Log.i("ShyniflexPlayback", "stage=header_checked mode=manual accepted=${evidence != null}")
        if (evidence == null || evidence.subtitleCodec !=
            exoNativeSubtitleKind(spec.subtitles.singleOrNull(::exoEmbeddedSubtitle)?.format)) {
            finishMpvProbe?.invoke(); return
        }
        val revision = engineGeneration
        val source = spec
        val account = client.token
        // Releasing a player while it dispatches a listener can invalidate its
        // event iteration; defer the handoff and fence it against user actions.
        handler.post {
            if (!mayCommitMpvProbe(mpvProbePending && manualExoProbePending, player === created,
                    revision == engineGeneration, spec == source, client.token == account,
                    visible, exiting || isDestroyed) || closeLockedSpoilerPlayback()) return@post
            val chapterCarry = chapterCreditsEvidence.takeIf {
                chapterCreditsOwner === created && it?.source == originalChapterSource(spec)
            }
            val pendingSubtitles = spec.subtitlesPending
            finishMpvProbe = null
            handler.removeCallbacks(engineProbeTimeout)
            captureTrackPreferencesForReplacement()
            releasePlayer()
            exoRestoreSubtitlePending = pendingSubtitles
            spec = spec.copy(subtitlesPending = false)
            playbackFirstFrameReported = false
            startPreparedPlayer(null, true, chapterCarry, manualExo = true, sourceHasAudio = evidence.hasAudio)
        }
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

    private fun applySegmentActionHighlight(view: View) {
        applyControlHighlight(view)
        view.background = getDrawable(R.drawable.player_credit_action)
        view.backgroundTintList = null
    }

    private fun applyMenuHighlight(view: View) {
        val colors = playerMenuColors()
        fun focused() = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(colors.surfaceVariant.toArgb())
            setStroke(dp(1), colors.primary.toArgb())
        }
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), ColorDrawable(Color.TRANSPARENT))
            addState(intArrayOf(android.R.attr.state_pressed), focused())
            addState(intArrayOf(android.R.attr.state_focused), focused())
            addState(intArrayOf(android.R.attr.state_selected), focused())
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        view.backgroundTintList = null
        view.elevation = 0f
        view.stateListAnimator = null
    }

    private fun playerMenuColors() = CinemaAppearancePreferences(this).read().let { appearance ->
        cinemaColorScheme(androidx.compose.ui.graphics.Color(appearance.backgroundArgb), appearance.style)
    }

    /** Sparse numeric-only local logs. Private playback is never sampled into logcat. */
    private fun samplePlaybackDiagnostics(active: Player) {
        val now = SystemClock.elapsedRealtime()
        if (spec.sourceItem?.isSpoiler != false || now - lastDiagnosticAt < 5_000 ||
            (now - diagnosticStartedAt > 120_000 && active.playbackState != Player.STATE_BUFFERING)) return
        lastDiagnosticAt = now
        Log.i("ShyniflexPlayback", playbackDiagnosticLog(if (active is MpvPlayer) "mpv" else "exo",
            active.playbackState, playbackFirstFrameReported, active.isPlaying,
            (active as? MpvPlayer)?.streamDiagnostics(), active.totalBufferedDuration))
    }

    private fun showPlaybackDiagnostics() {
        diagnosticDialog?.dismiss()
        val dialog = AlertDialog.Builder(this).setTitle("재생 상태")
            .setMessage("확인 중…").setPositiveButton("닫기", null).create()
        diagnosticDialog = dialog
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || !visible || exiting || isDestroyed) return
                val active = player ?: return
                val native = (active as? MpvPlayer)?.streamDiagnostics()
                val cache = native?.cacheSeconds ?: (active.totalBufferedDuration.coerceAtLeast(0) / 1000.0)
                val state = when (active.playbackState) {
                    Player.STATE_BUFFERING -> "불러오는 중"
                    Player.STATE_ENDED -> "재생 완료"
                    Player.STATE_READY -> if (active.isPlaying) "재생 중" else "일시정지"
                    else -> "준비 중"
                }
                dialog.setMessage(buildString {
                    appendLine("${playbackModeLabel(spec)} · ${if (active is MpvPlayer) "MPV" else "ExoPlayer"}")
                    appendLine(state)
                    appendLine("미리 받은 영상: ${diagnosticNumber(cache)}초")
                    if (native != null) {
                        appendLine("디코딩: ${diagnosticDecoder(native.hardwareDecoder)}")
                        appendLine("수신 속도: ${diagnosticNumber(native.inputBytesPerSecond?.times(8)?.div(1_000_000))} Mbps")
                        appendLine("영상 비트레이트: ${diagnosticNumber(native.videoBitsPerSecond?.div(1_000_000))} Mbps")
                        appendLine("버퍼 회복 목표: ${native.rebufferTargetSeconds}초")
                        appendLine("\n속도는 최근 패킷의 추정값입니다. 미리 받기가 끝나면 수신 속도가 낮아질 수 있습니다.")
                    }
                }.trim())
                handler.postDelayed(this, 1_000)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(refresh)
            if (diagnosticDialog === dialog) diagnosticDialog = null
        }
        dialog.show()
        refresh.run()
    }

    private fun showSettings() {
        val colors = playerMenuColors()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(colors.surface.toArgb())
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        panel.addView(TextView(this).apply {
            text = "재생 설정"; textSize = 16f; setTextColor(colors.onSurface.toArgb())
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
            text = "닫기"; textSize = 14f; setTextColor(colors.onSurface.toArgb()); applyMenuHighlight(this)
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, dp(48)))

        fun addRow(label: String, value: String = "", action: () -> Unit) {
            val row = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true; isFocusable = true
                applyMenuHighlight(this)
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                contentDescription = listOf(label, value).filter { it.isNotBlank() }.joinToString(", ")
                setOnClickListener { dialog.dismiss(); action() }
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(colors.onSurface.toArgb())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = value; textSize = 12f; setTextColor(colors.primary.toArgb())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(colors.onSurfaceVariant.toArgb()); gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(18), -2))
            rows.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
            focusRows.add(row)
            rows.addView(View(this).apply { setBackgroundColor(colors.outlineVariant.toArgb()) },
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
                button.setTextColor(if (selected == index) colors.primary.toArgb() else colors.onSurface.toArgb())
                applyMenuHighlight(button)
            }
            rows.removeAllViews()
            focusRows.clear()
            when (index) {
                -1 -> {
                    addRow("영상 보정", pictureSettingsLabel()) { showPictureSettings() }
                    addRow("어학 학습", if (studyRepeat.range != null) "반복 중" else "문장 · 구간 반복 · 동시 자막") { showLanguageStudy() }
                    addRow("배속", speedLabel()) { showSpeed() }
                    addRow("재생 엔진", playbackEngineSettingLabel()) { showPlaybackEnginePreference() }
                    addRow("재생 상태", playbackModeLabel(spec)) { showPlaybackDiagnostics() }
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
                    if (client.shyni != null) addRow("변환 (데이터 절약)", conversionLabel()) { showConversion() }
                    addRow("오디오 트랙", selectedTrackLabel(C.TRACK_TYPE_AUDIO)) { showTracks(C.TRACK_TYPE_AUDIO) }
                    addRow("자막 선택", if (!subtitlesEnabled) "끔" else selectedTrackLabel(C.TRACK_TYPE_TEXT)) { showTracks(C.TRACK_TYPE_TEXT) }
                    addRow("자막 글꼴", PlexSubtitleFonts.options(this).firstOrNull { it.key == subtitleFont }?.label.orEmpty()) { showSubtitleFont() }
                    addRow("자막 위치") { showSubtitlePosition() }
                    addRow("자막 정렬") { showSubtitleAlignment() }
                    addRow("자막 모양") { showSubtitleAppearance() }
                    if (externalSubtitleId != null || localSubtitleActiveId != null) addRow("자막 싱크", String.format(Locale.KOREAN, "%+.1f초", subtitleOffsetMs / 1000.0)) { showSubtitleOffset() }
                    addRow("싱크 다시 맞추기") { resynchronize() }
                }
                0 -> {
                    addRow("어학 학습", if (studyRepeat.range != null) "반복 중" else "문장 · 구간 반복 · 동시 자막") { showLanguageStudy() }
                    addRow("배속", speedLabel()) { showSpeed() }
                    addRow("재생 엔진", playbackEngineSettingLabel()) { showPlaybackEnginePreference() }
                }
                1 -> {
                    addRow("영상 보정", pictureSettingsLabel()) { showPictureSettings() }
                    if (client.shyni != null) addRow("변환 (데이터 절약)", conversionLabel()) { showConversion() }
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
                    addRow("자막 정렬") { showSubtitleAlignment() }
                    addRow("자막 모양") { showSubtitleAppearance() }
                    if (externalSubtitleId != null || localSubtitleActiveId != null) addRow("자막 싱크", String.format(Locale.KOREAN, "%+.1f초", subtitleOffsetMs / 1000.0)) { showSubtitleOffset() }
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

    private fun playbackEnginePreference(): PlaybackEnginePreference = PlaybackEnginePreference.fromKey(
        getSharedPreferences("gsy_settings", MODE_PRIVATE).getString("playback_engine", null))

    private fun playbackEngineSettingLabel(): String {
        val active = when (player) {
            is MpvPlayer -> "MPV"
            is ExoPlayer -> "ExoPlayer"
            else -> "확인 중"
        }
        return "${playbackEnginePreference().label} · 현재 $active"
    }

    private fun showPlaybackEnginePreference() {
        val choices = PlaybackEnginePreference.entries
        val selected = choices.indexOf(playbackEnginePreference())
        val labels = choices.map { it.label }
        val dialog = AlertDialog.Builder(this).setTitle("재생 엔진 · 현재 영상에 적용")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { choice, index ->
                val next = choices.getOrNull(index) ?: return@setSingleChoiceItems
                choice.dismiss()
                applyPlaybackEnginePreference(next)
            }.setNegativeButton("취소", null).show()
        dialog.window?.decorView?.tag = "playback-engine-choice"
        if (television) dialog.window?.setLayout(dp(360), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun applyPlaybackEnginePreference(next: PlaybackEnginePreference) {
        if (exiting || isDestroyed || refreshing || navigationLoading) return
        getSharedPreferences("gsy_settings", MODE_PRIVATE).edit().putString("playback_engine", next.key).apply()
        enginePreferenceForContent = next
        forceMpvSession = null
        val active = player
        val sameEngine = playbackEngineSettled(mpvProbePending, active is ExoPlayer && !exoValidated) &&
            ((next == PlaybackEnginePreference.MPV && active is MpvPlayer) ||
                (next == PlaybackEnginePreference.EXO && active is ExoPlayer))
        if (sameEngine) {
            applyPictureToPlayer()
            toast("선택한 재생 엔진이 적용되어 있습니다.")
            return
        }
        toast("현재 위치에서 ${next.label} 재생 엔진을 적용합니다.")
        restartPlaybackAtCurrentPosition()
    }

    private fun selectedTrackLabel(type: Int): String {
        if (type == C.TRACK_TYPE_TEXT) {
            if (!subtitlesEnabled) return "끔"
            localSubtitleRecord?.takeIf { it.id == localSubtitleActiveId }?.let { return it.label }
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

    /** A ticket/engine change belongs to the same viewing; a different file gets its own default. */
    private fun subtitlePlaybackContentKey(source: PlaybackSpec): String =
        if (source.partId > 0) "${source.libraryId}:${source.partId}"
        else "${source.libraryId}:${source.itemId}:${source.viewItemId}:${source.url}"

    private fun ensureSubtitleDefaults() {
        val next = subtitlePlaybackContentKey(spec)
        if (subtitlePolicyContentKey == next) return
        subtitlePolicyContentKey = next
        subtitleOriginAttemptedKey = null
        subtitleDefaultAudioLanguage = null; subtitleAudioDefaultOff = false
        subtitleAutomaticRestoreRevision = null
        subtitlesEnabled = defaultSubtitlesEnabled(spec)
        subtitleManuallySelected = false; restoredLocalSubtitleId = null
        clearExternalSubtitle(); externalSubtitleId = if (subtitlesEnabled) preferredSubtitle(spec)?.id else null
        pendingTextPreference = null; deferredSubtitlePreference = null
    }

    private fun ensureLocalSubtitleSource(forceReload: Boolean = false) {
        val next = localSubtitleOwner(client.server, client.token, spec)
        if (next == localSubtitleOwnerKey && !forceReload) return
        localSubtitleReadJob?.cancel()
        val wasActive = localSubtitleActiveId != null
        localSubtitleOwnerKey = next; localSubtitleRecord = null
        localSubtitleActiveId = null; localSubtitleTimeline = null
        localSubtitleChoiceRevision++
        if (wasActive) subtitleOverlay.subtitleText = ""
        val owner = next ?: return
        val revision = localSubtitleChoiceRevision
        val account = client.token
        localSubtitleReadJob = scope.launch {
            val record = try { withContext(Dispatchers.IO) {
                LocalSubtitleWrites.awaitPending()
                localSubtitleStore.find(owner)
            } }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (!localSubtitleCurrent(owner, account) || revision != localSubtitleChoiceRevision) return@launch
            localSubtitleRecord = record
            val restoreManualChoice = subtitleManuallySelected && subtitlesEnabled && record?.id == restoredLocalSubtitleId
            if (record?.selected == true && (defaultSubtitlesEnabled(spec, subtitleDefaultAudioLanguage) || restoreManualChoice))
                loadLocalSubtitle(owner, record, revision, account, manualSelection = restoreManualChoice)
        }
    }

    private fun localSubtitleCurrent(owner: String, account: String?): Boolean =
        !exiting && !isDestroyed && ::spec.isInitialized && ::client.isInitialized && client.token == account &&
            owner == localSubtitleOwnerKey && owner == localSubtitleOwner(client.server, account, spec) &&
            !closeLockedSpoilerPlayback()

    private fun loadLocalSubtitle(owner: String, record: LocalSubtitleRecord, revision: Int, account: String?,
        manualSelection: Boolean = true) {
        localSubtitleReadJob?.cancel()
        localSubtitleReadJob = scope.launch {
            try {
                val timeline = withContext(Dispatchers.IO) { SubtitleTimeline(localSubtitleStore.load(owner, record.id)) }
                if (!localSubtitleCurrent(owner, account) || revision != localSubtitleChoiceRevision) return@launch
                clearExternalSubtitle(); externalSubtitleId = null; embeddedServerSubtitleId = null
                pendingTextPreference = null; deferredSubtitlePreference = null; awaitingEmbeddedSubtitle = false
                localSubtitleRecord = record.copy(selected = true)
                localSubtitleActiveId = record.id; localSubtitleTimeline = timeline
                restoredLocalSubtitleId = record.id
                subtitlesEnabled = true; subtitleManuallySelected = subtitleManuallySelected || manualSelection
                player?.let { active ->
                    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    renderEmbeddedCues(active.currentCues)
                }
                updateSubtitleText()
                LocalSubtitleWrites.select(localSubtitleStore, owner, record.id, true)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { if (localSubtitleCurrent(owner, account) && revision == localSubtitleChoiceRevision) toast("저장된 자막을 불러오지 못했습니다.") }
        }
    }

    private fun deactivateLocalSubtitle() {
        restoredLocalSubtitleId = null
        localSubtitleChoiceRevision++
        localSubtitleReadJob?.cancel(); localSubtitleReadJob = null
        val owner = localSubtitleOwnerKey
        val record = localSubtitleRecord
        if (owner != null && record != null && (record.selected || localSubtitleActiveId == record.id))
            LocalSubtitleWrites.select(localSubtitleStore, owner, record.id, false)
        else if (owner != null && record == null) {
            val revision = localSubtitleChoiceRevision
            val account = client.token
            LocalSubtitleWrites.deselectCurrent(localSubtitleStore, owner) { success ->
                if (success) handler.post {
                    if (localSubtitleCurrent(owner, account) && localSubtitleChoiceRevision == revision && localSubtitleRecord == null)
                        scope.launch {
                            val current = withContext(Dispatchers.IO) { localSubtitleStore.find(owner) }
                            if (localSubtitleCurrent(owner, account) && localSubtitleChoiceRevision == revision && localSubtitleRecord == null)
                                localSubtitleRecord = current
                        }
                }
            }
        }
        localSubtitleRecord = record?.copy(selected = false)
        localSubtitleActiveId = null; localSubtitleTimeline = null
    }

    private fun receiveLocalSubtitleResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        val owner = data?.getStringExtra(SubtitleImportActivity.EXTRA_OWNER_KEY) ?: return
        val id = data.getStringExtra(SubtitleImportActivity.EXTRA_SUBTITLE_ID) ?: return
        if (owner != localSubtitleOwnerKey || (localSubtitleLaunchOwner != null && owner != localSubtitleLaunchOwner)) return
        if (localSubtitleLaunchOwner != null && localSubtitleLaunchRevision != localSubtitleChoiceRevision) {
            LocalSubtitleWrites.select(localSubtitleStore, owner, id, false)
            return
        }
        if (!localSubtitleCurrent(owner, client.token)) return
        val account = client.token
        val revision = ++localSubtitleChoiceRevision
        localSubtitleReadJob?.cancel()
        localSubtitleReadJob = scope.launch {
            try {
                val record = withContext(Dispatchers.IO) { localSubtitleStore.find(owner) }
                if (!localSubtitleCurrent(owner, account) || revision != localSubtitleChoiceRevision || record?.id != id) return@launch
                localSubtitleRecord = record
                loadLocalSubtitle(owner, record, revision, account)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
              catch (_: Exception) { if (localSubtitleCurrent(owner, account)) toast("불러온 자막을 확인하지 못했습니다.") }
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
                        nativeText.single().first.getTrackFormat(nativeText.single().second).let { exoSubtitleMimeMatches(track.format, it.sampleMimeType, it.codecs) })
                        nativeText.single().first.getTrackFormat(nativeText.single().second).id else null)
                    ?.takeIf { it in nativeIds }?.let { track.id to it }
            }.toMap() else emptyMap()
        val selectedNativeIds = nativeText.filter { (group, index) -> group.isTrackSelected(index) }
            .mapNotNull { (group, index) -> group.getTrackFormat(index).id }.toSet()
        if (type == C.TRACK_TYPE_TEXT) {
            choices += TrackChoice("자막 끄기", !subtitlesEnabled) {
                subtitleManuallySelected = true
                subtitlesEnabled = false; clearExternalSubtitle(); studySecondary.clear()
                active.trackSelectionParameters = active.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
                playerView.subtitleView?.visibility = View.GONE
            }
            spec.subtitles.forEach { track ->
                val selectedId = embeddedServerSubtitleId ?: externalSubtitleId
                val selected = subtitlesEnabled && localSubtitleActiveId == null && (selectedId == track.id ||
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
            localSubtitleRecord?.let { record ->
                choices += TrackChoice("불러온 자막 · ${record.label}", subtitlesEnabled && localSubtitleActiveId == record.id, changesSelection = false) {
                    val owner = localSubtitleOwnerKey ?: return@TrackChoice
                    val revision = ++localSubtitleChoiceRevision
                    deferredSubtitlePreference = null
                    subtitleManuallySelected = true
                    loadLocalSubtitle(owner, record, revision, client.token)
                }
                choices += TrackChoice("불러온 자막 삭제", false, changesSelection = false) {
                    val owner = localSubtitleOwnerKey ?: return@TrackChoice
                    localSubtitleChoiceRevision++; localSubtitleReadJob?.cancel()
                    restoredLocalSubtitleId = null
                    if (localSubtitleActiveId == record.id) {
                        localSubtitleActiveId = null; localSubtitleTimeline = null
                        subtitlesEnabled = false; subtitleOverlay.subtitleText = ""; subtitleOverlay.visibility = View.GONE
                        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }
                    localSubtitleRecord = null
                    LocalSubtitleWrites.remove(localSubtitleStore, owner, record.id) { success ->
                        if (!success) handler.post { if (!exiting && !isDestroyed && localSubtitleOwnerKey == owner) toast("자막을 삭제하지 못했습니다.") }
                    }
                }
            }
            choices += TrackChoice("자막 파일 불러오기", false, changesSelection = false) {
                val owner = localSubtitleOwnerKey ?: return@TrackChoice
                localSubtitleLaunchOwner = owner; localSubtitleLaunchRevision = localSubtitleChoiceRevision
                try { localSubtitlePicker.launch(Intent(this, SubtitleImportActivity::class.java).putExtra(SubtitleImportActivity.EXTRA_OWNER_KEY, owner)) }
                catch (_: Exception) { toast("파일 선택 화면을 열지 못했습니다.") }
            }
            if (BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank()) choices += TrackChoice("온라인 자막 검색", false, changesSelection = false) {
                val owner = localSubtitleOwnerKey ?: return@TrackChoice
                localSubtitleLaunchOwner = owner; localSubtitleLaunchRevision = localSubtitleChoiceRevision
                try { onlineSubtitlePicker.launch(OnlineSubtitleActivity.createIntent(this, owner, spec)) }
                catch (_: Exception) { toast("온라인 자막 검색을 열지 못했습니다.") }
            }
            choices += TrackChoice("웹에서 자막 검색", false, changesSelection = false) {
                handler.post {
                    if (!exiting && !isDestroyed && !closeLockedSpoilerPlayback()) {
                        subtitleDialog?.dismiss(); subtitleDialog = showSubtitleWebSearch(this, spec)
                    }
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
                    (subtitlesEnabled && localSubtitleActiveId == null && externalSubtitleId == null && embeddedServerSubtitleId == null))) {
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
                if (type == C.TRACK_TYPE_TEXT && choices[index].changesSelection) {
                    pendingTextPreference = null; deferredSubtitlePreference = null
                    deactivateLocalSubtitle()
                }
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

    private fun ensureStudySource() {
        val key = subtitlePlaybackContentKey(spec)
        if (studyContentKey != key) {
            studyContentKey = key
            studyRepeat.clear()
            studySentenceSourceKey = null
            studySecondaryOffsetMs = 0L
            studySecondaryAtTop = getSharedPreferences("gsy_settings", MODE_PRIVATE).getBoolean("study_secondary_top", true)
            applyStudySecondaryStyle()
            studyDialog?.dismiss(); studyDialog = null
        }
        studySecondary.reset(localSubtitleOwner(client.server, client.token, spec))
    }

    private fun studyTimeline(): Pair<SubtitleTimeline?, Long> = when {
        subtitlesEnabled && localSubtitleActiveId != null -> localSubtitleTimeline to subtitleOffsetMs
        subtitlesEnabled && externalSubtitleId != null -> subtitleTimeline to subtitleOffsetMs
        else -> studySecondary.timeline to studySecondaryOffsetMs
    }

    private fun syncStudySentenceSource() {
        val (_, offset) = studyTimeline()
        // The same selected subtitle can briefly lose its parsed timeline while an engine reloads.
        val key = when {
            subtitlesEnabled && localSubtitleActiveId != null -> "local:$localSubtitleActiveId"
            subtitlesEnabled && externalSubtitleId != null -> "server:$externalSubtitleId"
            else -> "secondary:${System.identityHashCode(studySecondary.timeline)}"
        }
        if (key != studySentenceSourceKey || offset != studySentenceOffsetMs) {
            studyRepeat.cancelSentence()
            studySentenceSourceKey = key
            studySentenceOffsetMs = offset
        }
    }

    private fun studyPosition() = (player?.currentPosition ?: lastPosition).coerceAtLeast(0)

    private fun studyTime(value: Long): String {
        val seconds = value.coerceAtLeast(0) / 1000
        return String.format(Locale.KOREAN, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun studyReady(requireUnlocked: Boolean = true) = visible && prepared && player != null && !exiting && !refreshing &&
        !conversionLoading && !navigationLoading && !mpvProbePending && !playerGestureActive &&
        !gestures.isSeekGestureActive && (!requireUnlocked || !gestures.locked) &&
        player?.isCurrentMediaItemSeekable == true &&
        player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true &&
        playbackEngineSettled(mpvProbePending, player is ExoPlayer && !exoValidated)

    private fun studySnapshot(): LanguageStudySnapshot {
        syncStudySentenceSource()
        val (timeline, offset) = studyTimeline()
        val position = studyPosition()
        val current = timeline?.studyCueAt(position, offset)
        val range = studyRepeat.range
        val ready = studyReady()
        val status = when {
            prepared && player?.isCurrentMediaItemSeekable == false -> "이 영상은 위치 이동을 지원하지 않아 문장·구간 반복을 사용할 수 없습니다."
            !ready -> "재생 준비가 끝나면 사용할 수 있습니다."
            timeline == null -> "문장 이동·반복은 시간 정보가 있는 자막이 필요합니다. 내장 자막은 A–B 반복을 이용하세요."
            current == null -> "현재 자막이 없는 구간입니다. 이전·다음 문장으로 이동할 수 있습니다."
            else -> "선택한 텍스트 자막의 시간을 기준으로 이동합니다."
        }
        return LanguageStudySnapshot(status = status, sentenceText = current?.text.orEmpty(),
            canPrevious = ready && timeline?.previousStudyCue(position, offset) != null,
            canNext = ready && timeline?.nextStudyCue(position, offset) != null,
            canRepeatSentence = ready && current != null && current.endMs - current.startMs >= 500,
            repeatLabel = when {
                range != null -> "${if (studyRepeat.sentence) "문장 반복" else "A–B 반복"} · ${studyTime(range.startMs)} – ${studyTime(range.endMs)}"
                studyRepeat.startMs != null -> "A ${studyTime(studyRepeat.startMs!!)} · B를 지정하세요"
                else -> "반복 꺼짐"
            },
            canMarkEnd = ready && studyRepeat.startMs?.let { position - it >= 500 } == true,
            secondaryLabel = if (studySecondary.loading) "번역 자막 불러오는 중" else studySecondary.label,
            speedLabel = speedLabel())
    }

    private fun showLanguageStudy() {
        if (exiting || isDestroyed || !::gestures.isInitialized || gestures.locked || closeLockedSpoilerPlayback()) return
        studyDialog?.dismiss()
        studyDialog = LanguageStudyDialog(this, ::studySnapshot,
            onPrevious = { seekStudySentence(false) }, onNext = { seekStudySentence(true) },
            onRepeatSentence = {
                if (studyReady()) {
                    val (timeline, offset) = studyTimeline()
                    timeline?.studyCueAt(studyPosition(), offset)?.let { cue ->
                        if (studyRepeat.repeatSentence(StudyInterval(cue.startMs, cue.endMs), lastDuration))
                            startStudyRepeat()
                    }
                }
            },
            onMarkStart = { if (studyReady()) studyRepeat.markStart(studyPosition()) },
            onMarkEnd = {
                if (studyReady() && studyRepeat.markEnd(studyPosition(), lastDuration))
                    startStudyRepeat()
                else toast("A보다 0.5초 이상 뒤에서 B를 지정해 주세요.")
            },
            onClearRepeat = { studyRepeat.clear() },
            onSecondarySubtitles = ::showStudySubtitles, onSpeed = ::showSpeed).also { it.show() }
    }

    private fun seekStudySentence(next: Boolean) {
        if (!studyReady()) return
        val (timeline, offset) = studyTimeline()
        val cue = if (next) timeline?.nextStudyCue(studyPosition(), offset)
            else timeline?.previousStudyCue(studyPosition(), offset)
        cue ?: return
        studyRepeat.clear()
        seekForStudy(cue.startMs)
    }

    private fun startStudyRepeat() {
        val range = studyRepeat.range ?: return
        // Arm the pending-seek guard before the asynchronous initial seek, too.
        val decision = studyRepeat.poll(range.endMs, ended = false, SystemClock.elapsedRealtime())
        if (decision is StudyRepeatDecision.Seek) seekForStudy(decision.positionMs, resume = true)
    }

    private fun seekForStudy(target: Long, resume: Boolean = false) {
        val active = player ?: return
        val position = if (lastDuration > 0) target.coerceIn(0, lastDuration - 1) else target.coerceAtLeast(0)
        completionGate().seek(position)
        lastPosition = position; pendingSeek = position
        active.seekTo(position)
        if (resume) { resumeWhenVisible = true; active.play() }
        updateSubtitleText(); updateStudySecondaryText(); refreshSegmentActions()
    }

    /** Return true while a repeat seek owns the boundary, before EOF/auto-next handling. */
    private fun maybeRepeatStudy(active: Player, playIntent: Boolean = active.playWhenReady): Boolean {
        syncStudySentenceSource()
        if (studyRepeat.range == null || !studyReady(requireUnlocked = false) || !playIntent || isExoEmbeddedSeekPending(active)) return false
        if (active.playbackState != Player.STATE_READY && active.playbackState != Player.STATE_ENDED) return false
        return when (val decision = studyRepeat.poll(active.currentPosition,
            active.playbackState == Player.STATE_ENDED, SystemClock.elapsedRealtime())) {
            StudyRepeatDecision.Idle -> false
            is StudyRepeatDecision.Seek -> { seekForStudy(decision.positionMs, resume = true); true }
            StudyRepeatDecision.Waiting -> true
            StudyRepeatDecision.Failed -> { toast("반복 위치로 이동하지 못해 반복을 해제했습니다."); false }
        }
    }

    private fun showStudySubtitles() {
        if (exiting || isDestroyed || closeLockedSpoilerPlayback()) return
        if (spec.subtitlesPending && !subtitleListFailed) player?.let(::loadDeferredSubtitles)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun choice(label: String, action: () -> Unit) { labels += label; actions += action }
        choice("원문 자막 선택 · ${selectedTrackLabel(C.TRACK_TYPE_TEXT)}") { showTracks(C.TRACK_TYPE_TEXT) }
        choice("번역 자막 끄기") { studySecondary.clear() }
        spec.subtitles.filterNot { isMpvBitmapSubtitle(it.format ?: it.mimeType) }.forEach { track ->
            choice("번역 · ${subtitleMenuLabel(track.label, track.language, track.format ?: track.mimeType,
                if (track.isEmbedded) "서버 내장" else "서버 외부", track.forced == true, track.sdh == true)}") {
                studySecondary.loadServer(track, subtitleLoader)
            }
        }
        if (spec.subtitlesPending) choice("서버 자막 목록 다시 확인") {
            player?.let(::loadDeferredSubtitles); showStudySubtitles()
        }
        choice("번역 자막 파일 불러오기") {
            val owner = studySecondary.ownerKey
            if (owner == null) toast("현재 영상의 자막 저장 정보를 확인할 수 없습니다.")
            else try {
                studySubtitlePicker.launch(Intent(this, SubtitleImportActivity::class.java)
                    .putExtra(SubtitleImportActivity.EXTRA_OWNER_KEY, owner))
            } catch (_: Exception) { toast("이 기기에서 파일 선택 창을 열 수 없습니다.") }
        }
        choice("저장된 번역 자막 불러오기") { studySecondary.loadLocal() }
        choice("번역 자막 위치 · ${if (studySecondaryAtTop) "위" else "아래"}") {
            studySecondaryAtTop = !studySecondaryAtTop
            getSharedPreferences("gsy_settings", MODE_PRIVATE).edit().putBoolean("study_secondary_top", studySecondaryAtTop).apply()
            applyStudySecondaryStyle(); showStudySubtitles()
        }
        choice("번역 자막 싱크 · ${String.format(Locale.KOREAN, "%+.1f초", studySecondaryOffsetMs / 1000.0)}") {
            AlertDialog.Builder(this).setTitle("번역 자막 싱크")
                .setItems(arrayOf("0.5초 빠르게", "0.5초 느리게", "초기화")) { _, index ->
                    studySecondaryOffsetMs = when (index) {
                        0 -> (studySecondaryOffsetMs + 500).coerceAtMost(60_000)
                        1 -> (studySecondaryOffsetMs - 500).coerceAtLeast(-60_000)
                        else -> 0
                    }
                    updateStudySecondaryText(); showStudySubtitles()
                }.setNegativeButton("닫기", null).show()
        }
        AlertDialog.Builder(this).setTitle("원문·번역 자막")
            .setItems(labels.toTypedArray()) { _, index -> actions[index]() }
            .setNegativeButton("닫기", null).show()
    }

    private fun applyStudySecondaryStyle() {
        if (!::studySecondaryOverlay.isInitialized) return
        studySecondaryOverlay.appearance = subtitleAppearance.copy(verticalWriting = false,
            horizontalOffsetPercent = 0, verticalOffsetPercent = 0,
            horizontalAlignment = SubtitleHorizontalAlignment.CENTER,
            verticalAlignment = if (studySecondaryAtTop) SubtitleVerticalAlignment.TOP else SubtitleVerticalAlignment.BOTTOM)
        studySecondaryOverlay.subtitleTypeface = PlexSubtitleFonts.typeface(this, subtitleFont)
    }

    private fun updateStudySecondaryText() {
        if (!::studySecondaryOverlay.isInitialized) return
        val timeline = studySecondary.timeline
        val canDraw = timeline != null && visible && prepared && player != null && !exiting &&
            studySecondary.ownerKey == languageStudySecondaryOwner(localSubtitleOwnerKey)
        studySecondaryOverlay.visibility = if (canDraw) View.VISIBLE else View.GONE
        if (!canDraw) { studySecondaryOverlay.subtitleText = ""; return }
        val position = if (::gestures.isInitialized) gestures.pendingSeekPositionMs ?: studyPosition() else studyPosition()
        studySecondaryOverlay.subtitleText = timeline?.textAt((position + studySecondaryOffsetMs).coerceAtLeast(0)).orEmpty()
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
        if (!subtitlesEnabled || externalSubtitleId != null || embeddedServerSubtitleId != null || localSubtitleActiveId != null) {
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
        if (!::spec.isInitialized || !::client.isInitialized || spec.sourceItem?.isSpoiler != true) return false
        val shyni = client.shyni ?: return false
        if (shyni.spoilerUnlocked) return false
        if (activityStarted && spoilerRecovery != null && shyni.spoilerPlaybackReauthenticationFailed && !exiting) {
            showSpoilerReauthenticationFailure()
            return true
        }
        if (!exiting) {
            exiting = true
            dismissContinuousLoading()
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
        val shyni = client.shyni ?: return
        val current = player
        val currentSpec = spec
        spoilerReauthenticationJob = scope.launch {
            while (activityStarted && !exiting && player === current && prepared &&
                spec.partId == currentSpec.partId && spec.libraryId == currentSpec.libraryId &&
                spec.viewItemId == currentSpec.viewItemId && spec.sessionId == currentSpec.sessionId) {
                if (refreshing || conversionLoading || navigationLoading || gestures.isSeekGestureActive) { delay(500); continue }
                val remaining = shyni.spoilerRemainingMs
                if (remaining <= 0) { closeLockedSpoilerPlayback(); return@launch }
                val wait = SpoilerPlaybackReauthenticationPolicy.delayMs(remaining, shyni.spoilerLifetimeMs)
                if (wait > 0) { delay(wait); continue }
                reauthenticateSpoilerPlayback()
                return@launch
            }
        }
    }

    private fun reauthenticateSpoilerPlayback() {
        val active = player ?: return
        val shyni = client.shyni ?: return
        if (!activityStarted || exiting || !prepared || refreshing || conversionLoading || navigationLoading ||
            spoilerRotationJob?.isActive == true || spec.sourceItem?.isSpoiler != true) return
        fun snapshot(): ConversionRestore {
            capturePosition()
            return ConversionRestore(specWithCurrentSourceAudioCodec(), lastPosition,
                gestures.playWhenReadyBeforeSeek ?: active.playWhenReady,
                spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) },
                selectedTrackPreference(C.TRACK_TYPE_AUDIO),
                if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null && localSubtitleActiveId == null)
                    selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
                if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
                lastDuration, subtitlesEnabled, subtitleManuallySelected, localSubtitleActiveId)
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
                val flushBudget = SpoilerPlaybackReauthenticationPolicy.flushTimeoutMs(shyni.spoilerRemainingMs)
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
                val budget = SpoilerPlaybackReauthenticationPolicy.timeoutMs(shyni.spoilerRemainingMs)
                if (budget <= 0) throw ApiException("비공개 재생 권한을 다시 확인해야 합니다.", 403)
                val refreshed = withTimeoutOrNull(budget) {
                    shyni.reauthenticateSpoilerPlayback(previous.spec, previous.position, previous.audioIndex)
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
                    if (!shyni.spoilerUnlocked && !shyni.spoilerPlaybackReauthenticationFailed) closeLockedSpoilerPlayback()
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
                        val markers = client.shyni?.playbackMarkers(current, durationMs = 0) ?: current.markers
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
                            // Some home rows also omit showId. Neighbor inspection has now filled
                            // the exact-part cache, so retry once using its verified parent metadata.
                            if (spec.sourceItem?.type == "episode" && spec.sourceItem?.showId == null &&
                                spec.sourceItem?.hasKnownMediaOrigin() == false) {
                                subtitleOriginAttemptedKey = null
                                resolveSubtitleOrigin(readyPlayer)
                            }
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

    /** Home rows can omit country metadata. Resolve it after READY without restarting playback. */
    private fun resolveSubtitleOrigin(readyPlayer: Player) {
        val shyni = client.shyni ?: return
        val item = spec.sourceItem ?: return
        val key = subtitlePlaybackContentKey(spec)
        if (subtitleOriginAttemptedKey != key && subtitleOriginJob?.isActive != true) {
            Log.i("ShyniFlixSubtitle", "origin_probe stage=initial known=${item.hasKnownMediaOrigin()} " +
                "default_off=${!defaultSubtitlesEnabled(spec)} view_present=${spec.viewItemId != null} " +
                "source_view_matches=${item.viewItemId == spec.viewItemId} show_present=${item.showId != null} " +
                "context_present=${!spec.progressToken.isNullOrBlank()} audio_known=${subtitleDefaultAudioLanguage != null} " +
                "audio_default_off=${!defaultSubtitlesEnabled(spec, subtitleDefaultAudioLanguage)} " +
                "enabled=$subtitlesEnabled manual=$subtitleManuallySelected")
        }
        if (item.hasKnownMediaOrigin() || subtitleOriginAttemptedKey == key || subtitleOriginJob?.isActive == true ||
            player !== readyPlayer || !prepared || exiting || isDestroyed || refreshing || conversionLoading ||
            navigationLoading || closeLockedSpoilerPlayback()) return
        val current = spec
        val account = client.token
        val generation = engineGeneration
        fun isCurrent() = player === readyPlayer && engineGeneration == generation && client.token == account &&
            subtitlePlaybackContentKey(spec) == key && !exiting && !isDestroyed && !refreshing &&
            !conversionLoading && !navigationLoading
        subtitleOriginJob = scope.launch {
            try {
                val resolved = shyni.resolvePlaybackOrigin(current) { diagnostic ->
                    Log.i("ShyniFlixSubtitle", "origin_probe $diagnostic")
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || closeLockedSpoilerPlayback()) return@launch
                subtitleOriginAttemptedKey = key
                val source = spec.sourceItem ?: return@launch
                if (resolved == null || source.hasKnownMediaOrigin()) return@launch
                spec = spec.copy(sourceItem = source.copy(productionCountries = resolved.productionCountries,
                    originalLanguage = resolved.originalLanguage))
                val defaultOff = !defaultSubtitlesEnabled(spec, subtitleDefaultAudioLanguage)
                Log.i("ShyniFlixSubtitle", "origin=resolved default_off=$defaultOff manual=$subtitleManuallySelected")
                applyAutomaticSubtitleDefault(readyPlayer)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isCurrent()) subtitleOriginAttemptedKey = key
            }
        }
    }

    /** Missing work metadata may use the actual selected audio, without inventing a country. */
    private fun applyAutomaticSubtitleDefault(active: Player) {
        if (player !== active || exiting || isDestroyed) return
        val selectedAudio = active.currentTracks.groups.firstNotNullOfOrNull { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) null else (0 until group.length)
                .firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat)
        }
        // Empty headers during a decoder change are temporary. An actual unlabelled original
        // track is a new unknown language; conversion output can omit the known source label.
        if (selectedAudio != null && (selectedAudio.language != null || !spec.isServerConversion))
            subtitleDefaultAudioLanguage = selectedAudio.language
        if (subtitleManuallySelected) return
        val enabled = defaultSubtitlesEnabled(spec, subtitleDefaultAudioLanguage)
        if (enabled) {
            if (!subtitleAudioDefaultOff) return
            // A later foreign origin or a newly selected non-Korean audio restores the normal default.
            subtitleAudioDefaultOff = false
            subtitlesEnabled = true
            ensureLocalSubtitleSource(forceReload = true)
            val preferred = preferredSubtitle(spec)
            val hasEmbedded = active.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSupported(it) }
            }
            when {
                preferred != null -> selectServerSubtitle(preferred)
                hasEmbedded || !spec.subtitlesPending -> selectEmbeddedSubtitleFallback()
                else -> active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            }
            if (spec.subtitlesPending) subtitleAutomaticRestoreRevision = subtitleSelectionRevision
            updateSubtitleButton()
            return
        }
        subtitleAudioDefaultOff = defaultSubtitlesEnabled(spec)
        if (!subtitlesEnabled) return
        Log.i("ShyniFlixSubtitle", "default_off=true reason=${if (subtitleAudioDefaultOff) "selected_audio" else "work_origin"}")
        // Invalidate automatic loads; imported files remain selectable and are not deleted.
        subtitlesEnabled = false
        subtitleAutomaticRestoreRevision = null
        clearExternalSubtitle(); externalSubtitleId = null
        pendingTextPreference = null; deferredSubtitlePreference = null
        localSubtitleChoiceRevision++
        localSubtitleReadJob?.cancel(); localSubtitleReadJob = null
        localSubtitleActiveId = null; localSubtitleTimeline = null; restoredLocalSubtitleId = null
        ensureLocalSubtitleSource(forceReload = true)
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        playerView.subtitleView?.setCues(emptyList())
        renderEmbeddedCues(active.currentCues)
        updateSubtitleButton()
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
                val tracks = client.shyni?.playbackSubtitles(current) ?: current.subtitles
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || closeLockedSpoilerPlayback()) return@launch
                spec = spec.copy(subtitles = tracks, subtitlesPending = false)
                val savedId = embeddedServerSubtitleId ?: externalSubtitleId
                val preferred = if (localSubtitleActiveId != null) null else deferredSubtitleSelection(spec, savedId, deferredSubtitlePreference,
                    subtitlesEnabled, subtitleManuallySelected, pendingTextPreference != null,
                    subtitleSelectionRevision == selectionRevision || (!subtitleManuallySelected &&
                        subtitleAutomaticRestoreRevision == subtitleSelectionRevision))
                subtitleAutomaticRestoreRevision = null
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
            if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null && localSubtitleActiveId == null)
                selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
            if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
            lastDuration, subtitlesEnabled, subtitleManuallySelected, localSubtitleActiveId)
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
                var next = client.playback(target).copy(resumeMs = 0, navigationQuery = previous.spec.navigationQuery)
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
        val mode = playbackModeLabel(spec)
        playerView.findViewById<TextView>(R.id.player_mode).apply {
            text = mode
            contentDescription = "재생 방식, $mode"
        }
        playerView.findViewById<Button>(R.id.player_speed).apply {
            text = speedLabel(); contentDescription = "배속 설정, $text"; tooltipText = contentDescription
        }
        playerView.findViewById<Button>(R.id.player_conversion).apply {
            visibility = if (client.shyni != null) View.VISIBLE else View.GONE
            text = playbackModeButtonLabel(spec); contentDescription = "변환 (데이터 절약), 현재 $mode"; tooltipText = contentDescription
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
            if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null && localSubtitleActiveId == null)
                selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
            if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
            lastDuration, subtitlesEnabled, subtitleManuallySelected, localSubtitleActiveId)
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
        val sameSource = subtitlePlaybackContentKey(refreshed) == subtitlePlaybackContentKey(previous.spec)
        subtitlePolicyContentKey = subtitlePlaybackContentKey(refreshed)
        if (!sameSource) {
            subtitleOriginAttemptedKey = null
            subtitleDefaultAudioLanguage = null; subtitleAudioDefaultOff = false
            subtitleAutomaticRestoreRevision = null
        }
        subtitlesEnabled = if (sameSource) previous.subtitleEnabled else defaultSubtitlesEnabled(refreshed)
        subtitleManuallySelected = sameSource && previous.subtitleManual
        restoredLocalSubtitleId = previous.localSubtitle.takeIf { sameSource && subtitlesEnabled }
        clearExternalSubtitle(); externalSubtitleId = null
        deferredSubtitlePreference = previous.subtitle.takeIf { subtitlesEnabled && refreshed.subtitlesPending }
        val nativeText = previous.text.takeIf { subtitlesEnabled && !refreshed.isServerConversion && restoredLocalSubtitleId == null }
        val preferredText = previous.subtitle ?: previous.text?.let { preference ->
            matchingPlaybackTrack(preference, refreshed.subtitles.map { PlaybackTrackPreference(it.language, it.title ?: it.label) })
                ?.let(refreshed.subtitles::get)
        }
        val subtitle = if (!subtitlesEnabled || nativeText != null || restoredLocalSubtitleId != null) null else
            replacementSubtitle(preferredText, refreshed.subtitles) ?: preferredSubtitle(refreshed)
        spec = refreshed.copy(resumeMs = previous.position)
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
        if (continuousPrevious != null) {
            // The old item has already ended: never reopen its tail on auto-next failure.
            continuousPrevious = null
            spec = previous.spec; lastPosition = previous.duration; lastDuration = previous.duration
            completed = true; refreshing = false; conversionLoading = false
            toast("다음 회차를 재생하지 못했습니다.")
            finishCompletedPlaybackNow(alreadyQueued = true)
            return
        }
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
                subtitlePolicyContentKey = subtitlePlaybackContentKey(spec)
                subtitlesEnabled = previous.subtitleEnabled; subtitleManuallySelected = previous.subtitleManual
                restoredLocalSubtitleId = previous.localSubtitle.takeIf { subtitlesEnabled }
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

    private fun pictureSettingsLabel(): String = pictureStore.currentPreset(pictureSettings)?.name ?: "사용자 조절"

    private fun pictureSupportNote(): String =
        "MPV와 ExoPlayer에 같은 보정값을 적용합니다. ExoPlayer에서 처음 보정을 켤 때는 현재 위치에서 화면을 다시 준비합니다. HDR·Dolby Vision 보정은 MPV를 사용하며, 보호된 영상은 보정을 지원하지 않습니다."

    private fun showPictureSettings() {
        if (exiting || isFinishing || isDestroyed) return
        dismissPictureSettings()
        pictureDialog = PictureSettingsDialog(this, television, pictureStore, current = { pictureSettings },
            onApply = { value ->
                if (!exiting && !isDestroyed) {
                    pictureSettings = value.normalized()
                    pictureStore.writeCurrent(pictureSettings)
                    applyPictureToPlayer()
                }
            }, supportNote = ::pictureSupportNote).also { it.show() }
    }

    private fun dismissPictureSettings() {
        val dialog = pictureDialog
        pictureDialog = null
        dialog?.dismiss()
    }

    private fun applyPictureToPlayer() {
        when (val active = player) {
            is MpvPlayer -> active.setPictureSettings(pictureSettings)
            is ExoPlayer -> {
                if (!pictureSettings.isNeutral && handleUnsupportedExoPicture(active)) return
                if (exoPictureBypassContent == subtitlePlaybackContentKey(spec)) return
                val effect = exoPictureEffect.takeIf { exoPictureOwner === active }
                if (effect == null) {
                    if (!pictureSettings.isNeutral) scheduleExoPictureRestart(active)
                    return
                }
                effect.update(pictureSettings)
                exoPictureRedraw?.let(handler::removeCallbacks)
                exoPictureRedraw = null
                // A live matrix updates on the next rendered frame. Paused playback needs the
                // replayable-frame cache; debounce slider input rather than flushing every step.
                if (prepared && active.playbackState == Player.STATE_READY && !active.isPlaying) {
                    val redraw = Runnable {
                        exoPictureRedraw = null
                        if (player === active && exoPictureOwner === active && !exiting && !isDestroyed &&
                            active.playbackState == Player.STATE_READY && !active.isPlaying) {
                            active.setVideoEffects(androidx.media3.common.VideoFrameProcessor.REDRAW)
                        }
                    }
                    exoPictureRedraw = redraw
                    handler.postDelayed(redraw, 80)
                }
            }
        }
    }

    private fun handleUnsupportedExoPicture(active: ExoPlayer): Boolean {
        val reason = exoPictureUnsupportedReason(active.currentTracks) ?: return false
        if (reason == ExoPictureUnsupportedReason.DRM) {
            val key = subtitlePlaybackContentKey(spec)
            if (exoPictureBypassContent != key) {
                exoPictureBypassContent = key
                toast("보호된 영상은 보정을 적용하지 않고 재생합니다.")
            }
            if (exoPictureOwner === active) scheduleExoPictureRestart(active)
            return exoPictureOwner === active
        }
        schedulePictureMpvFallback(active, "HDR·Dolby Vision 영상 보정은 MPV에서 적용합니다.")
        return true
    }

    private fun schedulePictureMpvFallback(active: ExoPlayer, message: String) {
        if (exoPictureRestartPending === active) return
        exoPictureRestartPending = active
        handler.post {
            if (player !== active || exoPictureRestartPending !== active) return@post
            exoPictureRestartPending = null
            if (exiting || isDestroyed || refreshing) return@post
            captureTrackPreferencesForReplacement()
            fallbackFromExo(active)
            toast(message)
        }
    }

    private fun scheduleExoPictureRestart(active: ExoPlayer) {
        if (exoPictureRestartPending === active) return
        exoPictureRestartPending = active
        handler.post {
            if (player !== active || exoPictureRestartPending !== active) return@post
            exoPictureRestartPending = null
            if (exiting || isDestroyed || refreshing || navigationLoading || conversionLoading ||
                closeLockedSpoilerPlayback()) return@post
            val wasPlaying = if (exoValidated && visible) active.playWhenReady else resumeWhenVisible
            lastPosition = gestures.cancelSeekGesture() ?: exoFallbackPosition(lastPosition,
                active.currentPosition, exoValidated, playbackFirstFrameReported)
            pendingSeek = null; finishRemoteSeeking()
            val manual = manualExoInUse
            val hadAudio = manualExoSourceHasAudio
            val header = headerExoInUse
            val chapterCarry = chapterCreditsEvidence.takeIf { chapterCreditsOwner === active }
            val pendingSubtitles = exoRestoreSubtitlePending || spec.subtitlesPending
            captureTrackPreferencesForReplacement()
            settleWatchTime(false)
            prepared = false
            releasePlayer()
            exoRestoreSubtitlePending = pendingSubtitles
            resumeWhenVisible = wasPlaying
            playbackFirstFrameReported = false
            diagnosticStartedAt = SystemClock.elapsedRealtime()
            startPreparedPlayer(null, true, chapterCarry, manualExo = manual,
                sourceHasAudio = hadAudio, headerExo = header)
        }
    }

    private fun watchExoPictureFirstFrame(active: ExoPlayer) {
        if (exoPictureOwner !== active || playbackFirstFrameReported || exoPictureFrameTimeout != null) return
        val check = Runnable {
            exoPictureFrameTimeout = null
            val surface = playerView.videoSurfaceView as? android.view.SurfaceView
            if (player === active && exoPictureOwner === active && visible && !exiting && !isDestroyed &&
                active.playbackState == Player.STATE_READY && !playbackFirstFrameReported &&
                (active.videoSize.width > 0 || exoPictureAspectRatio(active.currentTracks) != null) &&
                surface?.holder?.surface?.isValid == true) {
                schedulePictureMpvFallback(active, "보정 화면을 출력하지 못해 같은 위치에서 MPV로 재생합니다.")
            }
        }
        exoPictureFrameTimeout = check
        handler.postDelayed(check, 8_000)
    }

    private fun applyExoPictureAspectRatio(active: ExoPlayer) {
        if (player !== active || exoPictureOwner !== active) return
        // Media3 1.10.1 deliberately suppresses VideoSize events in its effects path.
        // These color effects do not crop/rotate, so the selected source format supplies
        // the display ratio without changing Media3's first-frame/surface workaround.
        val aspect = active.videoFormat?.let(::exoPictureAspectRatio)
            ?: exoPictureAspectRatio(active.currentTracks) ?: return
        playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.setAspectRatio(aspect)
    }

    private fun captureTrackPreferencesForReplacement() {
        pendingAudioPreference = selectedTrackPreference(C.TRACK_TYPE_AUDIO) ?: pendingAudioPreference
        pendingTextPreference = if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null && localSubtitleActiveId == null)
            selectedTrackPreference(C.TRACK_TYPE_TEXT) ?: pendingTextPreference else null
    }

    private fun restartPlaybackAtCurrentPosition() {
        val active = player
        val settled = playbackEngineSettled(mpvProbePending, active is ExoPlayer && !exoValidated)
        val wasPlaying = gestures.playWhenReadyBeforeSeek ?: if (active != null && settled) active.playWhenReady else resumeWhenVisible
        val seekTarget = gestures.cancelSeekGesture() ?: pendingSeek
        finishRemoteSeeking()
        if (seekTarget != null) lastPosition = seekTarget.coerceAtLeast(0) else capturePosition()
        pendingSeek = null
        captureTrackPreferencesForReplacement()
        if (exoRestoreSubtitlePending && spec.subtitles.isEmpty()) spec = spec.copy(subtitlesPending = true)
        exoRestoreSubtitlePending = false
        settleWatchTime(false)
        preparePlayback(wasPlaying)
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
        val exo = player is ExoPlayer
        // Media3 sizes Exo's video surface for fit/crop/stretch. Scale only that
        // surface for 110/125/150%, never the controls or the subtitle overlay.
        // SurfaceView scaling is synchronized on Android N+ (our minimum is O).
        playerView.resizeMode = if (exo) resizeMode else AspectRatioFrameLayout.RESIZE_MODE_FILL
        (player as? ExoPlayer)?.let(::applyExoPictureAspectRatio)
        playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)?.apply {
            // A zoomed video may extend into its former letterbox; the PlayerView
            // still clips it to the screen, while sibling captions remain unscaled.
            clipChildren = false
            clipToPadding = false
        }
        playerView.clipChildren = true
        playerView.videoSurfaceView?.apply {
            scaleX = if (exo) resizeScale else 1f
            scaleY = if (exo) resizeScale else 1f
        }
        // MPV owns its fit/crop/zoom inside a full-screen Surface. Clear any Exo
        // transform above before a fallback so native zoom is not applied twice.
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
        applyResizeMode()
        saveResizeMode()
        showResizeNotice()
    }

    private fun loadExternalSubtitle(track: SubtitleTrack) {
        if (!subtitlesEnabled || localSubtitleActiveId != null) return
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
            // A reselected in-band cue may have begun in an earlier cluster.
            if (active is ExoPlayer && exoValidated &&
                requiresMpvForEmbeddedTextResume(listOf(track), active.currentPosition)) {
                scheduleEmbeddedTextMpvAfterSeek(active, active.currentPosition)
                return
            }
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                // MPV's native tracks are resolved after load. For a verified Exo
                // source, keep text enabled from prepare so early subtitle samples
                // are not discarded before Media3 publishes the track group.
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, active !is ExoPlayer || !exoEmbeddedSubtitle(track)).build()
            resolvePendingEmbeddedSubtitle()
        } else {
            externalSubtitleId = track.id
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            loadExternalSubtitle(track)
        }
    }

    private fun resolvePendingEmbeddedSubtitle() {
        if (!subtitlesEnabled || localSubtitleActiveId != null) { pendingEmbeddedSubtitle = null; return }
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
            options.single().supported && exoSubtitleMimeMatches(track.format, options.single().originalMimeType, null)) 0
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
        val route = mpvSubtitleController.update(active, subtitlesEnabled, externalSubtitleId != null || localSubtitleActiveId != null,
            cues.cues.any { it.text != null })
        // Bitmap subtitles are drawn by MPV on the video surface. Plain text
        // belongs to the exact Shyni Player horizontal/vertical Canvas renderer.
        playerView.subtitleView?.visibility = View.GONE
        subtitleOverlay.visibility = if (route.showOverlay) View.VISIBLE else View.GONE
        if (!route.showOverlay) subtitleOverlay.subtitleText = ""
        else if (externalSubtitleId == null && localSubtitleActiveId == null) subtitleOverlay.subtitleText =
            cues.cues.mapNotNull { it.text?.toString() }.joinToString("\n\n")
    }

    private fun selectEmbeddedSubtitleFallback() {
        if (!subtitlesEnabled || localSubtitleActiveId != null) { awaitingEmbeddedSubtitle = false; return }
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
        if (subtitlesEnabled && (externalSubtitleId != null || localSubtitleActiveId != null)) {
            val position = gestures.pendingSeekPositionMs ?: player?.currentPosition ?: lastPosition
            val timeline = if (localSubtitleActiveId != null) localSubtitleTimeline else subtitleTimeline
            subtitleOverlay.subtitleText = timeline?.textAt((position + subtitleOffsetMs).coerceAtLeast(0)).orEmpty()
        }
    }

    private fun applySubtitleStyle() {
        subtitleAppearance = subtitleAppearance.copy(
            verticalSpacingPercent = normalizedVerticalSubtitleSpacingPercent(subtitleAppearance.verticalSpacingPercent))
        subtitleFont = PlexSubtitleFonts.normalizedKey(this, subtitleFont)
        val typeface = PlexSubtitleFonts.typeface(this, subtitleFont)
        subtitleOverlay.appearance = subtitleAppearance; subtitleOverlay.subtitleTypeface = typeface
        applyStudySecondaryStyle()
        playerView.subtitleView?.let { applyPlexSubtitleStyle(it, subtitleAppearance, typeface) }
        player?.let { renderEmbeddedCues(it.currentCues) }
        getSharedPreferences("gsy_settings", MODE_PRIVATE).edit().putString("subtitle_font", subtitleFont)
            .putInt("subtitle_size", subtitleAppearance.sizePercent).putInt("subtitle_x", subtitleAppearance.horizontalOffsetPercent)
            .putInt("subtitle_y", subtitleAppearance.verticalOffsetPercent).putBoolean("subtitle_vertical", subtitleAppearance.verticalWriting)
            .putInt("subtitle_vertical_spacing", subtitleAppearance.verticalSpacingPercent)
            .putString("subtitle_horizontal_alignment", subtitleAppearance.horizontalAlignment.name)
            .putString("subtitle_vertical_alignment", subtitleAppearance.verticalAlignment.name).apply()
    }

    private fun showSubtitleAlignment() {
        fun horizontalLabel(value: SubtitleHorizontalAlignment) = when (value) {
            SubtitleHorizontalAlignment.DEFAULT -> "기본"
            SubtitleHorizontalAlignment.LEFT -> "왼쪽"
            SubtitleHorizontalAlignment.CENTER -> "가운데"
            SubtitleHorizontalAlignment.RIGHT -> "오른쪽"
        }
        fun verticalLabel(value: SubtitleVerticalAlignment) = when (value) {
            SubtitleVerticalAlignment.DEFAULT -> "기본"
            SubtitleVerticalAlignment.TOP -> "위"
            SubtitleVerticalAlignment.CENTER -> "가운데"
            SubtitleVerticalAlignment.BOTTOM -> "아래"
        }
        AlertDialog.Builder(this).setTitle("자막 정렬")
            .setItems(arrayOf("가로 정렬 · ${horizontalLabel(subtitleAppearance.horizontalAlignment)}",
                "세로 정렬 · ${verticalLabel(subtitleAppearance.verticalAlignment)}")) { _, axis ->
                if (axis == 0) {
                    val values = SubtitleHorizontalAlignment.values()
                    AlertDialog.Builder(this).setTitle("가로 정렬")
                        .setSingleChoiceItems(values.map(::horizontalLabel).toTypedArray(),
                            values.indexOf(subtitleAppearance.horizontalAlignment)) { choice, selected ->
                            subtitleAppearance = subtitleAppearance.copy(horizontalAlignment = values[selected])
                            applySubtitleStyle(); choice.dismiss()
                        }.setNegativeButton("취소", null).show()
                } else {
                    val values = SubtitleVerticalAlignment.values()
                    AlertDialog.Builder(this).setTitle("세로 정렬")
                        .setSingleChoiceItems(values.map(::verticalLabel).toTypedArray(),
                            values.indexOf(subtitleAppearance.verticalAlignment)) { choice, selected ->
                            subtitleAppearance = subtitleAppearance.copy(verticalAlignment = values[selected])
                            applySubtitleStyle(); choice.dismiss()
                        }.setNegativeButton("취소", null).show()
                }
            }.setNegativeButton("닫기", null).show()
    }

    private fun showSubtitleAppearance() {
        AlertDialog.Builder(this).setTitle("자막 모양")
            .setItems(arrayOf("글꼴", "크기 (${subtitleAppearance.sizePercent}%)", "위치", "세로 쓰기 ${if (subtitleAppearance.verticalWriting) "켜짐" else "꺼짐"}", "색상·테두리",
                "세로 자간 (${subtitleAppearance.verticalSpacingPercent}%)", "정렬")) { _, index ->
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
                    6 -> showSubtitleAlignment()
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
        if (externalSubtitleId == null && localSubtitleActiveId == null) { toast("텍스트 자막을 선택한 뒤 싱크를 조절할 수 있습니다."); return }
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
        if (endingAfterCompletion || (continuousPlaybackGate.isActive() && completed) ||
            !prepared || !::spec.isInitialized || (completed && state != "completed")) return
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
        val snapshot = update.copy(thresholdAttempt = firstThreshold, completionEligible = reached,
            authenticationEpoch = epoch, accountScope = PlaybackCompletionSignals.scope(client.server, client.token),
            privateGrantRevision = if (update.spec.sourceItem?.isSpoiler == true) SpoilerAccessRegistry.changes.value else null,
            // Activity teardown can enqueue the same EOF after a manual menu choice.
            // All writes from this playback retain the choice revision first captured.
            choiceRevision = progressChoiceRevisions.getOrPut(key) { PlaybackCompletionSignals.choiceRevision() })
        fun enqueue(sequence: Long?): Boolean {
            if (!playbackAuthentication.isCurrent(epoch)) {
                update.acknowledgement?.complete(false)
                return false
            }
            if (sequence != null) localCompletionReceipts.add(sequence)
            val accepted = updates.submit(snapshot.copy(localCompletionSequence = sequence),
                important = firstThreshold || update.acknowledgement != null || update.state in setOf("stopped", "completed"),
                supersedes = if (firstThreshold || update.state == "completed") { older ->
                    update.spec.sessionId != null && older.authenticationEpoch == epoch && older.spec.sessionId == update.spec.sessionId &&
                        watchedKey(older.spec) == key
                } else null)
            if (!accepted && sequence != null) localCompletionReceipts.remove(sequence)
            return accepted
        }
        val accepted = if (update.state == "completed" && reached) {
            val item = update.spec.sourceItem?.takeIf { it.id == update.spec.itemId &&
                it.libraryId == update.spec.libraryId && it.viewItemId == update.spec.viewItemId }
            PlaybackCompletionSignals.acceptPending(snapshot.accountScope, item,
                snapshot.privateGrantRevision, snapshot.choiceRevision, ::enqueue)
        } else enqueue(null)
        if (!accepted && firstThreshold) watchedQueued.remove(key)
        // Presence is optional and must never block progress recording or playback.
        if (accepted && ::spec.isInitialized && samePlaybackPresenceSource(update.spec, spec) &&
            (!exiting || update.state in setOf("stopped", "completed"))) {
            presenceLifecycle.update(update.spec, update.position, update.duration, update.state)
        }
        return accepted
    }

    private fun discardLocalCompletion(update: ProgressUpdate) {
        PlaybackCompletionSignals.discardLocal(update.localCompletionSequence)
        update.localCompletionSequence?.let(localCompletionReceipts::remove)
    }

    private fun isProgressChoiceCurrent(update: ProgressUpdate): Boolean =
        PlaybackCompletionSignals.isChoiceCurrent(update.accountScope, update.spec.sourceItem, update.choiceRevision)

    private suspend fun ensureProgressAuthentication(update: ProgressUpdate) {
        currentCoroutineContext().ensureActive()
        if (!playbackAuthentication.isCurrent(update.authenticationEpoch))
            throw kotlinx.coroutines.CancellationException("Playback authentication changed")
        if (!isProgressChoiceCurrent(update))
            throw kotlinx.coroutines.CancellationException("A newer manual watch choice superseded playback")
    }

    private fun finishCompletedPlayback() {
        if (exiting || endingAfterCompletion || isDestroyed) return
        if (player?.let { maybeRepeatStudy(it) } == true) {
            handler.removeCallbacks(ticker); handler.postDelayed(ticker, 50)
            return
        }
        if (continuousPlaybackGate.isActive()) return
        val shouldContinue = getSharedPreferences("gsy_settings", MODE_PRIVATE).getBoolean("continuous_playback", false) &&
            spec.sourceItem?.type == "episode" && visible && activityStarted && player != null && !closeLockedSpoilerPlayback()
        if (shouldContinue && continuousPlaybackGate.begin()) {
            startContinuousPlayback()
            return
        }
        finishCompletedPlaybackNow()
    }

    private fun finishCompletedPlaybackNow(alreadyQueued: Boolean = false) {
        if (exiting || endingAfterCompletion || isDestroyed) return
        capturePosition()
        completionGate().finish()
        endingAfterCompletion = true; exiting = true; completed = true; resumeWhenVisible = false
        dismissContinuousLoading()
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
        if (!alreadyQueued) submitProgress(ProgressUpdate(spec, lastPosition, lastDuration, "completed", watchedMs))
        returnToCaller(spec.sourceItem?.takeIf { it.type == "episode" && it.id == spec.itemId &&
            it.libraryId == spec.libraryId && it.viewItemId == spec.viewItemId }?.toJson())
    }

    /** Complete the old item exactly once, then prepare only an independently verified next episode. */
    private fun startContinuousPlayback() {
        val active = player
        val epoch = playbackAuthentication.captureProgress()
        if (active == null || epoch == null) { continuousPlaybackGate.reset(); finishCompletedPlaybackNow(); return }
        capturePosition(); completionGate().finish(); settleWatchTime(false)
        completed = true
        if (lastDuration > 0) lastPosition = lastDuration
        val previous = ConversionRestore(specWithCurrentSourceAudioCodec(), lastPosition, false,
            spec.subtitles.firstOrNull { it.id == (embeddedServerSubtitleId ?: externalSubtitleId) },
            selectedTrackPreference(C.TRACK_TYPE_AUDIO),
            if (subtitlesEnabled && externalSubtitleId == null && embeddedServerSubtitleId == null && localSubtitleActiveId == null)
                selectedTrackPreference(C.TRACK_TYPE_TEXT) else null,
            if (spec.isServerConversion) spec.selectedAudioStreamIndex else active.selectedAudioStreamIndex() ?: spec.selectedAudioStreamIndex,
            lastDuration, subtitlesEnabled, subtitleManuallySelected, localSubtitleActiveId)
        continuousPrevious = previous
        val completionQueued = submitProgress(ProgressUpdate(previous.spec, lastPosition, lastDuration, "completed", watchedMs))
        if (!completionQueued) {
            // A closed queue or changing authentication means the critical write
            // was not accepted; never begin another episode as if it had been.
            continuousPrevious = null
            continuousPlaybackGate.reset()
            toast("시청 기록을 저장하지 못해 연속재생을 중단했습니다.")
            finishCompletedPlaybackNow()
            return
        }
        val account = client.token
        val currentNeighbors = playbackNeighbors
        handler.removeCallbacks(ticker); cancelPlaybackMetadata(); neighborsJob?.cancel()
        refreshing = true; navigationLoading = true; conversionResumeWhenVisible = true
        active.pause(); playerView.hideController(); hideLockControl(); hideSegmentActions(); updatePlaybackButtons()
        showContinuousLoading()
        neighborsJob = scope.launch {
            var replaced = false
            try {
                val target = continuousPlaybackTarget(true, previous.spec, currentNeighbors)
                    ?: withTimeoutOrNull(8_000) {
                        continuousPlaybackTarget(true, previous.spec, client.playbackNeighbors(previous.spec))
                    }
                if (target == null) return@launch
                if (exiting || isDestroyed || !visible || !activityStarted || player !== active || client.token != account ||
                    !playbackAuthentication.isCurrent(epoch) || !samePlaybackPresenceSource(previous.spec, spec) ||
                    closeLockedSpoilerPlayback()) return@launch
                val next = withTimeoutOrNull(20_000) {
                    var preparedNext = client.playback(target)
                        .copy(resumeMs = 0, navigationQuery = previous.spec.navigationQuery)
                    previous.spec.conversionHeight?.let { height -> preparedNext = client.convertPlayback(preparedNext, height, 0) }
                    preparedNext
                } ?: return@launch
                if (exiting || isDestroyed || !visible || !activityStarted || player !== active || client.token != account ||
                    !playbackAuthentication.isCurrent(epoch) || !samePlaybackPresenceSource(previous.spec, spec) ||
                    closeLockedSpoilerPlayback() ||
                    !getSharedPreferences("gsy_settings", MODE_PRIVATE).getBoolean("continuous_playback", false)) return@launch
                conversionRestore = previous; conversionLoading = true; refreshing = false
                replaced = true
                applyConversionPlayback(next, previous.copy(position = 0, playing = true))
                handler.postDelayed(conversionTimeout, 45_000)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Leaving or rotating the player invalidates this handoff.
            } catch (_: Exception) {
                if (!exiting && !isDestroyed) {
                    if (replaced) restoreConversion("다음 회차를 불러오지 못했습니다.")
                    else toast("다음 회차를 불러오지 못했습니다.")
                }
            } finally {
                navigationLoading = false
                if (!replaced && !exiting && !isDestroyed && player === active && continuousPrevious === previous) {
                    continuousPrevious = null; refreshing = false; conversionResumeWhenVisible = null
                    finishCompletedPlaybackNow(alreadyQueued = true)
                }
                if (!isDestroyed) updatePlaybackButtons()
            }
        }
    }

    private fun returnToCaller(completedEpisode: String? = null) {
        setResult(RESULT_OK, Intent().apply { if (completedEpisode != null) putExtra("completedEpisode", completedEpisode) })
        finish()
    }

    /** A responsive, cancellable overlay while next-episode network work runs asynchronously. */
    private fun showContinuousLoading() {
        if (continuousLoadingOverlay != null || isDestroyed || exiting) return
        val root = playerView.parent as? FrameLayout ?: return
        val overlay = FrameLayout(this).apply {
            tag = "continuous_loading"
            // The current video has ended; consume gestures so a seek cannot race the handoff.
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            setBackgroundColor(0xE51B1B1B.toInt())
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(this).apply {
            text = "다음 회차를 불러오는 중"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), 0, dp(8), 0)
        }
        val cancel = Button(this).apply {
            tag = "continuous_loading_cancel"
            text = "취소"
            contentDescription = "연속재생 취소하고 회차 목록으로 돌아가기"
            setOnClickListener { if (continuousPrevious != null && !exiting) leavePlayer() }
            if (television) {
                applyControlHighlight(this)
                isFocusable = true
                isFocusableInTouchMode = true
            }
        }
        panel.addView(spinner, LinearLayout.LayoutParams(dp(32), dp(32)))
        panel.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        continuousLoadingOverlay = overlay
        if (television) cancel.post {
            if (continuousLoadingOverlay === overlay && !exiting && !isDestroyed) cancel.requestFocus()
        }
    }

    private fun dismissContinuousLoading(restoreFocus: Boolean = false) {
        val overlay = continuousLoadingOverlay ?: return
        continuousLoadingOverlay = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        if (restoreFocus && television && !exiting && !isDestroyed && ::playerView.isInitialized) playerView.requestFocus()
    }

    private fun finishAfterProgress(acknowledgement: CompletableDeferred<Boolean>) {
        if (BuildConfig.IS_PLEX && ::spec.isInitialized) {
            spec.sourceItem?.takeIf { it.playable && it.id == spec.itemId &&
                it.libraryId == spec.libraryId && it.viewItemId == spec.viewItemId }?.let { played ->
                setResult(RESULT_OK, Intent().putExtra("playedItem", played.toJson()))
            }
        }
        scope.launch {
            // Manual Back gives partial progress a bounded chance to commit first.
            withTimeoutOrNull(4_000) { acknowledgement.await() }
            if (!isDestroyed) {
                finish()
            }
        }
    }

    private fun releasePlayer() {
        studyDialog?.dismiss(); studyDialog = null
        studyRepeat.resetPending()
        if (::studySecondaryOverlay.isInitialized) {
            studySecondaryOverlay.subtitleText = ""; studySecondaryOverlay.visibility = View.GONE
        }
        exoPictureRedraw?.let(handler::removeCallbacks); exoPictureRedraw = null
        exoPictureFrameTimeout?.let(handler::removeCallbacks); exoPictureFrameTimeout = null
        exoPictureEffect = null; exoPictureOwner = null; exoPictureRestartPending = null
        chapterCreditsOwner = null
        chapterCreditsEvidence = null
        chapterCreditsRetry?.let(handler::removeCallbacks)
        chapterCreditsRetry = null
        chapterCreditsCaptureGate.reset()
        engineGeneration++
        engineSelectionJob?.cancel(); engineSelectionJob = null
        manualExoSelectionPending = false
        manualExoProbePending = false
        manualExoInUse = false
        manualExoSourceHasAudio = false
        headerExoInUse = false
        mpvHeaderHandoffPending = false
        handler.removeCallbacks(engineSelectionTimeout)
        handler.removeCallbacks(engineProbeTimeout)
        mpvProbeJob?.cancel(); mpvProbeJob = null
        finishMpvProbe = null; mpvProbePending = false
        exoValidated = false
        pendingExoTextSeekTarget = null
        pendingExoTextSeekIntent = null
        committedExoTextSeekIntent = null
        exoRestoreSubtitlePending = false
        dismissTvSubtitlePosition()
        cancelPlaybackMetadata()
        subtitleOriginJob?.cancel(); subtitleOriginJob = null
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
        continuousPrevious?.let { previous ->
            neighborsJob?.cancel(); continuousPrevious = null
            spec = previous.spec; lastPosition = previous.duration; lastDuration = previous.duration
            completed = true
            finishCompletedPlaybackNow(alreadyQueued = true)
            return
        }
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
        studyDialog?.dismiss(); studyDialog = null
        dismissPictureSettings()
        dismissTvSubtitlePosition()
        val interruptedSpoilerRotation = spoilerRecovery != null || spoilerRotationJob?.isActive == true
        activityStarted = false
        cancelSpoilerPlaybackReauthentication()
        if (interruptedSpoilerRotation && ::spec.isInitialized && ::client.isInitialized &&
            spec.sourceItem?.isSpoiler == true) {
            client.shyni?.clearSpoilerGrant()
            closeLockedSpoilerPlayback()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume(); visible = true; hideSystemBars()
        if (closeLockedSpoilerPlayback()) return
        if (endingAfterCompletion || exiting) return
        if (!refreshing && playbackEngineSettled(mpvProbePending, player is ExoPlayer && !exoValidated) &&
            player?.let { !isExoEmbeddedSeekPending(it) && !maybeRepeatStudy(it, resumeWhenVisible) && it.reconcilePlaybackCompletion() } == true) {
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
        studyDialog?.dismiss(); studyDialog = null
        studyRepeat.resetPending()
        if (::studySecondaryOverlay.isInitialized) studySecondaryOverlay.visibility = View.GONE
        diagnosticDialog?.dismiss()
        diagnosticDialog = null
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
        outState.putBoolean("endingAfterCompletion", endingAfterCompletion || continuousPrevious != null)
        if (::spec.isInitialized) {
            capturePosition(); outState.putString("spec", (continuousPrevious?.spec ?: spec).toJson()); outState.putLong("position", lastPosition)
            outState.putLong("duration", lastDuration); outState.putLong("watched", watchedMs); outState.putFloat("speed", speed)
            outState.putStringArrayList("watchedReached", ArrayList(watchedReached + completionGates.filterValues { it.eligible }.keys))
            val desiredPlayback = if (mpvProbePending || (player is ExoPlayer && !exoValidated)) resumeWhenVisible
                else player?.playWhenReady ?: resumeWhenVisible
            outState.putBoolean("playing", conversionResumeWhenVisible ?: if (visible)
                gestures.playWhenReadyBeforeSeek ?: desiredPlayback else resumeWhenVisible)
            outState.putBoolean("subtitles", subtitlesEnabled); outState.putInt("externalSubtitle", externalSubtitleId ?: -1)
            outState.putBoolean("subtitleManuallySelected", subtitleManuallySelected)
            outState.putString("subtitleDefaultAudioLanguage", subtitleDefaultAudioLanguage)
            outState.putBoolean("subtitleAudioDefaultOff", subtitleAudioDefaultOff)
            outState.putString("localSubtitle", localSubtitleActiveId ?: restoredLocalSubtitleId)
            outState.putInt("embeddedSubtitle", embeddedServerSubtitleId ?: -1)
            outState.putLong("subtitleOffset", subtitleOffsetMs)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        studyDialog?.dismiss(); studyDialog = null
        studySecondary.release()
        dismissPictureSettings()
        dismissContinuousLoading()
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
