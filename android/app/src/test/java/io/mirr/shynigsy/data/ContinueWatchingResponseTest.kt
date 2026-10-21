package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ContinueWatchingResponseTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val row = """{"metadata_item_id":24,"library_view_id":9,"view_item_id":601,
        "type":"episode","title":"회차","season_number":0,"episode_number":2,"position_ms":45000}"""

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun respond(text: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(text))
    private suspend fun unlock() {
        respond("""{"token":"spc_test-only-grant","expires_in":300}""")
        client.unlockSpoiler("1234")
        server.takeRequest()
    }

    @Test fun documentedItemsEnvelopePreservesExactPlaybackAndProgressContext() = runBlocking {
        respond("""{"items":[$row]}""")
        val item = client.continueWatching().single()
        assertEquals(24, item.id)
        assertEquals(9, item.libraryId)
        assertEquals(601, item.viewItemId)
        assertEquals(0, item.seasonNumber)
        assertEquals(45000L, item.resumeMs)
        assertFalse(item.isSpoiler)
        val request = server.takeRequest()
        assertEquals("/continue-watching?limit=30", request.path)
        assertNull(request.getHeader("X-Shyni-Spoiler"))
    }

    @Test fun legacyTopArrayAndResultsEnvelopeRemainSupported() = runBlocking {
        for (text in listOf("[$row]", """{"results":[$row]}""")) {
            respond(text)
            assertEquals(24, client.continueWatching().single().id)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun onlyExplicitEmptyArraysAreAuthoritativeEmptyHistory() = runBlocking {
        for (text in listOf("[]", """{"items":[]}""", """{"results":[]}""")) {
            respond(text)
            assertTrue(client.continueWatching().isEmpty())
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun missingNullAndWrongTypedCollectionsFailRatherThanErasingHistory() = runBlocking {
        val malformed = listOf("{}", """{"detail":"temporary problem"}""", """{"items":null}""",
            """{"items":{}}""", """{"items":"not a list"}""", """{"items":1}""",
            """{"results":null}""", """{"results":{}}""", """{"results":false}""",
            """{"items":null,"results":[]}""", """{"items":[],"results":null}""")
        for (text in malformed) {
            respond(text)
            val error = runCatching { client.continueWatching() }.exceptionOrNull()
            assertTrue(error is ContinueWatchingResponseException)
            assertTrue(error is IOException)
            assertEquals(ContinueWatchingResponseException().message, error!!.message)
        }
        assertEquals(malformed.size, server.requestCount)
    }

    @Test fun successfulHtmlInvalidJsonAndScalarBodiesReturnOnlyGenericTypedFailure() = runBlocking {
        val marker = "synthetic-signed-marker"
        val malformed = listOf("<!doctype html><html><body>$marker</body></html>",
            "{", "\"$marker\"", "42", "true", "null", "")
        for ((index, text) in malformed.withIndex()) {
            respond(text)
            val error = runCatching { client.continueWatching() }.exceptionOrNull()
            assertTrue("payload #$index", error is ContinueWatchingResponseException)
            // Coroutine stack-trace recovery can copy a callback exception and
            // retain the sanitized original as its cause. Check all copies so a
            // parser exception or raw response can never leak through that chain.
            var cause: Throwable? = error
            var depth = 0
            while (cause != null) {
                assertTrue("payload #$index has a cyclic/deep cause chain", depth++ < 8)
                assertTrue("payload #$index cause", cause is ContinueWatchingResponseException)
                assertEquals("payload #$index cause message", ContinueWatchingResponseException().message, cause.message)
                assertFalse("payload #$index leaks response", cause.message.orEmpty().contains(marker))
                cause = cause.cause
            }
        }
        assertEquals(malformed.size, server.requestCount)
    }

    @Test fun malformedArrayMembersNeverBecomeAnAuthoritativeEmptyHistory() = runBlocking {
        for (text in listOf("[null,42]", """{"items":[null,42]}""", """{"results":[$row,"invalid"]}""")) {
            respond(text)
            assertTrue(runCatching { client.continueWatching() }.exceptionOrNull() is ContinueWatchingResponseException)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun httpAuthorizationAndServerFailuresKeepTheirExistingStatus() = runBlocking {
        for (code in listOf(401, 403, 503)) {
            respond("<html>unavailable</html>", code)
            val error = runCatching { client.continueWatching() }.exceptionOrNull() as ApiException
            assertEquals(code, error.status)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun nativeContinueUsesSameEnvelopeValidationWithoutChangingPrivateOrigin() = runBlocking {
        unlock()
        respond("""{"results":[$row]}""")
        val item = client.spoilerContinueWatching().single()
        assertTrue(item.isSpoiler)
        assertEquals(9, item.libraryId)
        assertEquals(601, item.viewItemId)
        val request = server.takeRequest()
        assertEquals("/spoiler/client/continue-watching?limit=30", request.path)
        assertEquals("spc_test-only-grant", request.getHeader("X-Shyni-Spoiler"))
        respond("""{"items":null}""")
        assertTrue(runCatching { client.spoilerContinueWatching() }.exceptionOrNull() is ContinueWatchingResponseException)
        assertTrue(client.spoilerUnlocked)
        assertEquals(3, server.requestCount)
    }

    @Test fun validEnvelopeDoesNotRelaxExistingRemoteTypeOrViewFilters() = runBlocking {
        respond("""{"items":[$row,
            {"metadata_item_id":25,"library_view_id":9,"view_item_id":602,"type":"episode","remote":true},
            {"metadata_item_id":26,"library_view_id":9,"view_item_id":603,"type":"episode","federated_id":"remote"},
            {"metadata_item_id":27,"library_view_id":9,"view_item_id":604,"type":"book"},
            {"metadata_item_id":28,"view_item_id":605,"type":"movie"}]}""")
        assertEquals(listOf(24), client.continueWatching().map { it.id })
        assertEquals(1, server.requestCount)
    }
}
