package io.mirr.shynigsy.data

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.IOException

class ShyniClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val metadata = PlaybackMetadataDispatcher()
    private val movie = MediaItem(23, 8, "영화", "movie", durationMs = 7200000, viewItemId = 501)

    @Before fun setup() {
        server = MockWebServer().apply { dispatcher = metadata; start() }
        client = ShyniClient(server.url("/").toString(), "account-token", deviceId = "device-123")
    }
    @After fun close() { server.shutdown() }
    private fun respond(body: String, status: Int = 200) = server.enqueue(
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body),
    )
    private fun body(): JSONObject = JSONObject(server.takeRequest().body.readUtf8())
    private fun respondMetadata(path: String, body: String, status: Int = 200) =
        metadata.enqueueMetadata(path, MockResponse().setResponseCode(status).setBody(body))

    @Test fun loginUsesTokenContractWithoutExistingBearer() = runBlocking {
        respond("""{"token":"new-token","username":"tester"}""")
        assertEquals(LoginOutcome.Success("new-token", "tester"), client.login(" tester ", " secret "))
        val request = server.takeRequest()
        val json = JSONObject(request.body.readUtf8())
        assertEquals("/auth/login", request.path)
        assertEquals("tester", json.getString("username"))
        assertEquals(" secret ", json.getString("password"))
        assertNull(request.getHeader("Authorization"))
        assertEquals("device-123", request.getHeader("X-Device-Id"))
        assertEquals("new-token", client.token)
    }

    @Test fun totpChallengeDoesNotReplaceSessionUntilConfirmed() = runBlocking {
        respond("""{"require_totp":true,"challenge_token":"challenge-value"}""")
        assertEquals(LoginOutcome.NeedsTotp("challenge-value"), client.login("user", "password"))
        assertEquals("account-token", client.token)
        server.takeRequest()
        respond("""{"token":"after-totp","username":"user"}""")
        assertEquals(LoginOutcome.Success("after-totp", "user"), client.completeTotp("challenge-value", " 123456 "))
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        val json = JSONObject(request.body.readUtf8())
        assertEquals("/auth/login/totp", request.path)
        assertEquals("challenge-value", json.getString("challenge_token"))
        assertEquals("123456", json.getString("code"))
        assertFalse(json.getBoolean("trust_device"))
    }

    @Test fun forbiddenErrorKeepsServerMessageAndHttpStatus() = runBlocking {
        respond("""{"detail":"이 View에 접근할 수 없습니다."}""", 403)
        val failure = runCatching { client.libraries() }.exceptionOrNull() as ApiException
        assertEquals(403, failure.status)
        assertEquals("이 View에 접근할 수 없습니다.", failure.message)
    }

    @Test fun transportFailureRetainsCauseForProgressRetryPolicy() = runBlocking {
        val offline = ShyniClient(server.url("/").toString(), "account-token",
            httpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor {
                throw IOException("connection interrupted")
            }.build())
        val failure = runCatching { offline.continueWatching() }.exceptionOrNull() as ApiException
        assertEquals(0, failure.status)
        assertTrue(failure.cause is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectsCannotForwardAccountTokenOrLoginBody() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            repeat(2) { server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/capture"))) }
            val apiFailure = runCatching { client.me() }.exceptionOrNull() as ApiException
            val loginFailure = runCatching { client.login("user", "private-password") }.exceptionOrNull() as ApiException
            assertEquals(307, apiFailure.status)
            assertEquals(307, loginFailure.status)
            assertEquals(0, other.requestCount)
            assertEquals(2, server.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun imageAuthorizationIsRestrictedBySchemeHostAndPort() {
        assertEquals("Bearer account-token", client.headersFor(server.url("/image").toString())["Authorization"])
        assertEquals("Bearer account-token", client.headersFor("/image")["Authorization"])
        assertTrue(client.headersFor("https://example.com/image").isEmpty())
        assertTrue(client.headersFor("https://localhost:${server.port}/image").isEmpty())
        assertTrue(client.headersFor("http://localhost:${server.port + 1}/image").isEmpty())
    }

    @Test fun publicLibrariesUseViewIdsAndExcludeNonvideoTypes() = runBlocking {
        respond("""[{"id":8,"management_section_id":99,"name":"영화","type":"movie"},{"id":9,"name":"TV","type":"tv"},{"id":10,"name":"음악","type":"music"}]""")
        assertEquals(listOf(Library(8, "영화", "movie"), Library(9, "TV", "tv")), client.libraries())
        val request = server.takeRequest()
        assertEquals("/libraries", request.path)
        assertEquals("Bearer account-token", request.getHeader("Authorization"))
    }

    @Test fun recentlyAddedKeepsEachPublicViewGroupedAndRetainsWatchedIdentity() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val json = when (url.encodedPath) {
                    "/libraries/8/movies" -> """[
                        {"id":23,"title":"영화 A","library_id":999,"view_item_id":501,"added_at":"2026-10-13T06:00:00Z"},
                        {"id":24,"title":"날짜 없음","view_item_id":502,"added_at":"invalid"},
                        {"id":50,"title":"원격","view_item_id":777,"added_at":"2026-10-13T09:00:00Z","remote":true}
                    ]"""
                    "/libraries/9/shows" -> """[{"id":23,"title":"시리즈","view_item_id":601,"added_at":"2020-01-01T00:00:00Z","latest_ep":"2026-10-13T16:00:00+09:00"}]"""
                    "/libraries/10/movies" -> """[{"id":23,"title":"영화 B","view_item_id":701,"added_at":"2026-10-13T07:30:00Z"}]"""
                    "/playback/progress" -> if (url.queryParameter("library_view_id") == "8")
                        """{"items":[{"metadata_item_id":23,"completed":true,"position_ms":999,"duration_ms":1000}]}"""
                        else """{"items":[{"metadata_item_id":23,"completed":false,"position_ms":42,"duration_ms":1000}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
            }
        }
        val recent = client.recentlyAdded(listOf(Library(8, "영화", "movie"), Library(9, "TV", "tv"),
            Library(10, "다른 영화", "movie"), Library(8, "중복 View", "movie"), Library(99, "도서", "book")))
        assertEquals(listOf("영화 A", "날짜 없음", "시리즈", "영화 B"), recent.map { it.title })
        assertEquals(listOf(8, 8, 9, 10), recent.map { it.libraryId })
        assertEquals(listOf(501, 502, 601, 701), recent.map { it.viewItemId })
        assertEquals("show", recent[2].type)
        assertTrue(recent[0].watched)
        assertEquals(0L, recent[0].resumeMs)
        assertEquals(42L, recent[3].resumeMs)
        assertEquals(5, server.requestCount)
        val requests = (1..5).map { server.takeRequest() }
        val paths = requests.map { it.path }.toSet()
        assertTrue(paths.contains("/libraries/8/movies?sort=added&sort_dir=desc"))
        assertTrue(paths.contains("/libraries/9/shows?sort=latest_episode&sort_dir=desc"))
        assertTrue(paths.contains("/libraries/10/movies?sort=added&sort_dir=desc"))
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertEquals("Bearer account-token", request.getHeader("Authorization"))
            assertNull(request.requestUrl!!.queryParameter("offset"))
            assertNull(request.requestUrl!!.queryParameter("limit"))
        }
    }

    @Test fun recentlyAddedCapsCatalogLocallyAndFetchesProgressOnlyForVisibleItems() = runBlocking {
        val timestamp = java.time.OffsetDateTime.parse("2026-10-13T12:00:00Z")
        respond(JSONArray((1..75).map { id -> JSONObject().put("id", id).put("title", "Movie $id")
            .put("view_item_id", id + 100).put("added_at", timestamp.minusMinutes(id.toLong()).toString()) }).toString())
        respond("{}")
        val recent = client.recentlyAdded(listOf(Library(8, "영화", "movie")))
        assertEquals(20, recent.size)
        assertEquals(1, recent.first().id)
        assertEquals(20, recent.last().id)
        assertEquals("/libraries/8/movies?sort=added&sort_dir=desc", server.takeRequest().path)
        assertEquals((1..20).joinToString(","), server.takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals(2, server.requestCount)
    }

    @Test fun recentlyAddedLargeNewLibraryCannotRemoveAnOlderLibraryRow() = runBlocking {
        val timestamp = java.time.OffsetDateTime.parse("2026-10-13T12:00:00Z")
        val largeNewCatalog = JSONArray((1..75).map { id -> JSONObject().put("id", id).put("title", "New $id")
            .put("view_item_id", id + 100).put("added_at", timestamp.minusMinutes(id.toLong()).toString()) }).toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = when (request.requestUrl!!.encodedPath) {
                    "/libraries/8/movies" -> largeNewCatalog
                    "/libraries/9/shows" -> """[{"id":800,"title":"오래된 TV","view_item_id":801,"latest_ep":"2020-01-01T00:00:00Z"}]"""
                    "/libraries/10/movies" -> """[{"id":900,"title":"오래된 영화","view_item_id":901,"added_at":"2020-01-01T00:00:00Z"}]"""
                    "/playback/progress" -> "{}"
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
            }
        }
        val recent = client.recentlyAdded(listOf(Library(8, "새 영화", "movie"), Library(9, "TV", "tv"), Library(10, "옛 영화", "movie")))
        assertEquals(22, recent.size)
        assertEquals((1..20).toList(), recent.filter { it.libraryId == 8 }.map { it.id })
        assertEquals(listOf(8, 9, 10), recent.map { it.libraryId }.distinct())
        assertEquals("오래된 TV", recent[20].title)
        assertEquals("오래된 영화", recent[21].title)
        assertEquals(5, server.requestCount)
        val progressRequests = (1..5).map { server.takeRequest() }.filter { it.requestUrl!!.encodedPath == "/playback/progress" }
        assertEquals(2, progressRequests.size)
        assertEquals((1..20).joinToString(","), progressRequests.single { it.requestUrl!!.queryParameter("library_view_id") == "8" }.requestUrl!!.queryParameter("item_ids"))
        assertEquals("900", progressRequests.single { it.requestUrl!!.queryParameter("library_view_id") == "10" }.requestUrl!!.queryParameter("item_ids"))
    }

    @Test fun recentlyAddedSkipsNonvideoViewsWithoutNetwork() = runBlocking {
        assertTrue(client.recentlyAdded(listOf(Library(99, "도서", "book"), Library(0, "잘못된 View", "movie"))).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun recentlyAddedPropagatesAuthenticationFailure() = runBlocking {
        respond("""{"detail":"로그인 만료"}""", 401)
        val failure = runCatching { client.recentlyAdded(listOf(Library(8, "영화", "movie"))) }.exceptionOrNull() as ApiException
        assertEquals(401, failure.status)
        assertEquals("로그인 만료", failure.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun recentlyAddedRejectsOversizedCatalogBeforeReadingTheWholeBody() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeader("Content-Length", 16L * 1024 * 1024 + 1))
        val failure = runCatching { client.recentlyAdded(listOf(Library(8, "영화", "movie"))) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("서버 응답이 너무 큽니다.", failure?.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun moviePaginationSlicesFullCatalogWithoutResendingFakeOffsets() = runBlocking {
        val all = JSONArray((1..75).map { JSONObject().put("id", it).put("title", "movie $it").put("view_item_id", it + 100) })
        respond(all.toString())
        respond("{}")
        respond("{}")
        val library = Library(8, "영화", "movie")
        assertEquals(60, client.items(library).items.size)
        val second = client.items(library, 60)
        assertEquals(75, second.total)
        assertEquals(61, second.items.first().id)
        assertEquals(15, second.items.size)
        assertEquals(3, server.requestCount)
        assertEquals("/libraries/8/movies?sort=title&sort_dir=asc", server.takeRequest().path)
        assertEquals((1..60).joinToString(","), server.takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals((61..75).joinToString(","), server.takeRequest().requestUrl!!.queryParameter("item_ids"))
    }

    @Test fun searchEscapesQueryAndIncludesMetadataType() = runBlocking {
        respond("""[{"id":23,"title":"A&B / 한글","view_item_id":501}]""")
        respond("{}")
        assertEquals(1, client.items(Library(8, "영화", "movie"), query = "A&B / 한글").total)
        val request = server.takeRequest()
        assertEquals("A&B / 한글", request.requestUrl!!.queryParameter("q"))
        assertEquals("1", request.requestUrl!!.queryParameter("metadata_type"))
        assertEquals("200", request.requestUrl!!.queryParameter("limit"))
    }

    @Test fun catalogNeverAdvertisesUnavailablePagesFromAnUnpagedResponse() = runBlocking {
        respond("""{"items":[{"id":23,"metadata_type":1,"title":"Movie","view_item_id":501}],"total":999}""")
        respond("{}")
        val page = client.items(Library(8, "영화", "movie"), query = "Movie")
        assertEquals(1, page.total)
        assertEquals("movie", page.items.single().type)
        assertTrue(client.items(Library(8, "영화", "movie"), 60, "Movie").items.isEmpty())
    }

    @Test fun movieDetailsRefreshResumeFromExactProgressEnvelope() = runBlocking {
        respond("""{"id":23,"title":"새 제목","duration_sec":7200,"view_item_id":502,"summary":"설명"}""")
        respond("""{"items":[{"metadata_item_id":23,"position_ms":42000,"duration_ms":7200000,"completed":false}]}""")
        val detail = client.details(movie)
        assertEquals("새 제목", detail.item.title)
        assertEquals("설명", detail.item.summary)
        assertEquals(502, detail.item.viewItemId)
        assertEquals(42000L, detail.item.resumeMs)
        assertFalse(detail.item.watched)
        assertEquals("/libraries/8/movies/23", server.takeRequest().path)
        assertEquals("/playback/progress?item_ids=23&library_view_id=8", server.takeRequest().path)
    }

    @Test fun episodeDetailsUseListMetadataAndResetCompletedResume() = runBlocking {
        respond("""{"items":[{"metadata_item_id":23,"position_ms":7100000,"duration_ms":7200000,"completed":true}]}""")
        val episode = movie.copy(type = "episode", summary = "회차 설명", resumeMs = 54000)
        val detail = client.details(episode)
        assertEquals("회차 설명", detail.item.summary)
        assertEquals(0L, detail.item.resumeMs)
        assertTrue(detail.item.watched)
        assertEquals(1, server.requestCount)
        assertEquals("/playback/progress?item_ids=23&library_view_id=8", server.takeRequest().path)
    }

    @Test fun continueEpisodeEnrichmentUsesSupportedSeasonRouteAndPreservesPlaybackIdentity() = runBlocking {
        respond("""{"id":5,"title":"시리즈","summary":"시리즈 소개","art_url":"/show-backdrop.jpg"}""")
        respond("""[{"id":7,"index_":0,"title":"스페셜"}]""")
        // A View may choose a different physical version for the same logical episode.
        respond("""[{"id":999,"title":"회차 제목","season_number":0,"episode_number":4,
            "summary":"선택 회차 소개","thumb_url":"/episode.jpg","view_item_id":777}]""")
        respond("""{"items":[{"metadata_item_id":24,"position_ms":65000,"completed":false}]}""")
        val item = MediaItem(24, 9, "이전 제목", "episode", poster = server.url("/poster.jpg").toString(),
            showId = 5, seasonNumber = 0, episodeNumber = 4, viewItemId = 601)
        val fresh = client.details(item).item
        assertEquals(24, fresh.id)
        assertEquals(9, fresh.libraryId)
        assertEquals("episode", fresh.type)
        assertEquals(601, fresh.viewItemId)
        assertEquals(item.poster, fresh.poster)
        assertEquals("회차 제목", fresh.title)
        assertEquals("선택 회차 소개", fresh.summary)
        assertEquals("시리즈", fresh.showTitle)
        assertEquals(server.url("/show-backdrop.jpg").toString(), fresh.backdrop)
        assertEquals(65000L, fresh.resumeMs)
        assertEquals("/libraries/9/shows/5", server.takeRequest().path)
        assertEquals("/libraries/9/shows/5/seasons?platform=mobile", server.takeRequest().path)
        assertEquals("/libraries/9/shows/5/seasons/7/episodes?limit=100&offset=0", server.takeRequest().path)
        assertEquals("/playback/progress?item_ids=24&library_view_id=9", server.takeRequest().path)
    }

    @Test fun continueEpisodeWithoutSeasonUsesShowArtworkAndDescriptionFallbackOnly() = runBlocking {
        respond("""{"id":5,"title":"시리즈","summary":"시리즈 소개","art_url":"/show-backdrop.jpg"}""")
        respond("{}")
        val item = MediaItem(24, 9, "회차", "episode", showId = 5, viewItemId = 601)
        val fresh = client.details(item).item
        assertEquals(item.id, fresh.id)
        assertEquals("시리즈 소개", fresh.summary)
        assertEquals(server.url("/show-backdrop.jpg").toString(), fresh.backdrop)
        assertEquals(2, server.requestCount)
        assertEquals("/libraries/9/shows/5", server.takeRequest().path)
        assertEquals("/playback/progress?item_ids=24&library_view_id=9", server.takeRequest().path)
    }

    @Test fun showDetailsPreserveSpecialSeasonZeroAndShowIdentity() = runBlocking {
        respond("""{"id":5,"title":"Show","view_item_id":50}""")
        respond("""[{"id":7,"index_":0,"title":"스페셜","view_item_id":70},
            {"id":8,"season_number":1,"title":"시즌 1","show_title":"서버가 지정한 작품명","view_item_id":80}]""")
        val details = client.details(MediaItem(5, 9, "Old title", "show", viewItemId = 50))
        assertEquals("Show", details.item.title)
        assertEquals(0, details.children[0].seasonNumber)
        assertEquals(5, details.children[0].showId)
        assertEquals("Show", details.children[0].showTitle)
        assertEquals("season", details.children[1].type)
        assertEquals("서버가 지정한 작품명", details.children[1].showTitle)
        assertEquals("/libraries/9/shows/5", server.takeRequest().path)
        assertEquals("/libraries/9/shows/5/seasons?platform=mobile", server.takeRequest().path)
    }

    @Test fun seasonEpisodePaginationUsesServerOffset() = runBlocking {
        respond(JSONArray((1..100).map { JSONObject().put("id", it).put("title", "회차 $it").put("view_item_id", 1000 + it) }).toString())
        respond("""[{"id":101,"title":"마지막","show_title":"서버 회차 작품명","view_item_id":1101}]""")
        respond("""{"items":[{"metadata_item_id":7,"completed":true,"position_ms":9900}]}""")
        respond("{}")
        val details = client.details(MediaItem(7, 9, "시즌 1", "season", showId = 5, showTitle = "부모 작품명"))
        assertEquals(101, details.children.size)
        assertEquals(5, details.children.last().showId)
        assertEquals("episode", details.children.last().type)
        assertEquals("부모 작품명", details.children.first().showTitle)
        assertEquals("부모 작품명", details.children[99].showTitle)
        assertEquals("서버 회차 작품명", details.children.last().showTitle)
        assertEquals("마지막", details.children.last().title)
        assertTrue(details.children[6].watched)
        assertFalse(details.children.last().watched)
        assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals((1..100).joinToString(","), server.takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals("101", server.takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals(4, server.requestCount)
    }

    @Test fun playbackCarriesViewContextAndUsesOnlyScopedTicketInPlayer() = runBlocking {
        respond("""{"selected":{"part_id":90,"duration_sec":7200},"library_view_id":8,"view_item_id":501,"playback_context_token":"part-context","decision":"direct_play","selected_audio_stream_index":2,"selected_audio_stream_ordinal":1,"selected_audio_language":"ko","client_playback_hints":{"prefer_software_decode":true}}""")
        respond("""{"ticket":"scoped-ticket"}""")
        respondMetadata("/subtitles/90", """[{"id":4,"label":"한국어","lang_code":"ko","playable":true},{"id":5,"label":"이미지 자막","playable":false}]""")
        respondMetadata("/playback/progress", """{"items":[{"metadata_item_id":23,"part_id":90,"library_view_id":8,"view_item_id":501,"position_ms":42000,"duration_ms":7200000,"completed":false,"state":"paused"}]}""")
        val spec = client.playback(movie, 90)
        assertEquals(42000L, spec.resumeMs)
        assertEquals(7200000L, spec.durationMs)
        assertEquals(501, spec.viewItemId)
        assertEquals("part-context", spec.progressToken)
        assertEquals("direct_play", spec.decision)
        assertEquals(2, spec.selectedAudioStreamIndex)
        assertEquals(1, spec.selectedAudioStreamOrdinal)
        assertEquals("ko", spec.selectedAudioLanguage)
        assertTrue(spec.preferSoftwareDecode)
        assertEquals(90, spec.requestedPartId)
        assertTrue(spec.headers.isEmpty())
        assertFalse(spec.url.contains("account-token"))
        assertTrue(spec.url.endsWith("/stream/90?ticket=scoped-ticket"))
        assertEquals(1, spec.subtitles.size)
        assertTrue(spec.subtitles.single().headers.isEmpty())
        val subtitleUrl = spec.subtitles.single().url.toHttpUrl()
        assertEquals("/subtitles/track/4.vtt", subtitleUrl.encodedPath)
        assertEquals("scoped-ticket", subtitleUrl.queryParameter("ticket"))
        assertEquals("part-context", subtitleUrl.queryParameter("playback_context_token"))
        val decision = body()
        assertEquals(90, decision.getInt("part_id"))
        assertFalse(decision.has("metadata_item_id"))
        assertEquals(8, decision.getInt("library_view_id"))
        assertEquals(501, decision.getInt("view_item_id"))
        assertEquals("mobile", decision.getString("platform"))
        assertEquals("android", decision.getString("client_app"))
        assertFalse("Native automatic playback must not invent a transcode profile", decision.has("client_profile"))
        val enrichment = List(3) { server.takeRequest() }.associateBy { it.requestUrl!!.encodedPath }
        assertEquals("part-context", enrichment.getValue("/stream-ticket/90").requestUrl!!.queryParameter("playback_context_token"))
        assertEquals("part-context", enrichment.getValue("/subtitles/90").requestUrl!!.queryParameter("playback_context_token"))
        assertEquals("8", enrichment.getValue("/playback/progress").requestUrl!!.queryParameter("library_view_id"))
    }

    @Test fun sourceVersionsUseActualResolutionFallbackAndDeduplicatePartIds() = runBlocking {
        respond("""{"options":[
            {"part_id":90,"metadata_item_id":23,"resolution":"1920x1080","video_codec":"h264","audio_codec":"aac","file_size":1073741824,"bitrate_bps":8000000},
            {"part_id":90,"resolution_label":"duplicate"},
            {"part_id":91,"resolution_label":"4K","video_codec":"hevc","audio_codec":"truehd","dubbed":true},
            {"part_id":0,"resolution_label":"invalid"}
        ]}""")
        val versions = client.versions(movie)
        assertEquals(listOf(90, 91), versions.map { it.partId })
        assertEquals("1920x1080", versions[0].resolution)
        assertEquals("h264", versions[0].videoCodec)
        assertEquals("aac", versions[0].audioCodec)
        assertEquals(1_073_741_824L, versions[0].fileSizeBytes)
        assertEquals(8_000_000L, versions[0].bitrateBps)
        assertEquals(23, versions[0].metadataItemId)
        assertEquals("1920x1080 · H.264 · AAC · 8.0 Mbps · 1.00 GB", versions[0].label)
        assertEquals("4K · HEVC · TRUEHD · 더빙", versions[1].label)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/playback/versions", request.path)
        val selection = JSONObject(request.body.readUtf8())
        assertEquals(23, selection.getInt("metadata_item_id"))
        assertEquals(8, selection.getInt("library_view_id"))
        assertEquals(501, selection.getInt("view_item_id"))
        assertFalse(selection.has("client_profile"))
        assertFalse(selection.has("max_resolution"))
        assertFalse(selection.has("max_bitrate_bps"))
    }

    @Test fun singleSourceInfoIsShownWhenVersionOptionsAreUnavailable() = runBlocking {
        respond("""{"options":[],"source_info":{"part_id":90,"metadata_item_id":23,"resolution_label":"1080p","video_codec":"av1","file_size":524288000}}""")
        val version = client.versions(movie).single()
        assertEquals(90, version.partId)
        assertEquals("1080p · AV1 · 500 MB", version.label)
        assertEquals(23, version.metadataItemId)
    }

    @Test fun incompleteSourceDetailsUseHonestFileLabelWithoutInventedQuality() = runBlocking {
        respond("""{"options":[{"part_id":90}]}""")
        val version = client.versions(movie).single()
        assertEquals("원본 파일 90", version.label)
        assertNull(version.resolution)
        assertNull(version.videoCodec)
        assertNull(version.bitrateBps)
    }

    @Test fun defaultPlaybackRemainsAutomaticEvenWhenServerChoosesASpecificPart() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
        respond("""{"ticket":"ticket"}""")
        respondMetadata("/subtitles/90", "[]")
        respondMetadata("/playback/progress", "{}")
        val spec = client.playback(movie)
        assertEquals(90, spec.partId)
        assertNull(spec.requestedPartId)
        val request = body()
        assertEquals(23, request.getInt("metadata_item_id"))
        assertFalse(request.has("part_id"))
        assertFalse(request.has("client_profile"))
    }

    @Test fun alternateMetadataVersionKeepsLogicalResumeAndUsesSelectedPartContextForProgress() = runBlocking {
        respond("""{"options":[{"part_id":91,"metadata_item_id":456,"resolution_label":"2160p"}]}""")
        respond("""{"selected":{"part_id":91,"duration_sec":7200},"library_view_id":8,"view_item_id":502,"playback_context_token":"version-context","decision":"direct_play"}""")
        respond("""{"ticket":"version-ticket"}""")
        respondMetadata("/subtitles/91", "[]")
        // Shyni resolves the shared content identity and returns the requested logical item ID.
        respondMetadata("/playback/progress", """{"items":[{"metadata_item_id":23,"part_id":91,"position_ms":42000,"completed":false}]}""")
        respond("{}")

        val alternative = client.versions(movie).single()
        assertEquals(456, alternative.metadataItemId)
        val spec = client.playback(movie, alternative.partId)
        assertEquals(23, spec.itemId)
        assertEquals(91, spec.partId)
        assertEquals(91, spec.requestedPartId)
        assertEquals(502, spec.viewItemId)
        assertEquals(42000L, spec.resumeMs)
        assertTrue(spec.url.endsWith("/stream/91?ticket=version-ticket"))
        client.saveProgress(spec, 45000, 7200000, "paused", 3000)

        assertEquals("/playback/versions", server.takeRequest().path)
        val decision = body()
        assertEquals(91, decision.getInt("part_id"))
        assertFalse(decision.has("metadata_item_id"))
        assertEquals(501, decision.getInt("view_item_id"))
        val enrichment = List(3) { server.takeRequest() }.associateBy { it.requestUrl!!.encodedPath }
        assertEquals("version-context", enrichment.getValue("/stream-ticket/91").requestUrl!!.queryParameter("playback_context_token"))
        assertEquals("version-context", enrichment.getValue("/subtitles/91").requestUrl!!.queryParameter("playback_context_token"))
        assertEquals("23", enrichment.getValue("/playback/progress").requestUrl!!.queryParameter("item_ids"))
        val progress = body()
        assertEquals(91, progress.getInt("part_id"))
        assertEquals(23, progress.getInt("metadata_item_id"))
        assertEquals(502, progress.getInt("view_item_id"))
        assertEquals("version-context", progress.getString("playback_context_token"))
    }

    @Test fun dataSavingConversionForcesCurrentPartAndSeparatesSubtitleCredentials() = runBlocking {
        respond("""{"ticket":"video &?","mode":"video"}""")
        respond("""{"ticket":"subtitle-ticket","library_view_id":8,"view_item_id":501}""")
        respond("""[{"id":4,"source":"embedded","format":"ass","lang_code":"ko","stream_index":5,"playable":true}]""")
        val original = PlaybackSpec("영화", "https://server/stream/90?ticket=old", itemId = 23, libraryId = 8,
            partId = 90, durationMs = 7200000, sessionId = "old-session", progressToken = "part context&?",
            viewItemId = 501, requestedPartId = 90, selectedAudioStreamIndex = 2, selectedAudioLanguage = "ko",
            markers = listOf(PlaybackMarker("intro", 1000, 9000)))
        val converted = client.convertPlayback(original, 720, 42000)

        assertEquals(23, converted.itemId)
        assertEquals(90, converted.partId)
        assertEquals(90, converted.requestedPartId)
        assertEquals(720, converted.conversionHeight)
        assertEquals(42000L, converted.streamStartPositionMs)
        assertEquals(42000L, converted.resumeMs)
        assertEquals(7200000L, converted.durationMs)
        assertEquals(original.markers, converted.markers)
        assertEquals("video_transcode", converted.decision)
        assertEquals(0, converted.selectedAudioStreamOrdinal)
        assertEquals(2, converted.selectedAudioStreamIndex)
        assertEquals("ko", converted.selectedAudioLanguage)
        assertNotEquals(original.sessionId, converted.sessionId)
        assertTrue(converted.headers.isEmpty())
        val stream = converted.url.toHttpUrl()
        assertEquals("/transcode/90", stream.encodedPath)
        assertEquals("video &?", stream.queryParameter("ticket"))
        assertEquals("video", stream.queryParameter("mode"))
        assertEquals("720", stream.queryParameter("max_height"))
        assertEquals("42000", stream.queryParameter("start_ms"))
        assertEquals("2", stream.queryParameter("audio_stream_index"))
        assertNull(stream.queryParameter("bitrate_kbps"))
        assertFalse(converted.url.contains("account-token"))
        val subtitle = converted.subtitles.single()
        assertEquals("external", subtitle.source)
        assertNull(subtitle.streamIndex)
        assertEquals("text/vtt", subtitle.mimeType)
        assertEquals("subtitle-ticket", subtitle.url.toHttpUrl().queryParameter("ticket"))
        val conversionRequest = server.takeRequest()
        assertEquals("POST", conversionRequest.method)
        assertEquals("/playback/transcode-ticket/90", conversionRequest.requestUrl!!.encodedPath)
        assertEquals("video", conversionRequest.requestUrl!!.queryParameter("mode"))
        assertEquals("part context&?", conversionRequest.requestUrl!!.queryParameter("playback_context_token"))
        assertEquals("/stream-ticket/90", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/subtitles/90", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun originalQualityRenewsSamePartAndRemovesConversionOffset() = runBlocking {
        respond("""{"ticket":"original-ticket"}""")
        respond("""[{"id":4,"source":"embedded","format":"ass","stream_index":5,"playable":true}]""")
        val current = PlaybackSpec("영화", "https://server/transcode/90?ticket=old", itemId = 23, libraryId = 8,
            partId = 90, durationMs = 7200000, progressToken = "context", viewItemId = 501,
            decision = "video_transcode", conversionHeight = 480, streamStartPositionMs = 42000,
            selectedAudioStreamIndex = 2, selectedAudioStreamOrdinal = 0)
        val restored = client.convertPlayback(current, null, 60000)
        assertNull(restored.conversionHeight)
        assertEquals(0L, restored.streamStartPositionMs)
        assertEquals(60000L, restored.resumeMs)
        assertEquals(90, restored.partId)
        assertEquals("direct_play", restored.decision)
        assertTrue(restored.url.endsWith("/stream/90?ticket=original-ticket"))
        assertEquals("embedded", restored.subtitles.single().source)
        assertEquals(5, restored.subtitles.single().streamIndex)
        assertNull(restored.selectedAudioStreamOrdinal)
        assertEquals("/stream-ticket/90", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/subtitles/90", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(2, server.requestCount)
    }

    @Test fun conversionWithoutContextTokenUsesExplicitViewAndReturnedCurrentViewItem() = runBlocking {
        respond("""{"ticket":"video-ticket"}""")
        respond("""{"ticket":"original-ticket","library_view_id":8,"view_item_id":502}""")
        respond("[]")
        val spec = PlaybackSpec("영화", "source", itemId = 23, libraryId = 8, partId = 90, viewItemId = 501)
        val converted = client.convertPlayback(spec, 480, -100, null)
        assertEquals(502, converted.viewItemId)
        assertEquals(0L, converted.streamStartPositionMs)
        assertNull(converted.url.toHttpUrl().queryParameter("audio_stream_index"))
        val ticket = server.takeRequest().requestUrl!!
        assertEquals("8", ticket.queryParameter("library_view_id"))
        assertEquals("501", ticket.queryParameter("view_item_id"))
        server.takeRequest()
        assertEquals("502", server.takeRequest().requestUrl!!.queryParameter("view_item_id"))
    }

    @Test fun conversionPolicyAndCapabilityFailuresKeepServerReasonWithoutOriginalFallback() = runBlocking {
        val current = PlaybackSpec("영화", "unchanged", itemId = 23, libraryId = 8, partId = 90, progressToken = "context")
        for ((status, reason) in listOf(403 to "계정에서 영상 변환을 허용하지 않습니다.", 503 to "서버에 비디오 인코더가 없습니다.")) {
            respond(JSONObject().put("detail", reason).toString(), status)
            val failure = runCatching { client.convertPlayback(current, 720, 1000) }.exceptionOrNull() as ApiException
            assertEquals(status, failure.status)
            assertEquals(reason, failure.message)
            assertEquals("unchanged", current.url)
            assertEquals("/playback/transcode-ticket/90", server.takeRequest().requestUrl!!.encodedPath)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun unsupportedConversionChoiceIsRejectedBeforeNetwork() = runBlocking {
        val current = PlaybackSpec("영화", "source", itemId = 23, libraryId = 8, partId = 90, progressToken = "context")
        assertTrue(runCatching { client.convertPlayback(current, 1440, 0) }.exceptionOrNull() is ApiException)
        assertEquals(0, server.requestCount)
    }

    @Test fun automaticProgressivePlaybackSeparatesSubtitleTicketForAllVideoDeliveryModes() = runBlocking {
        for (mode in listOf("remux", "audio", "video")) {
            respond("""{"selected":{"part_id":90,"duration_sec":7200},"library_view_id":8,"view_item_id":501,
                "playback_context_token":"context","transcode":{"mode":"$mode","max_height":720}}""")
            metadata.enqueuePost("/playback/transcode-ticket/90", MockResponse().setBody("""{"ticket":"conversion-ticket"}"""))
            metadata.enqueuePost("/stream-ticket/90", MockResponse().setBody("""{"ticket":"subtitle-ticket"}"""))
            respondMetadata("/subtitles/90", """[{"id":4,"source":"embedded","format":"ass","stream_index":5,"playable":true}]""")
            respondMetadata("/playback/progress", "{}")
            val spec = client.playback(movie)
            assertEquals(mode, spec.url.toHttpUrl().queryParameter("mode"))
            assertEquals("conversion-ticket", spec.url.toHttpUrl().queryParameter("ticket"))
            assertEquals(if (mode == "video") 720 else null, spec.conversionHeight)
            assertEquals(0L, spec.streamStartPositionMs)
            assertEquals("external", spec.subtitles.single().source)
            assertNull(spec.subtitles.single().streamIndex)
            assertEquals("subtitle-ticket", spec.subtitles.single().url.toHttpUrl().queryParameter("ticket"))
            server.takeRequest()
            val enrichment = List(4) { server.takeRequest().requestUrl!!.encodedPath }
            assertEquals(setOf("/playback/transcode-ticket/90", "/stream-ticket/90", "/subtitles/90", "/playback/progress"), enrichment.toSet())
        }
    }

    @Test fun retryRenewsAutomaticProgressiveModeAndPreservesSourcePosition() = runBlocking {
        for (mode in listOf("remux", "audio", "video")) {
            respond("""{"ticket":"new-conversion-ticket"}""")
            respond("""{"ticket":"new-subtitle-ticket"}""")
            respond("[]")
            val current = PlaybackSpec("영화", server.url("/transcode/90?ticket=old&mode=$mode&max_height=720").toString(),
                itemId = 23, libraryId = 8, partId = 90, progressToken = "context", viewItemId = 501,
                durationMs = 7200000)
            val refreshed = client.renewPlayback(current, 25000, 2)
            assertEquals(mode, refreshed.url.toHttpUrl().queryParameter("mode"))
            assertEquals("25000", refreshed.url.toHttpUrl().queryParameter("start_ms"))
            assertEquals("2", refreshed.url.toHttpUrl().queryParameter("audio_stream_index"))
            assertEquals(25000L, refreshed.resumeMs)
            assertEquals(25000L, refreshed.streamStartPositionMs)
            assertEquals(7200000L, refreshed.durationMs)
            assertEquals(if (mode == "video") 720 else null, refreshed.conversionHeight)
            assertEquals(mode, server.takeRequest().requestUrl!!.queryParameter("mode"))
            server.takeRequest(); server.takeRequest()
        }
    }

    @Test fun originalRetryKeepsOriginalTransport() = runBlocking {
        respond("""{"ticket":"original-ticket"}""")
        respond("[]")
        val current = PlaybackSpec("영화", server.url("/stream/90?ticket=old").toString(),
            itemId = 23, libraryId = 8, partId = 90, progressToken = "context")
        val refreshed = client.renewPlayback(current, 25000)
        assertEquals("/stream/90", refreshed.url.toHttpUrl().encodedPath)
        assertEquals(25000L, refreshed.resumeMs)
        assertEquals(0L, refreshed.streamStartPositionMs)
        assertNull(refreshed.conversionHeight)
        assertEquals("/stream-ticket/90", server.takeRequest().requestUrl!!.encodedPath)
        server.takeRequest()
        assertEquals(2, server.requestCount)
    }

    @Test fun retryRejectsUnknownProgressiveModeBeforeNetwork() = runBlocking {
        val current = PlaybackSpec("영화", server.url("/transcode/90?mode=unknown").toString(),
            itemId = 23, libraryId = 8, partId = 90, progressToken = "context")
        assertTrue(runCatching { client.renewPlayback(current, 0) }.exceptionOrNull() is ApiException)
        assertEquals(0, server.requestCount)
    }

    @Test fun musicRetryKeepsItsExistingBitrateAndDedicatedStreamRoute() = runBlocking {
        respond("""{"ticket":"music-ticket"}""")
        respond("""{"ticket":"subtitle-ticket"}""")
        respond("[]")
        val current = PlaybackSpec("음악", server.url("/transcode/music/90?ticket=old&bitrate_kbps=192").toString(),
            itemId = 23, libraryId = 8, partId = 90, progressToken = "context")
        val refreshed = client.renewPlayback(current, 25000)
        assertEquals("/transcode/music/90", refreshed.url.toHttpUrl().encodedPath)
        assertEquals("192", refreshed.url.toHttpUrl().queryParameter("bitrate_kbps"))
        assertEquals("music", server.takeRequest().requestUrl!!.queryParameter("mode"))
        server.takeRequest(); server.takeRequest()
        assertEquals(3, server.requestCount)
    }

    @Test fun heroArtworkUsesActualServerArtAndPreservesDetailsWhenFieldsAreAbsent() {
        val item = client.parseMedia(JSONObject("""{"id":23,"title":"영화","art_url":"/art/23.jpg","summary":"작품 소개"}"""), 8, "movie")
        assertEquals(server.url("/art/23.jpg").toString(), item.backdrop)
        assertEquals("작품 소개", item.summary)
        val refreshed = client.parseMedia(JSONObject("""{"id":23,"title":"영화"}"""), 8, "movie", item)
        assertEquals(item.backdrop, refreshed.backdrop)
        assertEquals(item.summary, refreshed.summary)
    }

    @Test fun optionalMarkersUseDocumentedSelectedPartMediaInfoAndPlaybackContext() = runBlocking {
        respond("""{"markers":[{"segment_type":"intro","start_ms":0,"end_ms":9000},
            {"segment_type":"credits","start_ms":90000,"end_ms":110000}]}""")
        val spec = PlaybackSpec("작품", "source", itemId = 23, libraryId = 8, partId = 90,
            progressToken = "context &?", viewItemId = 501, durationMs = 100000)
        assertEquals(listOf(PlaybackMarker("intro", 0, 9000), PlaybackMarker("credits", 90000, 100000)), client.playbackMarkers(spec))
        val request = server.takeRequest()
        assertEquals("/libraries/8/media_info/90", request.requestUrl!!.encodedPath)
        assertEquals("context &?", request.requestUrl!!.queryParameter("playback_context_token"))
        assertNull(request.requestUrl!!.queryParameter("admin"))
    }

    @Test fun browseMarkersSeedPlaybackWithoutAnotherBlockingRequest() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
        respond("""{"ticket":"ticket"}""")
        respondMetadata("/subtitles/90", "[]")
        respondMetadata("/playback/progress", "{}")
        val item = client.parseMedia(JSONObject("""{"id":23,"title":"작품","view_item_id":501,
            "markers":[{"type":"intro","start_ms":0,"end_ms":9000}]}"""), 8, "movie")
        val spec = client.playback(item)
        assertEquals(listOf(PlaybackMarker("intro", 0, 9000)), spec.markers)
        assertTrue(spec.sourceItem!!.markers.isEmpty())
        assertEquals(4, server.requestCount)
    }

    @Test fun unavailableOptionalMarkerFieldPreservesKnownMarkers() = runBlocking {
        respond("""{"parts":[{"id":90}]}""")
        val marker = PlaybackMarker("intro", 0, 9_000)
        val spec = PlaybackSpec("작품", "source", itemId = 23, libraryId = 8, partId = 90,
            viewItemId = 501, durationMs = 100_000, markers = listOf(marker))
        assertEquals(listOf(marker), client.playbackMarkers(spec))
        assertEquals(1, server.requestCount)
    }

    @Test fun explicitlyEmptySelectedFileMarkersClearKnownMarkers() = runBlocking {
        respond("""{"markers":[]}""")
        val spec = PlaybackSpec("작품", "source", itemId = 23, libraryId = 8, partId = 90,
            viewItemId = 501, durationMs = 100_000, markers = listOf(PlaybackMarker("intro", 0, 9_000)))
        assertTrue(client.playbackMarkers(spec).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun externalSubtitlesKeepOriginalFormatAndConversionFallbackWithScopedContext() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"part context&?","decision":"direct_play"}""")
        respond("""{"ticket":"ticket &?"}""")
        respondMetadata("/subtitles/90", """{"subtitles":[
            {"id":4,"label":"한국어 ASS","source":"external","format":"ass","playable":true},
            {"id":5,"label":"한국어 SMI","source":"external","format":"smi","playable":true},
            {"id":6,"source":"embedded","format":"ass","playable":true},
            {"id":7,"source":"external","format":"pgs","playable":false},
            {"id":8,"source":"external","format":"srt","playable":false}
        ]}""")
        respondMetadata("/playback/progress", "{}")
        val spec = client.playback(movie)
        assertEquals(listOf(4, 5, 6), spec.subtitles.map { it.id })
        val ass = spec.subtitles[0]
        val smi = spec.subtitles[1]
        val embedded = spec.subtitles[2]
        assertEquals("text/x-ssa", ass.mimeType)
        assertEquals("application/x-sami", smi.mimeType)
        assertEquals("/subtitles/raw/4", ass.url.toHttpUrl().encodedPath)
        assertEquals("/subtitles/raw/5", smi.url.toHttpUrl().encodedPath)
        assertEquals("/subtitles/track/4.vtt", ass.fallbackUrl!!.toHttpUrl().encodedPath)
        assertEquals("text/vtt", ass.fallbackMimeType)
        assertEquals("/subtitles/track/6.vtt", embedded.url.toHttpUrl().encodedPath)
        assertNull(embedded.fallbackUrl)
        for (track in spec.subtitles) {
            for (url in listOfNotNull(track.url, track.fallbackUrl).map { it.toHttpUrl() }) {
                assertEquals(server.hostName, url.host)
                assertEquals(server.port, url.port)
                assertEquals("ticket &?", url.queryParameter("ticket"))
                assertEquals("part context&?", url.queryParameter("playback_context_token"))
            }
            assertTrue(track.headers.isEmpty())
            assertFalse(track.url.contains("account-token"))
        }
    }

    @Test fun subtitleUrlsCarryViewIdentityWhenContextTokenIsUnavailable() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"transcode":{"mode":"video"}}""")
        respond("""{"ticket":"transcode-ticket"}""")
        respond("""{"ticket":"subtitle-ticket"}""")
        respondMetadata("/subtitles/90", """[{"id":4,"source":"external","format":"srt","playable":true}]""")
        respondMetadata("/playback/progress", "{}")
        val track = client.playback(movie).subtitles.single()
        for (url in listOf(track.url, track.fallbackUrl!!).map { it.toHttpUrl() }) {
            assertEquals("subtitle-ticket", url.queryParameter("ticket"))
            assertEquals("8", url.queryParameter("library_view_id"))
            assertEquals("501", url.queryParameter("view_item_id"))
            assertNull(url.queryParameter("playback_context_token"))
        }
        server.takeRequest()
        assertEquals("/playback/transcode-ticket/90?library_view_id=8&view_item_id=501&mode=video", server.takeRequest().path)
        val enrichment = List(3) { server.takeRequest() }.associateBy { it.requestUrl!!.encodedPath }
        assertEquals("/stream-ticket/90?library_view_id=8&view_item_id=501", enrichment.getValue("/stream-ticket/90").path)
        assertEquals("/subtitles/90?library_view_id=8&view_item_id=501", enrichment.getValue("/subtitles/90").path)
        assertEquals("8", enrichment.getValue("/playback/progress").requestUrl!!.queryParameter("library_view_id"))
    }

    @Test fun preferredSubtitleKeepsSourceLanguageAndGlobalIndexWithoutInventingNativeOrdinal() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context",
            "subtitle":{"id":6,"source":"embedded","lang_code":"ko","stream_index":7,"stream_ordinal":null,"delivery":"embedded"}}""")
        respond("""{"ticket":"scoped-ticket"}""")
        respondMetadata("/subtitles/90", """[
            {"id":4,"label":"English","source":"embedded","format":"ass","lang_code":"en","stream_index":3,"playable":true,"forced":true},
            {"id":5,"label":"한국어 PGS","source":"embedded","format":"pgs","lang_code":"ko","stream_index":4,"playable":false},
            {"id":6,"label":"한국어 ASS","source":"embedded","format":"ASS","lang_code":"ko","stream_index":7,"playable":true,"forced":false,"sdh":true,"title":"Korean Full"},
            {"id":8,"label":"한국어 SRT","source":"external","format":"srt","lang_code":"ko","stream_index":null,"playable":true,"forced":null,"sdh":false,"title":null}
        ]""")
        respondMetadata("/playback/progress", "{}")
        val spec = client.playback(movie)
        assertEquals(6, spec.selectedSubtitleId)
        assertEquals(listOf(4, 6, 8), spec.subtitles.map { it.id })
        assertEquals("en", spec.subtitles.first().language)
        assertEquals(true, spec.subtitles.first().forced)
        assertNull(spec.subtitles.first().sdh)
        assertNull(spec.subtitles.first().title)
        val selected = spec.subtitles.single { it.id == spec.selectedSubtitleId }
        assertEquals("embedded", selected.source)
        assertEquals("ko", selected.language)
        // FFprobe's global index is metadata only; filtering unsupported rows must
        // never turn it into an invented Media3 type-relative track ordinal.
        assertEquals(7, selected.streamIndex)
        assertEquals("ass", selected.format)
        assertEquals(false, selected.forced)
        assertEquals(true, selected.sdh)
        assertEquals("Korean Full", selected.title)
        val external = spec.subtitles.last()
        assertEquals("external", external.source)
        assertNull(external.streamIndex)
        assertEquals("srt", external.format)
        assertNull(external.forced)
        assertEquals(false, external.sdh)
        assertNull(external.title)
        assertEquals("/subtitles/raw/8", external.url.toHttpUrl().encodedPath)
    }

    @Test fun catalogCompletionComesFromFreshBatchedProgressIncludingClearedHistory() = runBlocking {
        respond("""[
            {"id":23,"title":"완료","view_item_id":501},
            {"id":24,"title":"이어보기","view_item_id":502,"completed":true,"position_ms":9000},
            {"id":25,"title":"삭제된 이력","view_item_id":503,"completed":true,"position_ms":9000}
        ]""")
        respond("""{"items":[
            {"metadata_item_id":23,"completed":true,"position_ms":7100000,"duration_ms":7200000},
            {"metadata_item_id":24,"completed":false,"position_ms":42000,"duration_ms":7200000}
        ]}""")
        val items = client.items(Library(8, "영화", "movie")).items
        assertTrue(items[0].watched)
        assertEquals(0L, items[0].resumeMs)
        assertEquals(7200000L, items[0].durationMs)
        assertFalse(items[1].watched)
        assertEquals(42000L, items[1].resumeMs)
        assertFalse(items[2].watched)
        assertEquals(0L, items[2].resumeMs)
        server.takeRequest()
        assertEquals("/playback/progress?item_ids=23,24,25&library_view_id=8", server.takeRequest().path)
    }

    @Test fun parsedCompletedRowRetainsWatchedFlagAndExplicitZeroClearsOldResume() {
        val previous = movie.copy(resumeMs = 42000, watched = true)
        assertTrue(client.parseMedia(JSONObject("""{"id":23,"completed":true,"position_ms":7100000}"""), 8, "movie").watched)
        val reset = client.parseMedia(JSONObject("""{"id":23,"completed":false,"position_ms":0}"""), 8, "movie", previous)
        assertFalse(reset.watched)
        assertEquals(0L, reset.resumeMs)
        assertTrue(client.parseMedia(JSONObject("""{"id":23,"title":"Updated"}"""), 8, "movie", previous).watched)
    }

    @Test fun playbackRejectsMissingViewIdentityBeforeNetwork() = runBlocking {
        val failure = runCatching { client.playback(movie.copy(viewItemId = null)) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals(0, server.requestCount)
    }

    @Test fun watchedSwitchUsesExplicitPutWithExactPartAndViewForBothStates() = runBlocking {
        for (watched in listOf(true, false)) {
            respond("""{"options":[{"part_id":88,"metadata_item_id":24},{"part_id":90,"metadata_item_id":23}]}""")
            respond("""{"ok":true,"watched":$watched}""")
            client.setWatched(movie, watched)
            val versions = server.takeRequest()
            assertEquals("POST", versions.method)
            assertEquals("/playback/versions", versions.path)
            val selection = JSONObject(versions.body.readUtf8())
            assertEquals(23, selection.getInt("metadata_item_id"))
            assertEquals(8, selection.getInt("library_view_id"))
            assertEquals(501, selection.getInt("view_item_id"))
            val change = server.takeRequest()
            assertEquals("PUT", change.method)
            assertEquals("/continue-watching/23/watch-state", change.path)
            assertEquals("Bearer account-token", change.getHeader("Authorization"))
            val state = JSONObject(change.body.readUtf8())
            assertEquals(90, state.getInt("part_id"))
            assertEquals(watched, state.getBoolean("watched"))
            assertEquals(8, state.getInt("library_view_id"))
            assertEquals(501, state.getInt("view_item_id"))
            assertEquals(4, state.length())
            assertFalse(state.has("position_ms"))
            assertFalse(state.has("state"))
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun watchedSwitchCanUseMatchingSourceInfoWithoutStartingPlayback() = runBlocking {
        respond("""{"options":[],"source_info":{"part_id":90,"metadata_item_id":23}}""")
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(movie, true)
        assertEquals("/playback/versions", server.takeRequest().path)
        val change = server.takeRequest()
        assertEquals("/continue-watching/23/watch-state", change.path)
        assertEquals(90, JSONObject(change.body.readUtf8()).getInt("part_id"))
        assertEquals(2, server.requestCount)
    }

    @Test fun watchedSwitchRejectsMissingViewIdentityBeforeAnyRequest() = runBlocking {
        val failure = runCatching { client.setWatched(movie.copy(viewItemId = null), true) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals(0, server.requestCount)
    }

    @Test fun watchedSwitchUsesActualMetadataOfAuthorizedMergedMovieVersion() = runBlocking {
        respond("""{"options":[{"part_id":88,"metadata_item_id":24}],"source_info":{"part_id":88,"metadata_item_id":24}}""")
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(movie, true)
        server.takeRequest()
        val change = server.takeRequest()
        assertEquals("/continue-watching/24/watch-state", change.path)
        val data = JSONObject(change.body.readUtf8())
        assertEquals(88, data.getInt("part_id"))
        assertEquals(8, data.getInt("library_view_id"))
        assertEquals(501, data.getInt("view_item_id"))
        assertEquals(2, server.requestCount)
    }

    @Test fun watchedSwitchPropagatesServerRejectionWithoutPretendingSaved() = runBlocking {
        respond("""{"options":[{"part_id":90,"metadata_item_id":23}]}""")
        respond("""{"detail":"이 항목의 시청 상태를 변경할 수 없습니다."}""", 403)
        val failure = runCatching { client.setWatched(movie, false) }.exceptionOrNull() as ApiException
        assertEquals(403, failure.status)
        assertEquals("이 항목의 시청 상태를 변경할 수 없습니다.", failure.message)
    }

    @Test fun progressSavesContextMillisecondsAndActualWatchedTime() = runBlocking {
        respond("{}")
        val spec = PlaybackSpec("영화", "scoped-url", itemId = 23, libraryId = 8, partId = 90,
            sessionId = "player-session", progressToken = "part-context", viewItemId = 501, decision = "direct_play")
        client.saveProgress(spec, 45000, 7200000, "paused", 12000)
        val request = server.takeRequest()
        assertEquals("/playback/progress", request.path)
        val json = JSONObject(request.body.readUtf8())
        assertEquals(45000L, json.getLong("position_ms"))
        assertEquals(12000L, json.getLong("watched_ms"))
        assertEquals(7200000L, json.getLong("duration_ms"))
        assertEquals(23, json.getInt("metadata_item_id"))
        assertEquals(8, json.getInt("library_view_id"))
        assertEquals(501, json.getInt("view_item_id"))
        assertEquals("part-context", json.getString("playback_context_token"))
        assertEquals("player-session", json.getString("client_session_id"))
        assertEquals("paused", json.getString("state"))
        assertFalse(json.has("library_section_id"))
    }

    @Test fun unknownDurationDoesNotOverwriteServerDurationWithZero() {
        val spec = PlaybackSpec("영화", "scoped-url", itemId = 23, libraryId = 8, partId = 90)
        val json = client.progressBody(spec, -100, 0, "stopped", null)
        assertEquals(0, json.getLong("position_ms"))
        assertTrue(json.isNull("duration_ms"))
        assertFalse(json.has("watched_ms"))
    }

    @Test fun clientEofReportsCompletionCandidateForServerJudgment() {
        val spec = PlaybackSpec("영화", "scoped-url", itemId = 23, libraryId = 8, partId = 90)
        assertEquals("completed_candidate", client.progressBody(spec, 7200000, 7200000, "completed", 7000000).getString("state"))
    }

    @Test fun completedProgressStartsReplayAtBeginning() = runBlocking {
        respond("""{"selected":{"part_id":90,"duration_sec":7200},"library_view_id":8,"view_item_id":501,"playback_context_token":"part-context","decision":"direct_play"}""")
        respond("""{"ticket":"scoped-ticket"}""")
        respondMetadata("/subtitles/90", "[]")
        respondMetadata("/playback/progress", """{"items":[{"metadata_item_id":23,"position_ms":7100000,"duration_ms":7200000,"completed":true,"state":"paused"}]}""")
        assertEquals(0L, client.playback(movie).resumeMs)
    }

    @Test fun continueWatchingParsesMetadataIdAndViewIdentity() = runBlocking {
        respond("""{"items":[{"metadata_item_id":23,"library_view_id":8,"view_item_id":501,"item_type":"movie","title":"영화","position_ms":54000,"duration_ms":7200000},{"metadata_item_id":24,"library_view_id":8,"item_type":"book","title":"Book"}]}""")
        val item = client.continueWatching().single()
        assertEquals(23, item.id)
        assertEquals(8, item.libraryId)
        assertEquals(501, item.viewItemId)
        assertEquals(54000L, item.resumeMs)
    }

    @Test fun continueEpisodeDirectPlaybackUsesItsOwnIdentityLatestResumeAndPoster() = runBlocking {
        respond("""{"items":[{"id":999,"metadata_item_id":24,"library_view_id":9,"view_item_id":601,"item_type":"episode",
            "title":"회차 제목","show_title":"시리즈 제목","subtitle":"시즌 2 · 4화","season_number":2,"episode_number":4,
            "poster_url":"/series-poster.jpg","thumb_url":"/episode-still.jpg","position_ms":65000,"duration_ms":1800000}]}""")
        val item = client.continueWatching().single()
        assertEquals(24, item.id)
        assertEquals("시리즈 제목", item.showTitle)
        assertEquals("시즌 2 · 4화", item.displaySubtitle)
        assertEquals(2, item.seasonNumber)
        assertEquals(4, item.episodeNumber)
        assertEquals(server.url("/series-poster.jpg").toString(), item.poster)
        assertEquals("/continue-watching?limit=30", server.takeRequest().path)

        respond("""{"selected":{"part_id":90},"library_view_id":9,"view_item_id":601,"playback_context_token":"continue-context"}""")
        respond("""{"ticket":"continue-ticket"}""")
        respondMetadata("/subtitles/90", "[]")
        respondMetadata("/playback/progress", """{"items":[{"metadata_item_id":24,"position_ms":76000,"duration_ms":1800000,"completed":false}]}""")
        val spec = client.playback(item)
        assertEquals(76000L, spec.resumeMs)
        assertEquals(24, spec.itemId)
        assertEquals(9, spec.libraryId)
        assertEquals(601, spec.viewItemId)
        val decisionRequest = server.takeRequest()
        assertEquals("/playback/decision", decisionRequest.path)
        val decision = JSONObject(decisionRequest.body.readUtf8())
        assertEquals(24, decision.getInt("metadata_item_id"))
        assertEquals(9, decision.getInt("library_view_id"))
        assertEquals(601, decision.getInt("view_item_id"))
        assertFalse("A direct continue item must not pin an unrelated selected version", decision.has("part_id"))
        assertEquals(setOf("/stream-ticket/90", "/subtitles/90", "/playback/progress"),
            List(3) { server.takeRequest().requestUrl!!.encodedPath }.toSet())
        assertEquals("Continue playback needs no detail-navigation requests", 5, server.requestCount)
    }

    @Test fun continueResumeFallsBackToCardPositionWhenProgressHasNoNewRecord() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
        respond("""{"ticket":"ticket"}""")
        respondMetadata("/subtitles/90", "[]")
        respondMetadata("/playback/progress", "{}")
        assertEquals(65000L, client.playback(movie.copy(resumeMs = 65000)).resumeMs)
    }

    @Test fun cancellingCoroutineCancelsPendingHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { client.me() }
        // Do not block the coroutine's own event loop while waiting for request delivery.
        val observed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            server.takeRequest(2, TimeUnit.SECONDS)
        }
        assertNotNull(observed)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
