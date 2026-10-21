package io.mirr.shynigsy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.mpv.MpvPlayer
import `is`.xyz.mpv.MPVLib
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MpvEngineOptionsTest {
    @Test fun optimizedOptionsTakeEffectAndModernDecodersAreActuallyBundled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val player = MpvPlayer(context).apply { volume = 0f }
            try {
                val threads = MPVLib.getPropertyInt("options/vd-lavc-threads") ?: 0
                val forward = MPVLib.getPropertyInt("options/demuxer-max-bytes") ?: 0
                val backward = MPVLib.getPropertyInt("options/demuxer-max-back-bytes") ?: 0
                assertTrue("The effective software thread count must be bounded", threads in 1..8)
                assertTrue("Actual cache budget must be positive and at most 120 MiB", forward > 0 && backward > 0 && forward.toLong() + backward <= 120L * 1024 * 1024)
                assertEquals("Automatic decoder fallback must be configured", 3, MPVLib.getPropertyInt("options/hwdec-software-fallback"))
                val count = (MPVLib.getPropertyInt("decoder-list/count") ?: 0).coerceIn(0, 4096)
                val codecs = (0 until count).mapNotNull { MPVLib.getPropertyString("decoder-list/$it/codec") }.toSet()
                for (codec in listOf("h264", "hevc", "av1", "vp9", "vvc"))
                    assertTrue("The bundled FFmpeg must include the $codec decoder", codec in codecs)
                File(context.getExternalFilesDir(null), "mpv-codec-inventory.txt").writeText(
                    "MPV: ${MPVLib.getPropertyString("mpv-version")}\nThreads: $threads\nForward cache: $forward\nBack cache: $backward\nDecoders:\n" + codecs.sorted().joinToString("\n"))
            } finally { player.release() }
        }
    }
}
