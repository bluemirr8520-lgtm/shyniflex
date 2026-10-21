package io.mirr.shynigsy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.player.subtitles.ManualSubtitleCue
import io.mirr.shynigsy.player.subtitles.decodeSubtitleBytes
import io.mirr.shynigsy.player.subtitles.parseManualSubtitleCues
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.Charset

/** Runs against Android's ICU regex engine, which differs from the desktop JVM. */
@RunWith(AndroidJUnit4::class)
class PlexSubtitleParserAndroidTest {
    @Test fun plainSrtCueParsesOnAndroid() {
        val cues = parseManualSubtitleCues(
            "1\r\n00:00:01,200 --> 00:00:03,400\r\n외부 자막 확인\r\n",
        )
        assertEquals(listOf(ManualSubtitleCue(1_200, 3_400, "외부 자막 확인")), cues)
    }

    @Test fun assOverridesAreRemovedWithoutLosingCaptionText() {
        val cues = parseManualSubtitleCues(
            "[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.20,0:00:03.40,Default,,0,0,0,,{\\an8}{\\i1}첫 줄{\\i0}\\N둘째 줄\n",
        )
        assertEquals(listOf(ManualSubtitleCue(1_200, 3_400, "첫 줄\n둘째 줄")), cues)
    }

    @Test fun serverVttFallbackParsesHtmlAndEntities() {
        val cues = parseManualSubtitleCues(
            "WEBVTT\n\n00:01.200 --> 00:03.400 align:start\n<i>안녕</i>&nbsp;&#xD558;&#49464;&#50836;<br/>다음 줄\n",
        )
        assertEquals(listOf(ManualSubtitleCue(1_200, 3_400, "안녕 하세요\n다음 줄")), cues)
    }

    @Test fun samiKoreanCaptionParsesOnAndroid() {
        val cues = parseManualSubtitleCues(
            "<SAMI><BODY><SYNC Start=1200><P Class=ENCC>Hello" +
                "<P Class=KRCC>한국어 자막<SYNC Start=3400><P Class=KRCC>다음 자막</BODY></SAMI>",
        )
        assertEquals(
            listOf(
                ManualSubtitleCue(1_200, 3_400, "한국어 자막"),
                ManualSubtitleCue(3_400, 7_400, "다음 자막"),
            ),
            cues,
        )
    }

    @Test fun ttmlCaptionParsesTimingAndLineBreaks() {
        val cues = parseManualSubtitleCues(
            "<tt><body><p begin=\"1.2s\" dur=\"2.2s\">첫 줄<br/>둘째 줄</p></body></tt>",
        )
        assertEquals(listOf(ManualSubtitleCue(1_200, 3_400, "첫 줄\n둘째 줄")), cues)
    }

    @Test fun ordinaryLiteralBracesRemainVisible() {
        val cues = parseManualSubtitleCues(
            "1\n00:00:01,000 --> 00:00:02,000\n{내용} 닫는 괄호 } 여는 괄호 {\n",
        )
        assertEquals("{내용} 닫는 괄호 } 여는 괄호 {", cues.single().text)
    }

    @Test fun utf16AndLegacyKoreanDownloadsReachTheSameParser() {
        val body = "1\n00:00:01,200 --> 00:00:03,400\n외부 한글 자막\n"
        val expected = listOf(ManualSubtitleCue(1_200, 3_400, "외부 한글 자막"))
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + body.toByteArray(Charsets.UTF_16LE)
        assertEquals(expected, parseManualSubtitleCues(decodeSubtitleBytes(utf16)))
        val korean = body.toByteArray(Charset.forName("MS949"))
        assertEquals(expected, parseManualSubtitleCues(decodeSubtitleBytes(korean)))
    }
}
