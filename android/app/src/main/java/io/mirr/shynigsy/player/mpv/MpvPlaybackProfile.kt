package io.mirr.shynigsy.player.mpv

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import java.util.Locale

/** A bounded demuxer budget, separate from decoder/GPU buffers and the Java heap. */
data class MpvPlaybackProfile(
    val hardwareDecoder: String,
    val hardwareCodecs: List<String>,
    val forwardCacheMiB: Int,
    val backCacheMiB: Int,
    val cacheSeconds: Int,
    val softwareThreads: Int
) {
    fun mpvOptions(): Map<String, String> = linkedMapOf(
        "hwdec" to hardwareDecoder,
        "hwdec-software-fallback" to "3",
        "vd-lavc-check-hw-profile" to "yes",
        "vd-lavc-threads" to softwareThreads.toString(),
        "cache" to "auto",
        "cache-secs" to cacheSeconds.toString(),
        "cache-on-disk" to "no",
        "cache-pause" to "yes",
        "cache-pause-wait" to "1",
        "cache-pause-initial" to "no",
        "demuxer-readahead-secs" to "1",
        "demuxer-max-bytes" to "${forwardCacheMiB}MiB",
        "demuxer-max-back-bytes" to "${backCacheMiB}MiB",
        // Keep refilling while the bounded cache has space. Waiting until only a
        // quarter remains leaves too little headroom for high-bitrate network video.
        "demuxer-hysteresis-bytes" to "0",
        "video-sync" to "audio",
        "framedrop" to "vo",
        "interpolation" to "no"
    ).apply {
        if (hardwareCodecs.isNotEmpty()) put("hwdec-codecs", hardwareCodecs.joinToString(","))
    }

    data class Device(
        val sdk: Int,
        val memoryClassMiB: Int,
        val totalMemoryMiB: Long,
        val availableMemoryMiB: Long,
        val lowRam: Boolean,
        val memoryPressure: Boolean,
        val logicalCores: Int,
        val hardwareVideoMimeTypes: Set<String>
    )

    companion object {
        /** An explicit zero start makes mpv perform an unnecessary initial seek. */
        internal fun initialStartOption(sourcePositionMs: Long, serverConversion: Boolean): String =
            if (serverConversion || sourcePositionMs <= 0) "none"
            else String.format(Locale.ROOT, "%.3f", sourcePositionMs / 1000.0)

        private val knownCodecs = linkedMapOf(
            "video/avc" to "h264", "video/hevc" to "hevc",
            "video/x-vnd.on2.vp8" to "vp8", "video/x-vnd.on2.vp9" to "vp9",
            "video/av01" to "av1"
        )

        /** Pure policy: no Android calls, so constrained/unknown devices can be tested. */
        fun choose(device: Device, preferSoftwareDecode: Boolean = false): MpvPlaybackProfile {
            val compact = device.lowRam || device.memoryPressure || device.memoryClassMiB <= 128 ||
                device.totalMemoryMiB in 1..2048
            val roomy = !compact && device.sdk >= 29 && device.memoryClassMiB >= 384 && device.totalMemoryMiB >= 6144
            val tierBudget = if (compact) 20 else if (roomy) 120 else 60
            val heapBudget = (device.memoryClassMiB.coerceIn(16, 1024) / 4).coerceAtLeast(4)
            val availableBudget = (device.availableMemoryMiB.coerceAtLeast(0) / 16).coerceAtMost(120).toInt().coerceAtLeast(4)
            val budget = minOf(tierBudget, heapBudget, availableBudget)
            val back = (budget / 5).coerceAtLeast(1)
            val hardwareCodecs = if (preferSoftwareDecode) emptyList() else knownCodecs.filter { (mime, _) ->
                mime in device.hardwareVideoMimeTypes && (mime != "video/av01" || device.sdk >= 29)
            }.values.toList()
            return MpvPlaybackProfile(
                // The native manual documents Android direct MediaCodec RGB/10-bit loss.
                // Copy mode preserves the normal GPU color path and authored subtitle rendering.
                hardwareDecoder = if (hardwareCodecs.isEmpty()) "no" else "mediacodec-copy",
                hardwareCodecs = hardwareCodecs,
                forwardCacheMiB = budget - back,
                backCacheMiB = back,
                cacheSeconds = if (compact) 20 else if (roomy) 45 else 30,
                softwareThreads = device.logicalCores.coerceIn(1, if (compact) 4 else 8)
            )
        }

        fun forDevice(context: Context, preferSoftwareDecode: Boolean = false): MpvPlaybackProfile {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = ActivityManager.MemoryInfo()
            val memoryKnown = runCatching { manager?.getMemoryInfo(memory); manager != null }.getOrDefault(false)
            return choose(Device(
                sdk = Build.VERSION.SDK_INT,
                memoryClassMiB = manager?.memoryClass ?: 128,
                totalMemoryMiB = if (memoryKnown) memory.totalMem / MIB else 0,
                availableMemoryMiB = if (memoryKnown) memory.availMem / MIB else 256,
                lowRam = manager?.isLowRamDevice ?: true,
                memoryPressure = memoryKnown && memory.lowMemory,
                logicalCores = Runtime.getRuntime().availableProcessors(),
                hardwareVideoMimeTypes = hardwareMimeTypes
            ), preferSoftwareDecode)
        }

        // Capabilities do not change while this process is alive. Query once, without
        // using MediaCodec's software components as evidence of hardware acceleration.
        private val hardwareMimeTypes: Set<String> by lazy {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { !it.isEncoder }
                    .filter { info ->
                        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
                        else {
                            val name = info.name.lowercase(Locale.ROOT)
                            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
                                !name.startsWith("c2.google.") && !name.startsWith("omx.ffmpeg.") &&
                                !name.startsWith("c2.ffmpeg.") && !name.contains(".software") &&
                                !name.contains(".sw.") && !name.endsWith(".sw")
                        }
                    }.flatMap { it.supportedTypes.toList() }
                    .map { it.lowercase(Locale.ROOT) }.toSet()
            }.getOrDefault(emptySet())
        }
        private const val MIB = 1024L * 1024L
    }
}
