package io.mirr.shynigsy.player

/** libmpv's bundled FFmpeg supplies software fallback beyond Android MediaCodec. */
object PlaybackCapabilities {
    fun audioCodecs(): List<String> = listOf(
        "aac", "mp3", "ac3", "eac3", "dts", "truehd", "flac", "alac",
        "opus", "vorbis", "pcm_s16le", "pcm_s24le", "pcm_s32le", "wmapro",
    )
}
