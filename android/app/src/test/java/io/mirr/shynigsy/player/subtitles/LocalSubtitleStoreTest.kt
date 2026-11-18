package io.mirr.shynigsy.player.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class LocalSubtitleStoreTest {
    @Test fun importsSupportedTextBodiesWithoutDependingOnTheirNames() {
        val inputs = listOf(
            "1\n00:00:01,000 --> 00:00:02,000\nHello\n",
            "WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n",
            "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello\n",
            "<SAMI><BODY><SYNC Start=1000><P>Hello<SYNC Start=2000><P>End</BODY></SAMI>",
            "<tt><body><p begin=\"1s\" end=\"2s\">Hello</p></body></tt>",
        )
        for (input in inputs) assertTrue(parseLocalSubtitle(input.toByteArray()).any { it.text.contains("Hello") })
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + inputs.first().toByteArray(Charsets.UTF_16LE)
        assertEquals("Hello", parseLocalSubtitle(utf16).single().text)
    }

    @Test fun rejectsEmptyCompressedImageAndBinaryContent() {
        for (bytes in listOf(byteArrayOf(), "PK\u0003\u0004".toByteArray(),
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), byteArrayOf(0xff.toByte(), 0xd8.toByte()),
            "GIF89a".toByteArray(), "hello world".toByteArray())) {
            assertTrue(runCatching { parseLocalSubtitle(bytes) }.exceptionOrNull() is LocalSubtitleException)
        }
    }

    @Test fun neverTreatsPartialOrOversizedReadAsSuccess() = runBlocking {
        val failAfterSomeBytes = object : InputStream() {
            private var first = true
            override fun read(): Int = throw IOException("read failed")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw IOException("read failed")
                first = false; bytes[offset] = 49; return 1
            }
        }
        assertTrue(runCatching { readLocalSubtitleBytes(failAfterSomeBytes) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { readLocalSubtitleBytes(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1))) }
            .exceptionOrNull() is LocalSubtitleException)
    }

    @Test fun labelsAreBoundedAndCannotContainPathsOrControls() {
        assertEquals("subtitle.srt", sanitizeSubtitleLabel("../folder/\u202Esubtitle.srt\n"))
        assertEquals("외부 자막", sanitizeSubtitleLabel("\u0000\n"))
        assertTrue(sanitizeSubtitleLabel("x".repeat(500)).length <= 80)
        assertTrue(runCatching { validateOwner("../x") }.isFailure)
        validateOwner("a".repeat(64))
    }
}
