package io.mirr.shynigsy

import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import io.mirr.shynigsy.ui.ShyniApp
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    private var lastCompletionRevision = 0L
    private val playback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra("return_home", false) == true)
            model.returnHomeAfterPlayback()
        else model.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        // Artwork cannot redirect authenticated image requests to another origin.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components {
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
                }))
            }.build()
        }
        model = ViewModelProvider(this)[AppViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackCompletionSignals.changes.combine(model.state) { revision, state -> revision to state }
                    .collect { (revision, state) ->
                    if (revision > lastCompletionRevision && state.loggedIn && !state.busy && !state.spoilerBusy) {
                        lastCompletionRevision = revision
                        model.refresh()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state.spoilerUnlocked || state.spoilerPromptOpen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.decorView.postOnAnimation {
                        window.decorView.postOnAnimation {
                            val current = model.state.value
                            if (!current.spoilerUnlocked && !current.spoilerPromptOpen)
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
            }
        }
        setContent {
            ShyniApp(model) { spec ->
                playback.launch(Intent(this, NativePlayerActivity::class.java)
                    .putExtra("spec", spec.toJson())
                    .putExtra("server", model.state.value.server))
            }
        }
    }
}
