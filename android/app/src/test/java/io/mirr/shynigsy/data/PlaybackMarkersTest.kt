package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackMarkersTest {
    @Test fun actualBrowseAndMediaInfoFormatsAreNormalizedSortedAndDeduplicated() {
        val rows = JSONArray("""[
            {"segment_type":"credits","start_ms":"90000","end_ms":"100000"},
            {"type":"intro","start_ms":1000,"end_ms":9000},
            {"segment_type":"INTRO","start_ms":1000,"end_ms":9000},
            {"type":"recap","start_ms":0,"end_ms":1000}
        ]""")
        assertEquals(listOf(PlaybackMarker("recap", 0, 1000), PlaybackMarker("intro", 1000, 9000),
            PlaybackMarker("credits", 90000, 100000)), PlaybackMarker.parse(rows))
    }

    @Test fun jsonStringMarkerArraysAndPublicMediaInfoEnvelopeAreSupported() {
        val raw = """[{"type":"preview","start_ms":2000,"end_ms":5000}]"""
        assertEquals(listOf(PlaybackMarker("preview", 2000, 5000)), PlaybackMarker.parse(raw))
        assertEquals(PlaybackMarker.parse(raw), PlaybackMarker.parse(JSONObject().put("markers", raw)))
    }

    @Test fun invalidBoundsTypesFractionsAndOverflowDoNotProduceSkipButtons() {
        val rows = JSONArray("""[null,"invalid",{},
            {"type":"unknown","start_ms":0,"end_ms":1000},
            {"type":"intro","start_ms":-1,"end_ms":1000},
            {"type":"intro","start_ms":1000,"end_ms":1000},
            {"type":"intro","start_ms":2000,"end_ms":1000},
            {"type":"intro","start_ms":true,"end_ms":1000},
            {"type":"intro","start_ms":1.5,"end_ms":1000},
            {"type":"credits","start_ms":0,"end_ms":"9223372036854775808"}
        ]""")
        assertTrue(PlaybackMarker.parse(rows).isEmpty())
    }

    @Test fun knownDurationClampsEndsAndDropsRangesStartingBeyondContent() {
        val rows = JSONArray("""[{"type":"intro","start_ms":0,"end_ms":1200},
            {"type":"credits","start_ms":1000,"end_ms":2000}]""")
        assertEquals(listOf(PlaybackMarker("intro", 0, 1000)), PlaybackMarker.parse(rows, 1000))
    }

    @Test fun serverMarkerRowsAreBoundedBeforeIntentSerialization() {
        val rows = JSONArray((0 until 500).map { index -> JSONObject().put("type", "intro")
            .put("start_ms", index * 1000L).put("end_ms", index * 1000L + 500) })
        assertEquals(128, PlaybackMarker.parse(rows).size)
    }

    @Test fun playbackAndSourceMarkersRoundtripWithOlderPayloadDefault() {
        val markers = listOf(PlaybackMarker("intro", 0, 9000), PlaybackMarker("credits", 90000, 100000))
        val item = MediaItem(23, 8, "작품", "movie", viewItemId = 501, durationMs = 100000, markers = markers)
        val spec = PlaybackSpec("작품", "url", itemId = 23, libraryId = 8, partId = 90,
            durationMs = 100000, sourceItem = item, markers = markers)
        assertEquals(spec, PlaybackSpec.fromJson(spec.toJson()))
        assertEquals(item, MediaItem.fromJson(item.toJson()))
        assertTrue(PlaybackSpec.fromJson("""{"title":"작품","url":"url","itemId":23,"libraryId":8,"partId":90}""").markers.isEmpty())
    }
}
