package io.mirr.shynigsy.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SpoilerClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun respond(text: String, status: Int = 200) = server.enqueue(
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(text))
    private suspend fun unlock() {
        respond("""{"token":"spc_private-grant","expires_in":1800}""")
        client.unlockSpoiler("1234")
        server.takeRequest()
    }

    @Test fun nativePinGrantIsMemoryOnlyAndBoundToServerAccountAndDevice() = runBlocking {
        respond("""{"pin_enabled":true,"unlocked":false}""")
        val status = client.spoilerStatus()
        assertTrue(status.available)
        assertTrue(status.pinEnabled == true)
        assertFalse(status.unlocked)
        assertEquals("/spoiler/client/status", server.takeRequest().path)
        respond("""{"token":"spc_private-grant","expires_in":1800}""")
        assertTrue(client.unlockSpoiler(" 1234 ").unlocked)
        val request = server.takeRequest()
        assertEquals("/spoiler/client/unlock", request.path)
        assertEquals("device", request.getHeader("X-Device-Id"))
        assertEquals("1234", org.json.JSONObject(request.body.readUtf8()).getString("pin"))
        assertNull(request.getHeader("X-Shyni-Spoiler"))
        assertTrue(client.spoilerExpiresAtMs > System.currentTimeMillis())
        assertTrue(ShyniClient(server.url("/").toString(), "account", deviceId = "device").spoilerUnlocked)
        assertFalse(ShyniClient(server.url("/").toString(), "other-account", deviceId = "device").spoilerUnlocked)
        assertFalse(ShyniClient(server.url("/").toString(), "account", deviceId = "other-device").spoilerUnlocked)
        assertFalse(ShyniClient("https://different.example", "account", deviceId = "device").spoilerUnlocked)
        client.token = "new-account"
        assertFalse(ShyniClient(server.url("/").toString(), "account", deviceId = "device").spoilerUnlocked)
    }

    @Test fun privateCatalogUsesNativeRouteWhilePublicCatalogStaysPublic() = runBlocking {
        unlock()
        respond("""[{"id":8,"name":"일반","type":"movie"}]""")
        assertFalse(client.libraries().single().isSpoiler)
        assertNull(server.takeRequest().getHeader("X-Shyni-Spoiler"))
        respond("""{"libraries":[{"library_view_id":9,"name":"비공개","type":"tv"}]}""")
        assertTrue(client.spoilerLibraries().single().isSpoiler)
        val privateRequest = server.takeRequest()
        assertEquals("/spoiler/client/libraries", privateRequest.path)
        assertEquals("spc_private-grant", privateRequest.getHeader("X-Shyni-Spoiler"))
        respond("""{"items":[{"metadata_item_id":24,"library_view_id":9,"view_item_id":601,"type":"episode","title":"회차"}]}""")
        assertTrue(client.spoilerContinueWatching().single().isSpoiler)
        assertEquals("/spoiler/client/continue-watching?limit=30", server.takeRequest().path)
    }

    @Test fun publicContinuePreservesExplicitPrivateViewEvidenceAndPlaybackContext() = runBlocking {
        respond("""{"items":[
            {"metadata_item_id":24,"library_view_id":9,"view_item_id":601,"type":"episode","spoiler_enabled":true,"show_id":5,"season_number":0,"episode_number":2,"position_ms":45000},
            {"metadata_item_id":23,"library_view_id":8,"view_item_id":501,"type":"movie","spoiler_enabled":false}] }""")
        val items = client.continueWatching()
        val private = items.first()
        assertTrue(private.isSpoiler)
        assertEquals(24, private.id)
        assertEquals(9, private.libraryId)
        assertEquals(601, private.viewItemId)
        assertEquals(0, private.seasonNumber)
        assertEquals(45000L, private.resumeMs)
        assertFalse(items.last().isSpoiler)
        assertNull(server.takeRequest().getHeader("X-Shyni-Spoiler"))
        assertFalse(client.spoilerUnlocked)
    }

    @Test fun publicLibraryRowsDoNotLoseExplicitPrivateFlag() = runBlocking {
        respond("""[{"id":8,"name":"일반","type":"movie","spoiler_enabled":false},
            {"id":9,"name":"비공개","type":"tv","spoiler_enabled":true}]""")
        val libraries = client.libraries()
        assertFalse(libraries.first().isSpoiler)
        assertTrue(libraries.last().isSpoiler)
        assertEquals(listOf(8, 9), libraries.map { it.id })
        assertNull(server.takeRequest().getHeader("X-Shyni-Spoiler"))
    }

    @Test fun nativeContinueRetainsPrivateOriginForCallerViewMembershipFiltering() = runBlocking {
        unlock()
        respond("""{"items":[
            {"metadata_item_id":23,"library_view_id":8,"view_item_id":501,"type":"movie","spoiler_enabled":false},
            {"metadata_item_id":24,"library_view_id":9,"view_item_id":601,"type":"episode"}]}""")
        val items = client.spoilerContinueWatching()
        assertTrue(items.all { it.isSpoiler })
        assertEquals(listOf(8, 9), items.map { it.libraryId })
        assertEquals(listOf(501, 601), items.map { it.viewItemId })
        assertEquals("spc_private-grant", server.takeRequest().getHeader("X-Shyni-Spoiler"))
    }

    @Test fun nativeLibraryContextCannotBeDowngradedByExplicitFalseMetadata() = runBlocking {
        unlock()
        respond("""[{"id":9,"name":"비공개","type":"tv","spoiler_enabled":false}]""")
        assertTrue(client.spoilerLibraries().single().isSpoiler)
        assertEquals("spc_private-grant", server.takeRequest().getHeader("X-Shyni-Spoiler"))
    }

    @Test fun optionalStatusFieldsAllowPinPromptButNeverGrantPrivateAccess() = runBlocking {
        respond("""{"unlocked":true}""")
        val unknown = client.spoilerStatus()
        assertTrue(unknown.available)
        assertNull(unknown.pinEnabled)
        assertFalse("A status response cannot create a private capability", unknown.unlocked)
        server.takeRequest()
        respond("""{"available":false,"pin_enabled":false}""")
        val disabled = client.spoilerStatus()
        assertFalse(disabled.available)
        assertTrue(disabled.pinEnabled == false)
    }

    @Test fun privatePlaybackUsesGrantAtRuntimeWithoutSerializingIt() = runBlocking {
        unlock()
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
        respond("""{"ticket":"media-ticket"}""")
        respond("""[{"id":4,"source":"external","format":"srt","playable":true}]""")
        respond("{}")
        val item = MediaItem(23, 8, "작품", "movie", viewItemId = 501, isSpoiler = true)
        val spec = client.playback(item)
        assertTrue(spec.sourceItem!!.isSpoiler)
        assertTrue(spec.headers.isEmpty())
        assertTrue(spec.subtitles.single().headers.isEmpty())
        assertFalse(spec.toJson().contains("spc_private-grant"))
        assertFalse(spec.toJson().contains("account"))
        repeat(4) { assertEquals("spc_private-grant", server.takeRequest().getHeader("X-Shyni-Spoiler")) }
        assertEquals("spc_private-grant", client.playbackHeaders(spec)["X-Shyni-Spoiler"])
        assertEquals("Bearer account", client.playbackHeaders(spec)["Authorization"])
        assertEquals("device", client.playbackHeaders(spec)["X-Device-Id"])
        assertTrue(client.playbackHeaders(spec.copy(sourceItem = item.copy(isSpoiler = false))).isEmpty())
        assertTrue(client.headersFor("https://different.example/image.jpg").isEmpty())
        assertTrue(runCatching { client.playbackHeaders(spec.copy(url = "https://different.example/media")) }.exceptionOrNull() is ApiException)
    }

    @Test fun lockingClearsOtherPlayerClientsBeforeNetworkResponse() = runBlocking {
        unlock()
        val playerClient = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(150, TimeUnit.MILLISECONDS))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.lockSpoiler() }
        assertFalse(client.spoilerUnlocked)
        assertFalse(playerClient.spoilerUnlocked)
        val request = server.takeRequest()
        assertEquals("/spoiler/client/lock", request.path)
        assertEquals("spc_private-grant", request.getHeader("X-Shyni-Spoiler"))
        job.join()
        assertFalse(playerClient.spoilerUnlocked)
    }

    @Test fun revocationDuringFirstUnlockCannotRecreateCapability() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"spc_late-grant","expires_in":1800}""")
            .setBodyDelay(150, TimeUnit.MILLISECONDS))
        var failure: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching { client.unlockSpoiler("1234") }.exceptionOrNull()
        }
        server.takeRequest()
        SpoilerAccessRegistry.clearAll()
        job.join()
        assertTrue(failure is ApiException)
        assertFalse(client.spoilerUnlocked)
    }

    @Test fun lockedPrivateBrowseFailsBeforeSendingAnyRequest() = runBlocking {
        val failure = runCatching { client.items(Library(9, "비공개", "movie", true)) }.exceptionOrNull() as ApiException
        assertEquals(403, failure.status)
        assertEquals(0, server.requestCount)
    }

    @Test fun wrongPinKeepsServerReasonAndDoesNotCreateGrant() = runBlocking {
        respond("""{"detail":"PIN이 올바르지 않습니다"}""", 403)
        val failure = runCatching { client.unlockSpoiler("4321") }.exceptionOrNull() as ApiException
        assertEquals("PIN이 올바르지 않습니다", failure.message)
        assertEquals(403, failure.status)
        assertFalse(client.spoilerUnlocked)
    }

    @Test fun detailMetadataCannotDowngradeAnAuthorizedPrivateContext() {
        val private = MediaItem(23, 8, "작품", "movie", viewItemId = 501, isSpoiler = true)
        val fresh = client.parseMedia(org.json.JSONObject("""{"id":23,"spoiler_enabled":false}"""), 8, "movie", private)
        assertTrue(fresh.isSpoiler)
    }

    @Test fun privateMarkerRefreshUsesGrantAndExplicitViewWhenContextTokenMissing() = runBlocking {
        unlock()
        respond("""{"markers":[{"segment_type":"intro","start_ms":0,"end_ms":9000}]}""")
        val item = MediaItem(23, 8, "작품", "movie", viewItemId = 501, isSpoiler = true)
        val spec = PlaybackSpec("작품", "source", itemId = 23, libraryId = 8, partId = 90,
            viewItemId = 501, sourceItem = item)
        assertEquals(listOf(PlaybackMarker("intro", 0, 9000)), client.playbackMarkers(spec))
        val request = server.takeRequest()
        assertEquals("spc_private-grant", request.getHeader("X-Shyni-Spoiler"))
        assertEquals("8", request.requestUrl!!.queryParameter("library_view_id"))
        assertEquals("501", request.requestUrl!!.queryParameter("view_item_id"))
    }
}
