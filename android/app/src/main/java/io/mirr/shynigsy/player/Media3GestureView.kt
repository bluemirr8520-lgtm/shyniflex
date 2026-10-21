package io.mirr.shynigsy.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.roundToInt

/** Transparent video-surface gestures; the Activity owns the player and controls. */
class Media3GestureView(context: Context) : FrameLayout(context) {
    var locked: Boolean = false
        set(value) {
            if (value && !field) cancelSeekGesture(restorePlayback = true)
            field = value
        }
    var controllerVisible: Boolean = false
    var seekTimeoutMs: Long = 8_000
    var onSeekCommitted: ((Long) -> Unit)? = null
    var onSeekSettled: ((Long) -> Unit)? = null
    var onGestureActive: ((Boolean) -> Unit)? = null
    var onControllerToggle: (() -> Unit)? = null
    var onLockedTap: (() -> Unit)? = null
    var onSeekFailed: ((Long) -> Unit)? = null
    var onSubtitleDragStart: ((Float, Float) -> Boolean)? = null
    var onSubtitleDragged: ((Float, Float) -> Unit)? = null
    var onSubtitleDragEnd: ((Boolean) -> Unit)? = null

    val isSeekGestureActive: Boolean get() = seeking
    val pendingSeekPositionMs: Long? get() = if (seeking) targetPositionMs else null
    val playWhenReadyBeforeSeek: Boolean? get() = if (seeking) originalPlayWhenReady else null
    val isSubtitleGestureActive: Boolean get() = axis == Axis.SUBTITLE

    private var player: Player? = null
    private val activity = context.findActivity()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val queue = LatestSeekQueue()
    private var axis = Axis.NONE
    private var downX = 0f
    private var downY = 0f
    private var pointerId = -1
    private var startPositionMs = 0L
    private var targetPositionMs = 0L
    private var durationMs = 0L
    private var originalPlayWhenReady = false
    private var seeking = false
    private var ending = false
    private var previewStalled = false
    private var frameRenderedForSeek = false
    private var restoreVolume = 0
    private var restoreBrightness = -1f
    private var startBrightness = 0.5f
    private var tapSuppressed = false
    private var seekForwardHint = true
    private var displayedSeekForward = true
    private var stableEdgeInsets = Insets.NONE

