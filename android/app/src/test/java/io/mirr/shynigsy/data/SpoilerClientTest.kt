package io.mirr.shynigsy.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    private fun privateCurrent(mode: String? = null): PlaybackSpec {
        val source = MediaItem(23, 8, "합성 작품", "movie", viewItemId = 501, isSpoiler = true, durationMs = 120_000)
        val path = when (mode) {
            "music" -> "/transcode/music/90?ticket=old-ticket&mode=music&bitrate_kbps=256"
            null -> "/stream/90?ticket=old-ticket"
            else -> "/transcode/90?ticket=old-ticket&mode=$mode&max_height=720"
        }
        return PlaybackSpec(source.title, server.url(path).toString(), itemId = 23, libraryId = 8, partId = 90,
            viewItemId = 501, sourceItem = source, durationMs = 120_000, progressToken = "old-context",
            sessionId = "old-session", selectedAudioStreamIndex = 2,
            conversionHeight = if (mode == "video") 720 else null, navigationQuery = "합성 검색")
    }

    private fun respondFreshPlayback(mode: String? = null, selectedPart: Int = 90) {
        respond("""{"token":"spc_rotated-grant","expires_in":1800}""")
        respond("""{"selected":{"part_id":$selectedPart,"duration_ms":120000},"library_view_id":8,
            "view_item_id":501,"playback_context_token":"fresh-context","decision":"direct_play"}""")
        respond("""{"ticket":"initial-fresh-ticket"}""")
        respond("[]")
        respond("{}")
        if (mode != null) respond("""{"ticket":"fresh-conversion-ticket"}""")
        respond("""{"ticket":"final-fresh-ticket","library_view_id":8,"view_item_id":501}""")
        respond("""[{"id":4,"source":"external","format":"ass","playable":true}]""")
    }

    @Test fun foregroundReauthenticationRotatesGrantContextAndTicketsWithoutChangingDelivery() = runBlocking {
        for (mode in listOf(null, "audio", "remux", "video", "music")) {
            unlock()
            val current = privateCurrent(mode)
            respondFreshPlayback(mode)
            // The player client shares this installation/account's memory-only PIN.
            val player = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
            val refreshed = player.reauthenticateSpoilerPlayback(current, 45_000, 5)
            val requests = List(if (mode == null) 7 else 8) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals("/spoiler/client/unlock", requests.first().path)
            assertEquals("1234", org.json.JSONObject(requests.first().body.readUtf8()).getString("pin"))
            assertNull(requests.first().getHeader("X-Shyni-Spoiler"))
            requests.drop(1).forEach {
                assertEquals("spc_rotated-grant", it.getHeader("X-Shyni-Spoiler"))
                assertFalse(it.path!!.contains("old-context"))
            }
            val decision = org.json.JSONObject(requests[1].body.readUtf8())
            assertEquals(90, decision.getInt("part_id"))
            assertEquals(8, decision.getInt("library_view_id"))
            assertEquals(501, decision.getInt("view_item_id"))
            assertEquals(90, refreshed.partId)
            assertEquals(8, refreshed.libraryId)
            assertEquals(501, refreshed.viewItemId)
            assertEquals("fresh-context", refreshed.progressToken)
            assertNotEquals("old-session", refreshed.sessionId)
            assertEquals(45_000L, refreshed.resumeMs)
            assertEquals(5, refreshed.selectedAudioStreamIndex)
            assertEquals(if (mode == "video") 720 else null, refreshed.conversionHeight)
            assertEquals(if (mode == null) 0L else 45_000L, refreshed.streamStartPositionMs)
            assertEquals("합성 검색", refreshed.navigationQuery)
            assertEquals(mode, refreshed.url.toHttpUrl().queryParameter("mode"))
            if (mode == "music") assertEquals("256", refreshed.url.toHttpUrl().queryParameter("bitrate_kbps"))
            assertTrue(refreshed.url.contains(if (mode == null) "final-fresh-ticket" else "fresh-conversion-ticket"))
            assertTrue(refreshed.subtitles.single().url.contains("final-fresh-ticket"))
            assertTrue(refreshed.subtitles.single().url.contains("fresh-context"))
            assertFalse(refreshed.toJson().contains("old-ticket"))
            assertFalse(refreshed.toJson().contains("old-context"))
            assertFalse(refreshed.toJson().contains("1234"))
            assertFalse(refreshed.toJson().contains("spc_"))
            assertTrue(player.spoilerUnlocked)
            assertFalse(player.spoilerPlaybackReauthenticationFailed)
            assertEquals(1_800_000L, player.spoilerLifetimeMs)
            assertTrue(player.spoilerRemainingMs in 1..1_800_000L)
        }
    }

    @Test fun statusCannotExtendTheLocalGrantLifetime() = runBlocking {
        unlock()
        val deadline = client.spoilerExpiresAtMs
        val remaining = client.spoilerRemainingMs
        respond("""{"unlocked":true,"expires_in":86400}""")
        assertTrue(client.spoilerStatus().unlocked)
        server.takeRequest()
        assertEquals(deadline, client.spoilerExpiresAtMs)
        assertEquals(1_800_000L, client.spoilerLifetimeMs)
        assertTrue(client.spoilerRemainingMs <= remaining)
    }

    @Test fun publicForeignDeviceOrMismatchedSourceCannotUseTheCachedPin() = runBlocking {
        unlock()
        val current = privateCurrent()
        val other = ShyniClient(server.url("/").toString(), "account", deviceId = "different-device")
        assertTrue(runCatching { other.reauthenticateSpoilerPlayback(current, 1_000) }.exceptionOrNull() is ApiException)
        assertTrue(runCatching { client.reauthenticateSpoilerPlayback(current.copy(
            sourceItem = current.sourceItem!!.copy(isSpoiler = false)), 1_000) }.exceptionOrNull() is ApiException)
        assertTrue(runCatching { client.reauthenticateSpoilerPlayback(current.copy(viewItemId = 999), 1_000) }
            .exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
        assertTrue(client.spoilerUnlocked)
    }

    @Test fun automaticPinFailureIsAttemptedOnceAndDiscardsPermissionAndPin() = runBlocking {
        for (status in listOf(401, 403, 500)) {
            unlock()
            val before = server.requestCount
            respond("""{"detail":"synthetic authentication failure"}""", status)
            val failure = runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() as ApiException
            assertEquals(status, failure.status)
            assertEquals("/spoiler/client/unlock", server.takeRequest().path)
            assertFalse(client.spoilerUnlocked)
            assertTrue(client.spoilerPlaybackReauthenticationFailed)
            assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun uncertainTransportFailureCannotSilentlyRepeatThePin() = runBlocking {
        unlock()
        val before = server.requestCount
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
        assertEquals("/spoiler/client/unlock", server.takeRequest().path)
        assertTrue(client.spoilerPlaybackReauthenticationFailed)
        assertFalse(client.spoilerUnlocked)
        assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
        assertEquals(before + 1, server.requestCount)
    }

    @Test fun cancellationDuringAutomaticUnlockDoesNotLeaveAPinForRetry() = runBlocking {
        unlock()
        val before = server.requestCount
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }
        assertEquals("/spoiler/client/unlock", server.takeRequest().path)
        job.cancelAndJoin()
        assertFalse(client.spoilerUnlocked)
        assertTrue(client.spoilerPlaybackReauthenticationFailed)
        assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
        assertEquals(before + 1, server.requestCount)
    }

    @Test fun backgroundOrAccountRevocationCannotBeUndoneByALateAutomaticReply() = runBlocking {
        for (changeAccount in listOf(false, true)) {
            client.token = "account"
            unlock()
            val before = server.requestCount
            server.enqueue(MockResponse().setBody("""{"token":"spc_late","expires_in":1800}""")
                .setBodyDelay(150, TimeUnit.MILLISECONDS))
            var failure: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull()
            }
            server.takeRequest()
            if (changeAccount) client.token = "different-account" else SpoilerAccessRegistry.clearAll()
            job.join()
            assertTrue(failure is ApiException)
            assertFalse(client.spoilerUnlocked)
            assertFalse(client.spoilerPlaybackReauthenticationFailed)
            assertFalse(ShyniClient(server.url("/").toString(), "account", deviceId = "device").spoilerUnlocked)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun aManualUnlockWinsOverTheLateSuccessOrFailureOfAnOlderAutomaticAttempt() = runBlocking {
        for (status in listOf(200, 403)) {
            unlock()
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setBody(if (status == 200)
                """{"token":"spc_late","expires_in":1800}""" else """{"detail":"synthetic old failure"}""")
                .setBodyDelay(150, TimeUnit.MILLISECONDS))
            var failure: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull()
            }
            server.takeRequest()
            respond("""{"token":"spc_manual","expires_in":1800}""")
            client.unlockSpoiler("5678")
            server.takeRequest()
            job.join()
            assertTrue(failure is ApiException)
            assertTrue(client.spoilerUnlocked)
            assertFalse(client.spoilerPlaybackReauthenticationFailed)
            assertEquals("spc_manual", client.headersFor(server.url("/poster").toString())["X-Shyni-Spoiler"])
            assertEquals(before + 2, server.requestCount)
        }
    }

    @Test fun malformedAutomaticGrantCannotKeepAReusablePin() = runBlocking {
        for (response in listOf("""{"token":"spc_new","expires_in":0}""",
            """{"token":"spc_new","expires_in":1.5}""", """{"token":"spc_new","expires_in":"1800"}""",
            """{"token":"wrong-prefix","expires_in":1800}""")) {
            unlock()
            val before = server.requestCount
            respond(response)
            assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
            server.takeRequest()
            assertFalse(client.spoilerUnlocked)
            assertTrue(client.spoilerPlaybackReauthenticationFailed)
            assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun freshDecisionMustAuthorizeTheSamePartAndMustNotReuseTheOldContext() = runBlocking {
        for (part in listOf(90, 91)) {
            unlock()
            respond("""{"token":"spc_rotated","expires_in":1800}""")
            respond("""{"selected":{"part_id":$part},"library_view_id":8,"view_item_id":501,
                "playback_context_token":"${if (part == 90) "old-context" else "fresh-context"}"}""")
            respond("""{"ticket":"new-ticket"}""")
            respond("[]")
            respond("{}")
            val before = server.requestCount
            assertTrue(runCatching { client.reauthenticateSpoilerPlayback(privateCurrent(), 1_000) }.exceptionOrNull() is ApiException)
            repeat(5) { server.takeRequest() }
            assertEquals(before + 5, server.requestCount)
            assertFalse(client.spoilerUnlocked)
            assertTrue(client.spoilerPlaybackReauthenticationFailed)
        }
    }
}
