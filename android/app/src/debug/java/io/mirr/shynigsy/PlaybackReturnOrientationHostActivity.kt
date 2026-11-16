package io.mirr.shynigsy

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated instrumentation host; not compiled into any release APK. */
class PlaybackReturnOrientationHostActivity : ComponentActivity() {
    private val returnOrientation by lazy { PlaybackReturnOrientation(this) }
    private var returned = false
    private lateinit var label: TextView
    private val playback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        returnOrientation.playbackReturned()
        returned = true
        PlaybackReturnOrientationProbe.resultCode.set(result.resultCode)
        label.invalidate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnOrientation.restore(savedInstanceState)
        returned = savedInstanceState?.getBoolean("qa_returned") == true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        label = TextView(this).apply {
            text = "화면 방향 복원 검사"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(28, 48, 64))
            textSize = 22f
            gravity = Gravity.CENTER
        }
        setContentView(label)
        label.viewTreeObserver.addOnPreDrawListener {
            if (returned && window.decorView.hasWindowFocus()) {
                PlaybackReturnOrientationProbe.firstVisibleReturn.compareAndSet(null, frame())
            }
            true
        }
    }

    fun launchPlayback(intent: Intent) {
        returned = false
        returnOrientation.beforePlayback()
        PlaybackReturnOrientationProbe.beforeLaunch.set(frame())
        try { playback.launch(intent) }
        catch (failure: RuntimeException) { returnOrientation.launchFailed(); throw failure }
    }

    /** Saves the same pending orientation state as the real launcher, without another Activity. */
    fun beginPendingPlayback() = returnOrientation.beforePlayback()

    fun restoreFromSimulatedReturn() {
        returnOrientation.playbackReturned()
        returned = true
        label.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun frame() = PlaybackReturnOrientationFrame(resources.configuration.orientation,
        windowManager.defaultDisplay.rotation, requestedOrientation)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::label.isInitialized) label.invalidate()
    }

    override fun onResume() { super.onResume(); returnOrientation.onResume() }
    override fun onPause() { returnOrientation.onPause(); super.onPause() }
    override fun onStop() { returnOrientation.onStop(isChangingConfigurations); super.onStop() }
    override fun onDestroy() { returnOrientation.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("qa_returned", returned)
        returnOrientation.save(outState)
        super.onSaveInstanceState(outState)
    }
}

data class PlaybackReturnOrientationFrame(val configuration: Int, val displayRotation: Int, val requested: Int)

object PlaybackReturnOrientationProbe {
    val beforeLaunch = AtomicReference<PlaybackReturnOrientationFrame?>()
    val firstVisibleReturn = AtomicReference<PlaybackReturnOrientationFrame?>()
    val resultCode = AtomicInteger(Int.MIN_VALUE)
    fun reset() { beforeLaunch.set(null); firstVisibleReturn.set(null); resultCode.set(Int.MIN_VALUE) }
}
