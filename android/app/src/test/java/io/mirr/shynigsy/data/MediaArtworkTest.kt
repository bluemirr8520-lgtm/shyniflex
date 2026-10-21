package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MediaArtworkTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "account-secret", deviceId = "device")
    }
    @After fun close() { server.shutdown() }

    @Test fun continueKeepsShowPosterEpisodeThumbnailAndBackdropInSeparateSlots() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"metadata_item_id":24,"library_view_id":9,
            "view_item_id":601,"type":"episode","show_id":5,"season_number":0,"episode_number":4,
            "poster_url":"/show-poster.jpg","thumb_url":"/episode-still.jpg","art_url":"/show-art.jpg",
            "position_ms":45000,"spoiler_enabled":true}]}"""))
        val item = client.continueWatching().single()
        assertEquals(server.url("/show-poster.jpg").toString(), item.poster)
        assertEquals(server.url("/episode-still.jpg").toString(), item.thumbnail)
        assertEquals(server.url("/show-art.jpg").toString(), item.backdrop)
        assertTrue(item.isSpoiler)
        assertEquals(24, item.id)
        assertEquals(9, item.libraryId)
        assertEquals(601, item.viewItemId)
        assertEquals(0, item.seasonNumber)
        assertEquals(45000L, item.resumeMs)
        assertNull(server.takeRequest().getHeader("X-Shyni-Spoiler"))
    }

    @Test fun olderContinueWithoutThumbnailKeepsExistingPosterAndBackdrop() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"metadata_item_id":24,"library_view_id":9,
            "view_item_id":601,"type":"episode","poster_url":"/show-poster.jpg","art_url":"/show-art.jpg"}]}"""))
        val item = client.continueWatching().single()
        assertNull(item.thumbnail)
        assertEquals(server.url("/show-poster.jpg").toString(), item.poster)
        assertEquals(server.url("/show-art.jpg").toString(), item.backdrop)
    }

    @Test fun ordinaryBrowsePreservesItsExistingThumbFirstPosterChoice() {
        val item = client.parseMedia(JSONObject("""{"id":24,"thumb_url":"/episode.jpg",
            "poster_url":"/show.jpg","art_url":"/art.jpg"}"""), 9, "episode")
        assertEquals(server.url("/episode.jpg").toString(), item.poster)
        assertEquals(server.url("/episode.jpg").toString(), item.thumbnail)
        assertEquals(server.url("/art.jpg").toString(), item.backdrop)
    }

    @Test fun metadataRefreshWithoutArtworkPreservesAllPreviouslyKnownSlots() {
        val old = MediaItem(24, 9, "회차", "episode", poster = server.url("/show.jpg").toString(),
            backdrop = server.url("/art.jpg").toString(), thumbnail = server.url("/episode.jpg").toString())
        val refreshed = client.parseMedia(JSONObject("""{"id":24,"summary":"새 소개"}"""), 9, "episode", old)
        assertEquals(old.poster, refreshed.poster)
        assertEquals(old.thumbnail, refreshed.thumbnail)
        assertEquals(old.backdrop, refreshed.backdrop)
        assertEquals("새 소개", refreshed.summary)
    }

    @Test fun unsupportedThumbnailSchemeCannotDisplaceKnownSafeArtwork() {
        val old = MediaItem(24, 9, "회차", "episode", thumbnail = server.url("/episode.jpg").toString())
        val refreshed = client.parseMedia(JSONObject("""{"id":24,"thumb_url":"file:///private/image.jpg"}"""), 9, "episode", old)
        assertEquals(old.thumbnail, refreshed.thumbnail)
    }

    @Test fun privateArtworkAndViewIdentityRoundtripWithoutCredentialsOrGrantHeaders() {
        val item = MediaItem(24, 9, "회차", "episode", poster = server.url("/show.jpg").toString(),
            backdrop = server.url("/art.jpg").toString(), thumbnail = server.url("/episode.jpg").toString(),
            viewItemId = 601, isSpoiler = true)
        val spec = PlaybackSpec("회차", server.url("/stream/90?ticket=part-only").toString(),
            itemId = 24, libraryId = 9, partId = 90, viewItemId = 601, sourceItem = item)
        assertEquals(item, MediaItem.fromJson(item.toJson()))
        assertEquals(spec, PlaybackSpec.fromJson(spec.toJson()))
        assertFalse(spec.toJson().contains("account-secret"))
        assertFalse(spec.toJson().contains("X-Shyni-Spoiler"))
        assertFalse(spec.toJson().contains("Authorization"))
    }

    @Test fun olderMediaAndPlaybackJsonDefaultThumbnailToUnknown() {
        val itemJson = """{"id":24,"libraryId":9,"title":"회차","type":"episode","poster":"https://server/show.jpg"}"""
        assertNull(MediaItem.fromJson(itemJson).thumbnail)
        val specJson = JSONObject().put("title", "회차").put("url", "https://server/stream/90")
            .put("itemId", 24).put("libraryId", 9).put("partId", 90).put("sourceItem", JSONObject(itemJson))
        val restored = PlaybackSpec.fromJson(specJson.toString())
        assertNull(restored.sourceItem!!.thumbnail)
        assertEquals("https://server/show.jpg", restored.sourceItem!!.poster)
    }
}
