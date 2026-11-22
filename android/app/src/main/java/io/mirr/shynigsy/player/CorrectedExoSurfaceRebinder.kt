package io.mirr.shynigsy.player

import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Reconnect a corrected Exo output after its SurfaceView dimensions have settled. */
@UnstableApi
internal class CorrectedExoSurfaceRebinder(
    private val surfaceView: SurfaceView,
    private val owner: ExoPlayer,
    private val isCurrentOwner: () -> Boolean,
) : AutoCloseable {
    private data class Dimensions(val width: Int, val height: Int)

    private var candidateSize: Dimensions? = null
    private var lastReboundSize: Dimensions? = null
    private var pendingSize: Dimensions? = null
    private var surfaceCreationSerial = 0
    private var previousCreationSerial = -1
    private var remainingChecks = 0
    private var rebinding = false
    private var closed = false
    private val checkLayout = Runnable { checkStableLayout() }
    private val finishRebound = Runnable { finishWhenSurfaceReady(timedOut = false) }
    private val finishTimeout = Runnable { finishWhenSurfaceReady(timedOut = true) }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!rebinding) requestForCurrentLayout()
    }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceCreationSerial++
            requestForCurrentLayout()
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            requestForCurrentLayout()
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    init {
        checkMainThread()
        surfaceView.addOnLayoutChangeListener(layoutListener)
        surfaceView.holder.addCallback(surfaceCallback)
    }

    fun requestForCurrentLayout() {
        checkMainThread()
        if (closed || !isCurrentOwner()) return
        if (pendingSize != null) {
            surfaceView.removeCallbacks(finishRebound)
            surfaceView.post(finishRebound)
        } else if (!rebinding) {
            remainingChecks = 8
            scheduleCheck()
        }
    }

    private fun scheduleCheck() {
        if (closed || remainingChecks <= 0 || !surfaceView.isAttachedToWindow) return
        surfaceView.removeCallbacks(checkLayout)
        surfaceView.postOnAnimation(checkLayout)
    }

    private fun checkStableLayout() {
        if (closed || rebinding || pendingSize != null || !isCurrentOwner() ||
            !surfaceView.isAttachedToWindow) return
        remainingChecks--
        val parent = surfaceView.parent as? ViewGroup ?: return
        val size = Dimensions(surfaceView.width, surfaceView.height)
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        if (size.width <= 0 || size.height <= 0 || !holder.surface.isValid) return
        if (parent.isInLayout || surfaceView.isLayoutRequested ||
            frame.width() != size.width || frame.height() != size.height) {
            candidateSize = null
            scheduleCheck()
            return
        }
        if (size == lastReboundSize) return
        // Two animation callbacks must agree. Never detach from an onLayout callback,
        // and stop polling after a bounded number of frames until another layout event.
        if (candidateSize != size) {
            candidateSize = size
            scheduleCheck()
            return
        }
        val index = parent.indexOfChild(surfaceView)
        if (index < 0) return
        val params = surfaceView.layoutParams
        previousCreationSerial = surfaceCreationSerial
        pendingSize = size
        lastReboundSize = size
        candidateSize = null
        rebinding = true
        try {
            // A new BufferQueue is necessary on affected devices; recreating only its
            // EGL producer can leave the old buffer dimensions. Media3's existing
            // holder callbacks detach/attach output without replacing the player.
            parent.removeView(surfaceView)
        } finally {
            try {
                if (surfaceView.parent == null) {
                    parent.addView(surfaceView, index.coerceAtMost(parent.childCount), params)
                }
            } finally {
                rebinding = false
            }
        }
        if (!closed) {
            surfaceView.postDelayed(finishTimeout, 500)
            requestForCurrentLayout()
        }
    }

    private fun finishWhenSurfaceReady(timedOut: Boolean) {
        val expectedSize = pendingSize ?: return
        if (closed || rebinding) return
        if (!isCurrentOwner()) { close(); return }
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        val newSurfaceReady = surfaceView.isAttachedToWindow && holder.surface.isValid &&
            surfaceCreationSerial != previousCreationSerial
        if (!newSurfaceReady && !timedOut) return
        surfaceView.removeCallbacks(finishTimeout)
        surfaceView.removeCallbacks(finishRebound)
        if (!newSurfaceReady) {
            // No polling or repeated detaches. Keep the pending completion so a late
            // surfaceCreated can still redraw paused playback; close cancels it.
            return
        }
        if (frame.width() != expectedSize.width || frame.height() != expectedSize.height) {
            // The holder can lag one layout behind the View. Keep the pending paused
            // redraw until surfaceChanged confirms it, unless the View changed again.
            if (surfaceView.width == expectedSize.width && surfaceView.height == expectedSize.height) return
            pendingSize = null
            candidateSize = null
            requestForCurrentLayout()
            return
        }
        pendingSize = null
        if (owner.playbackState == Player.STATE_READY && !owner.isPlaying) {
            // Holder callbacks have queued the new output before this posted task.
            // Replay the cached paused frame without seeking or changing effect values.
            owner.setVideoEffects(VideoFrameProcessor.REDRAW)
        }
        requestForCurrentLayout()
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        pendingSize = null
        surfaceView.removeCallbacks(checkLayout)
        surfaceView.removeCallbacks(finishRebound)
        surfaceView.removeCallbacks(finishTimeout)
        surfaceView.removeOnLayoutChangeListener(layoutListener)
        surfaceView.holder.removeCallback(surfaceCallback)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }
}
