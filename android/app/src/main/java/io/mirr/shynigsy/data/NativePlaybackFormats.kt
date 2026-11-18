package io.mirr.shynigsy.data

import java.util.Locale

/** Route known native formats before an Exo subtitle/header probe. Unknown stays on the normal policy. */
internal fun sourceNeedsNativeEngine(container: String?, videoCodec: String?, audioCodec: String?): Boolean {
    fun normalized(value: String?) = value?.trim()?.lowercase(Locale.ROOT)
    val containers = normalized(container)?.split(',')?.map(String::trim).orEmpty()
    val nativeContainers = setOf("wmv", "asf", "video/x-ms-wmv", "video/x-ms-asf", "avi", "flv",
        "rm", "rmvb", "realmedia", "ogg", "ogv", "mpeg", "mpg", "mpegts", "mpeg-ts", "ts", "m2ts", "mts", "vob")
    return containers.any { it in nativeContainers } ||
        normalized(videoCodec) in setOf("wmv1", "wmv2", "wmv3", "wmv3image", "vc1", "vc-1", "vc1image", "rv10", "rv20", "rv30", "rv40") ||
        normalized(audioCodec) in setOf("wmav1", "wmav2", "wmapro", "wmalossless", "wmavoice")
}

/** Only replace compatibility remux/audio conversion; retain explicit video conversion and policy limits. */
internal fun preferNativeOriginal(mode: String?, container: String?, videoCodec: String?, audioCodec: String?): Boolean =
    mode in setOf("remux", "audio") && sourceNeedsNativeEngine(container, videoCodec, audioCodec)
