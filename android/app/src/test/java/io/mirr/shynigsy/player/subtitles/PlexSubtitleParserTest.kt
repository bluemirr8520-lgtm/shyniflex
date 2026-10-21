package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

class PlexSubtitleParserTest {
    @Test fun parsesServerWebVttAndClearsAfterCueEnd() {
        val cues = parseManualSubtitleCues("WEBVTT\n\n00:01.200 --> 00:02.500\n안녕하세요\n")
        assertEquals(listOf(ManualSubtitleCue(1200, 2500, "안녕하세요")), cues)
        val timeline = SubtitleTimeline(cues)
        assertEquals("안녕하세요", timeline.textAt(1200))
        assertEquals("", timeline.textAt(2500))
    }

    @Test fun parsesSrtWithoutBlankSeparators() {
        val cues = parseManualSubtitleCues("1\n00:00:01,000 --> 00:00:02,000\n첫 줄\n2\n00:00:03,000 --> 00:00:04,000\n둘째 줄\n")
        assertEquals(listOf("첫 줄", "둘째 줄"), cues.map { it.text })
    }

    @Test fun parsesAssDialogueToUserStyledPlainText() {
        val cues = parseManualSubtitleCues("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.20,0:00:03.40,Default,,0,0,0,,{\\an8}첫 줄\\N둘째 줄\n")
        assertEquals(ManualSubtitleCue(1200, 3400, "첫 줄\n둘째 줄"), cues.single())
    }

    @Test fun parsesSamiKoreanClassAndHtmlEntities() {
        val cues = parseManualSubtitleCues("<SAMI><BODY><SYNC Start=1000><P Class=ENCC>Hello<P Class=KRCC>안녕&nbsp;&#xD558;&#49464;&#50836;<SYNC Start=3000><P Class=KRCC>다음</BODY></SAMI>")
        assertEquals("안녕 하세요", cues.first().text)
        assertEquals(3000L, cues.first().endMs)
    }

    @Test fun parsesTtmlDurationAndLineBreaks() {
        val cues = parseManualSubtitleCues("<tt><body><p begin=\"1.5s\" dur=\"2s\">첫 줄<br/>둘째 줄</p></body></tt>")
        assertEquals(ManualSubtitleCue(1500, 3500, "첫 줄\n둘째 줄"), cues.single())
    }

    @Test fun decodesUtf16BomAndLegacyKoreanEncoding() {
        val body = "안녕하세요"
        assertEquals(body, decodeSubtitleBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + body.toByteArray(Charsets.UTF_16LE)))
        assertEquals(body, decodeSubtitleBytes(body.toByteArray(Charset.forName("MS949"))))
    }

    @Test fun retainsReceivedTextWhenSidecarTransferEndsEarly() {
        val bytes = "WEBVTT\n\n00:01.000 --> 00:02.000\n자막\n".toByteArray()
        val source = object : InputStream() {
            var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sent) throw IOException("interrupted fixture")
                sent = true; bytes.copyInto(buffer, offset); return bytes.size
            }
        }
        assertEquals(bytes.toList(), readSubtitleBytes(source).toList())
    }

    @Test fun untimedBodyDoesNotBecomeReadyCaptionText() {
        assertTrue(parseManualSubtitleCues("<html>로그인이 필요합니다</html>").isEmpty())
    }
}
