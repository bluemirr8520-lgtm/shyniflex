package io.mirr.shynigsy.player.mpv

import org.junit.Assert.*
import org.junit.Test

class MpvTrackSnapshotTest {
    @Test fun snapshotKeepsNativeIdsGlobalStreamIndicesAndIndependentSelectionFlags() {
        val tracks = MpvTrackSnapshot.parse("""[
            {"id":3,"type":"audio","codec":"eac3","lang":"ko","title":"Surround",
             "selected":true,"default":false,"ff-index":8,"audio-channels":6,"demux-samplerate":48000},
            {"id":7,"type":"sub","codec":"hdmv_pgs_subtitle","lang":"ko",
             "selected":false,"default":true,"forced":true,"hearing-impaired":true,"ff-index":12}
        ]""")!!
        assertEquals(3, tracks[0].id)
        assertEquals(8, tracks[0].streamIndex)
        assertEquals(6, tracks[0].audioChannels)
        assertEquals(48_000, tracks[0].sampleRate)
        assertTrue(tracks[0].selected)
        assertFalse(tracks[0].default)
        assertFalse(tracks[1].selected)
        assertTrue(tracks[1].default && tracks[1].forced && tracks[1].hearingImpaired)
        assertEquals("hdmv_pgs_subtitle", tracks[1].codec)
    }

    @Test fun sameLanguageSubtitlesRemainSeparateAndVideoDimensionsArePreserved() {
        val tracks = MpvTrackSnapshot.parse("""[
            {"id":1,"type":"video","codec":"hevc","demux-w":3840,"demux-h":2160},
            {"id":2,"type":"sub","codec":"ass","lang":"ko","ff-index":4},
            {"id":3,"type":"sub","codec":"subrip","lang":"ko","ff-index":5}
        ]""")!!
        assertEquals(3, tracks.size)
        assertEquals(3840, tracks[0].width)
        assertEquals(2160, tracks[0].height)
        assertEquals(listOf(2, 3), tracks.filter { it.type == "sub" }.map { it.id })
        assertEquals(listOf(4, 5), tracks.filter { it.type == "sub" }.map { it.streamIndex })
    }

    @Test fun emptySnapshotIsValidButMalformedSnapshotsCannotReplaceKnownTracks() {
        assertEquals(emptyList<MpvNativeTrack>(), MpvTrackSnapshot.parse("[]"))
        assertNull(MpvTrackSnapshot.parse("{"))
        assertNull(MpvTrackSnapshot.parse("""{"error":"unavailable"}"""))
        assertNull(MpvTrackSnapshot.parse(" ".repeat(1_048_577)))
    }

    @Test fun invalidRowsAndNumericOverflowDoNotInventTrackIdentities() {
        val tracks = MpvTrackSnapshot.parse("""[
            {"id":1.5,"type":"sub"},{"id":2147483648,"type":"audio"},
            {"id":0,"type":"video"},{"id":2,"type":"attachment"},
            {"id":4,"type":"audio","ff-index":-1,"audio-channels":0},
            {"id":5,"type":"sub","ff-index":2147483648}
        ]""")!!
        assertEquals(listOf(4, 5), tracks.map { it.id })
        assertNull(tracks[0].streamIndex)
        assertNull(tracks[0].audioChannels)
        assertNull(tracks[1].streamIndex)
    }

    @Test fun unrelatedSensitiveFieldsAreDiscardedAndTrackCountStaysBounded() {
        val row = """{"id":1,"type":"sub","codec":"ass","external-filename":"https://fixture.invalid/sub?ticket=private-sample"}"""
        val track = MpvTrackSnapshot.parse("[$row]")!!.single()
        assertFalse(track.toString().contains("private-sample"))
        val rows = (1..300).joinToString(",") { """{"id":$it,"type":"sub"}""" }
        assertEquals(256, MpvTrackSnapshot.parse("[$rows]")!!.size)
    }
}
