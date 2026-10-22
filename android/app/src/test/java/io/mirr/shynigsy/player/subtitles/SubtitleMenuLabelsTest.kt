package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleMenuLabelsTest {
    @Test fun languageAliasesAndRegionTagsDoNotDuplicateTheSameLanguageTitle() {
        for (language in listOf("ko", "kor", "KO_kr")) {
            assertEquals("한국어 · SRT · 서버 외부",
                subtitleMenuLabel("KOREAN", language, "application/x-subrip", "서버 외부"))
        }
        assertEquals("영어 · VTT · 서버 외부",
            subtitleMenuLabel("영어", "eng-US", "text/vtt; charset=utf-8", "서버 외부"))
        assertEquals("일본어 · ASS · 영상 내장", subtitleMenuLabel(null, "jpn", "ass", "영상 내장"))
        assertEquals("중국어 · SRT · 영상 내장", subtitleMenuLabel(null, "chi", "subrip", "영상 내장"))
    }

    @Test fun missingMetadataUsesUnknownLabelsWithoutGuessingLanguageFromAFileTitle() {
        assertEquals("언어 미상 · 형식 미상 · 영상 내장", subtitleMenuLabel(null, null, null, "영상 내장"))
        assertEquals("언어 미상 · 형식 미상 · 영상 내장", subtitleMenuLabel(" ", "und", " ", "영상 내장"))
        assertEquals("Movie.ko.srt · SRT · 서버 외부", subtitleMenuLabel("Movie.ko.srt", null, "srt", "서버 외부"))
    }

    @Test fun actualUnknownLanguageAndCodecStayVisibleWithoutInventingTheirMeaning() {
        assertEquals("xy-zz · CUSTOM_SUBTITLE · 영상 내장",
            subtitleMenuLabel(null, " Xy_ZZ ", "custom_subtitle", "영상 내장"))
        assertEquals("pt-br · 형식 미상 · 서버 내장",
            subtitleMenuLabel("PT-BR", "pt-BR", "unknown", "서버 내장"))
    }

    @Test fun textAndBitmapCodecAliasesRemainDistinguishable() {
        val formats = mapOf("text/x-ass" to "ASS", "ssa" to "SSA", "application/pgs" to "PGS",
            "dvd_subtitle" to "VobSub", "dvb_subtitle" to "DVB", "application/x-xsub" to "XSUB",
            "sami" to "SMI", "application/ttml+xml" to "TTML", "application/x-quicktime-tx3g" to "MOV_TEXT")
        for ((codec, expected) in formats) {
            assertEquals("영어 · $expected · 영상 내장", subtitleMenuLabel(null, "en", codec, "영상 내장"))
        }
    }

    @Test fun meaningfulSameLanguageTitlesAndAccessibilityFlagsArePreserved() {
        val full = subtitleMenuLabel("한국어 전체", "kor", "ass", "서버 내장")
        val forced = subtitleMenuLabel("한국어 강제", "kor", "ass", "서버 내장", forced = true)
        assertEquals("한국어 전체 · 한국어 · ASS · 서버 내장", full)
        assertEquals("한국어 강제 · 한국어 · ASS · 서버 내장 · 강제", forced)
        assertEquals("한국어 전체 · 한국어 · ASS · 서버 내장 · 청각 보조",
            subtitleMenuLabel("한국어 전체", "kor", "ass", "서버 내장", sdh = true))
        assertNotEquals(full, forced)
    }
}
