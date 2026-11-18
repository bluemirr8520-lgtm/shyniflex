package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.data.PlaybackSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Only an opaque digest is persisted; tickets and account identity never leave the app. */
internal fun localSubtitleOwner(server: String, token: String?, spec: PlaybackSpec): String? {
    val origin = server.trim().toHttpUrlOrNull() ?: return null
    if (origin.username.isNotEmpty() || origin.password.isNotEmpty() || origin.encodedPath != "/" ||
        origin.query != null || origin.fragment != null || token.isNullOrBlank()) return null
    val parts = listOf("shyniflex-local-subtitle-v1", origin.toString(), token, spec.libraryId.toString()) +
        if (spec.partId > 0) listOf("part", spec.partId.toString())
        else listOf("fallback", spec.itemId.toString(), spec.viewItemId.toString(), spec.url)
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