    private enum class Axis { NONE, SEEK, BRIGHTNESS, VOLUME, SUBTITLE }

    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(0xFF83E3C5.toInt())
    }
    private val hud = LinearLayout(context).apply {
        tag = LEVEL_HUD_TAG
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        background = GradientDrawable().apply {
            setColor(0xD9142033.toInt())
            cornerRadius = dp(16).toFloat()
        }
        visibility = View.GONE
        addView(caption, LinearLayout.LayoutParams(-1, -2))
        addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(12) })
    }
    private val seekIndicator = TextView(context).apply {
        id = View.generateViewId()
        tag = SEEK_INDICATOR_TAG
        setTextColor(Color.WHITE)
        textSize = 24f
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
    }

    private val hideHud = Runnable {
        if (!seeking) {
            hud.visibility = View.GONE
            seekIndicator.visibility = View.GONE
        }
    }
    private val pump = Runnable { advanceSeekQueue() }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (seeking) schedulePump(60)
        }

        override fun onRenderedFirstFrame() {
            if (seeking && queue.inFlight != null) {
                frameRenderedForSeek = true
                schedulePump(0)
            }
        }
    }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!seeking && !tapSuppressed && axis == Axis.NONE) {
                if (locked) onLockedTap?.invoke() else onControllerToggle?.invoke()
            }
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            if (pointerId >= 0 && !locked && !seeking && !tapSuppressed && axis == Axis.NONE &&
                onSubtitleDragStart?.invoke(event.x, event.y) == true) {
                tapSuppressed = true
                axis = Axis.SUBTITLE
                onGestureActive?.invoke(true)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            val active = player ?: return true
            if (locked || seeking || !canSeek(active)) return true
            tapSuppressed = true
            startPositionMs = active.currentPosition.coerceAtLeast(0)
            durationMs = active.duration
            seekForwardHint = event.x >= width / 2f
            beginSeek(active)
            targetPositionMs = (startPositionMs + if (seekForwardHint) 10_000 else -10_000)
                .coerceIn(0, (durationMs - 1).coerceAtLeast(0))
            showSeekIndicator()
            finishSeek(commit = true)
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = false
        addView(hud, LayoutParams(dp(330), -2, Gravity.CENTER))
        addView(seekIndicator, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.RIGHT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            positionSeekIndicator(insets)
            insets
        }
    }

    fun attachPlayer(player: Player?) {
        cancelSeekGesture()
        this.player?.removeListener(listener)
        this.player = player
        player?.addListener(listener)
    }

    /** Call before Activity pause/release; preserves a committed target, or the drag origin. */
    fun cancelSeekGesture(restorePlayback: Boolean = false): Long? {
        tapSuppressed = true
        hud.visibility = View.GONE
        seekIndicator.visibility = View.GONE
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(cancel) } finally { cancel.recycle() }
        if (!seeking) {
            if (axis == Axis.SUBTITLE) endSubtitleDrag(false)
            resetTouchUi()
            return null
        }
        val retained = if (ending) targetPositionMs else startPositionMs
        if (restorePlayback && player != null) {
            targetPositionMs = retained
            finishSeek(commit = false)
        } else {
            queue.reset()
            removeCallbacks(pump)
            seeking = false
            ending = false
            resetTouchUi()
            onGestureActive?.invoke(false)
        }
        return retained
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // PlayerView's real buttons must remain tappable under this transparent layer.
            if (controllerVisible && (event.y < dp(64) || event.y > height - dp(110))) return false
            if (seeking && ending) return true
            pointerId = event.getPointerId(0)
            downX = event.x
            downY = event.y
            seekForwardHint = downX >= width / 2f
            axis = Axis.NONE
            tapSuppressed = false
            startPositionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0
            durationMs = player?.duration?.coerceAtLeast(0) ?: 0
            restoreVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            restoreBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
            startBrightness = restoreBrightness.takeIf { it >= 0 }
                ?: runCatching { android.provider.Settings.System.getInt(context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
            removeCallbacks(hideHud)
            hud.visibility = View.GONE
            seekIndicator.visibility = View.GONE
        }
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            detector.onTouchEvent(event)
            cancelTouch()
            return true
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return true
        val deltaX = event.getX(index) - downX
        val deltaY = event.getY(index) - downY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!locked && !ending) {
                if (axis == Axis.NONE) {
                    if (SeekGestureMath.horizontal(deltaX, deltaY, touchSlop) && player?.let(::canSeek) == true) {
                        axis = Axis.SEEK
                        beginSeek(player!!)
                    } else if (SeekGestureMath.vertical(deltaX, deltaY, touchSlop)) {
                        axis = if (downX < width / 2f) Axis.BRIGHTNESS else Axis.VOLUME
                        onGestureActive?.invoke(true)
                    }
                }
                when (axis) {
                    Axis.SEEK -> {
                        targetPositionMs = SeekGestureMath.position(startPositionMs, deltaX, width, durationMs)
                        if (deltaX != 0f) seekForwardHint = deltaX > 0f
                        showSeekIndicator()
                        if (!previewStalled) {
                            queue.offer(targetPositionMs)
                            schedulePump(0)
                        }
                    }
                    Axis.BRIGHTNESS -> updateBrightness(deltaY)
                    Axis.VOLUME -> updateVolume(deltaY)
                    Axis.SUBTITLE -> onSubtitleDragged?.invoke(deltaX, deltaY)
                    Axis.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                if (axis == Axis.SEEK && seeking && !ending) finishSeek(commit = true)
                else if (axis == Axis.SUBTITLE) {
                    onSubtitleDragged?.invoke(deltaX, deltaY)
                    endSubtitleDrag(true)
                } else if (axis != Axis.NONE) {
                    onGestureActive?.invoke(false)
                    postDelayed(hideHud, 700)
                }
                pointerId = -1
            }
        }
        detector.onTouchEvent(event)
        return true
    }

    private fun beginSeek(active: Player) {
        queue.reset()
        originalPlayWhenReady = active.playWhenReady
        active.playWhenReady = false
        seeking = true
        ending = false
        previewStalled = false
        targetPositionMs = startPositionMs
        onGestureActive?.invoke(true)
    }

    private fun finishSeek(commit: Boolean) {
        if (!seeking) return
        ending = true
        if (commit) onSeekCommitted?.invoke(targetPositionMs)
        if (previewStalled) {
            failSeek()
            return
        }
        queue.offer(targetPositionMs, final = true)
        schedulePump(0)
    }

    private fun schedulePump(delayMs: Long) {
        removeCallbacks(pump)
        postDelayed(pump, delayMs)
    }

    private fun advanceSeekQueue() {
        val active = player ?: return
        if (!seeking) return
        val now = SystemClock.elapsedRealtime()
        val inFlight = queue.inFlight
        if (inFlight != null) {
            if (active.playerError != null || now - queue.sentAtMs >= seekTimeoutMs) {
                previewStalled = true
                removeCallbacks(pump)
                if (ending) failSeek()
                return
            }
            // Position discontinuity is optimistic. Video preview also awaits an actual frame.
            val hasSelectedVideo = active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            if (now - queue.sentAtMs >= 60 && active.playbackState == Player.STATE_READY &&
                (!hasSelectedVideo || frameRenderedForSeek) &&
                abs(active.currentPosition - inFlight.positionMs) <= 1_000) {
                val completed = queue.complete()!!
                if (completed.final) {
                    settleSeek(completed.positionMs)
                    return
                }
            }
        }
        queue.poll(now)?.let { request ->
            if (active.playbackState == Player.STATE_READY && abs(active.currentPosition - request.positionMs) <= 25) {
                queue.complete()
                if (request.final) {
                    settleSeek(request.positionMs)
                    return
                }
            } else {
                frameRenderedForSeek = false
                try { active.seekTo(request.positionMs) } catch (_: IllegalStateException) {
                    failSeek()
                    return
                }
            }
        }
        if (queue.inFlight != null || queue.pending != null) schedulePump(60)
    }

    private fun settleSeek(positionMs: Long) {
        val active = player
        queue.reset()
        seeking = false
        ending = false
        axis = Axis.NONE
        removeCallbacks(pump)
        active?.playWhenReady = originalPlayWhenReady
        onSeekSettled?.invoke(positionMs)
        onGestureActive?.invoke(false)
        postDelayed(hideHud, 600)
    }

    private fun failSeek() {
        val target = targetPositionMs
        val failed = onSeekFailed
        cancelSeekGesture()
        if (failed != null) failed(target)
        else player?.playWhenReady = originalPlayWhenReady
    }

    private fun cancelTouch() {
        hud.visibility = View.GONE
        seekIndicator.visibility = View.GONE
        when (axis) {
            Axis.SEEK -> {
                targetPositionMs = startPositionMs
                finishSeek(commit = false)
            }
            Axis.VOLUME -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0)
            Axis.BRIGHTNESS -> activity?.window?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = restoreBrightness }
            }
            Axis.SUBTITLE -> endSubtitleDrag(false)
            Axis.NONE -> Unit
        }
        pointerId = -1
        if (!seeking) {
            onGestureActive?.invoke(false)
            resetTouchUi()
        }
    }

    private fun endSubtitleDrag(commit: Boolean) {
        axis = Axis.NONE
        onSubtitleDragEnd?.invoke(commit)
        onGestureActive?.invoke(false)
    }

    private fun showSeekIndicator() {
        val feedback = seekSkipFeedback(startPositionMs, targetPositionMs, seekForwardHint)
        seekIndicator.text = feedback.text
        seekIndicator.contentDescription = feedback.description
        displayedSeekForward = feedback.forward
        positionSeekIndicator()
        hud.visibility = View.GONE
        seekIndicator.visibility = View.VISIBLE
    }

    private fun positionSeekIndicator(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(this)) {
        if (insets != null) stableEdgeInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val gravity = Gravity.CENTER_VERTICAL or (if (displayedSeekForward) Gravity.RIGHT else Gravity.LEFT)
        val left = dp(24) + stableEdgeInsets.left
        val right = dp(24) + stableEdgeInsets.right
        val current = seekIndicator.layoutParams as LayoutParams
        if (current.gravity != gravity || current.leftMargin != left || current.rightMargin != right) {
            seekIndicator.layoutParams = current.apply {
                this.gravity = gravity
                leftMargin = left
                rightMargin = right
            }
        }
    }

    private fun updateVolume(deltaY: Float) {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (restoreVolume - deltaY / height.coerceAtLeast(1) * maximum * 2).roundToInt().coerceIn(0, maximum)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        showLevel("볼륨", target * 100 / maximum)
    }

    private fun updateBrightness(deltaY: Float) {
        val target = (startBrightness - deltaY / height.coerceAtLeast(1)).coerceIn(0.02f, 1f)
        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = target } }
        showLevel("밝기", (target * 100).roundToInt())
    }

    private fun showLevel(label: String, percent: Int) {
        seekIndicator.visibility = View.GONE
        caption.text = "$label $percent%"
        progress.visibility = View.VISIBLE
        progress.progress = percent.coerceIn(0, 100) * 10
        hud.visibility = View.VISIBLE
    }

    private fun resetTouchUi() {
        axis = Axis.NONE
        pointerId = -1
        removeCallbacks(hideHud)
        hud.visibility = View.GONE
        seekIndicator.visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        positionSeekIndicator()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        cancelSeekGesture()
        player?.removeListener(listener)
        player = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        positionSeekIndicator()
    }

    private fun canSeek(player: Player): Boolean = player.duration > 0 && player.isCurrentMediaItemSeekable &&
        player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    companion object {
        const val SEEK_INDICATOR_TAG = "gesture_seek_side_indicator"
        const val LEVEL_HUD_TAG = "gesture_level_hud"
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.findActivity()
    else -> null
}
