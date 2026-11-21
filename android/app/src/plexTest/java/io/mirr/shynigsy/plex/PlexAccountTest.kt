package io.mirr.shynigsy.plex

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexAccountTest {
    @Test fun enteredAliasRequiresMatchingAnonymousPmsIdentity() = runBlocking {
        MockWebServer().use { cloud ->
            MockWebServer().use { alias ->
                cloud.start()
                alias.start()
                val account = PlexAccount("test-device", resourcesBase = cloud.url("/api/v2/"))
                val server = alias.url("/proxy").toString()
                val resources = """[{"clientIdentifier":"machine-a","name":"Home","provides":"server",
                    "accessToken":"pms-token","connections":[{"uri":"https://official.example"}]}]"""
                val identities = listOf(
                    """{"MediaContainer":{"machineIdentifier":"machine-b"}}""" to false,
                    """<?xml version="1.0"?><MediaContainer machineIdentifier="machine-a"/>""" to true,
                    """{"MediaContainer":{"machineIdentifier":"machine-a"}}""" to true,
                )
                for ((identity, matches) in identities) {
                    cloud.enqueue(MockResponse().setBody(resources))
                    alias.enqueue(MockResponse().setBody(identity))
                    val selected = account.servers("account-token", server).single()
                    assertEquals(matches, selected.matches(server))
                    assertEquals(if (matches) server else "https://official.example", selected.urls.first())
                    val probe = alias.takeRequest()
                    assertEquals("/proxy/identity", probe.path)
                    assertNull(probe.getHeader("X-Plex-Token"))
                    assertEquals("account-token", cloud.takeRequest().getHeader("X-Plex-Token"))
                }
            }
        }
    }

    @Test fun duplicateMachineListingsJoinAddressesAndDuplicateNamesStayDistinct() {
        val resources = org.json.JSONArray("""[
            {"clientIdentifier":"first","name":"Home","provides":"server","accessToken":"a","connections":[{"uri":"https://first.example"}]},
            {"clientIdentifier":"first","name":"Home","provides":"server","accessToken":"a","connections":[{"uri":"https://alternate.example"}]},
            {"clientIdentifier":"other","name":"Home","provides":"server","accessToken":"b","connections":[{"uri":"https://other.example"}]}
        ]""")
        val servers = parseServerConnections(resources)
        assertEquals(listOf("first", "other"), servers.map { it.id })
        assertEquals(listOf("https://first.example", "https://alternate.example"), servers.first().urls)
        assertTrue(servers.last().matches("other.example/"))
    }

    @Test fun codeClaimAndServerSelectionUseSeparateTokens() = runBlocking {
        MockWebServer().use { plex ->
            plex.start()
            val base = plex.url("/api/v2/")
            val account = PlexAccount("test-device", base, base)
            plex.enqueue(MockResponse().setBody("""{"id":7,"code":"ABCD","expiresIn":180}"""))
            plex.enqueue(MockResponse().setBody("""{"authToken":null}"""))
            plex.enqueue(MockResponse().setBody("""{"authToken":"account-token"}"""))
            plex.enqueue(MockResponse().setBody("""[
                {"name":"My TV","provides":"player","accessToken":"player-token","connections":[]},
                {"name":"Films","provides":"server","accessToken":"server-token","connections":[
                    {"uri":"https://relay.example","relay":true},
                    {"uri":"http://public.example","local":false},
                    {"uri":"https://direct.example","local":true}
                ]}
            ]"""))
            val code = account.createCode()
            assertEquals("ABCD", code.code)
            assertNull(account.tokenFor(code))
            val servers = account.servers(requireNotNull(account.tokenFor(code)))
            val films = servers.single()
            assertEquals(listOf("https://direct.example", "https://relay.example"), films.urls)
            assertEquals("server-token", films.token)
            val attempted = mutableListOf<String>()
            val result = films.connectFirstAvailable("https://unadvertised.example") {
                attempted += it
                if (it == films.urls.first()) throw java.io.IOException("Unavailable")
                it
            }
            assertEquals(films.urls, attempted)
            assertEquals(films.urls.last(), result)
            assertEquals(films.urls.last(), films.connectFirstAvailable("relay.example") { it })
            val create = plex.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/v2/pins", create.path)
            repeat(2) {
                val poll = plex.takeRequest()
                assertEquals("/api/v2/pins/7?code=ABCD", poll.path)
                assertEquals("test-device", poll.getHeader("X-Plex-Client-Identifier"))
                assertNull(poll.getHeader("X-Plex-Token"))
            }
            assertEquals("account-token", plex.takeRequest().getHeader("X-Plex-Token"))
        }
    }

    @Test fun browserSignInUsesStrongPinAndEncodedAuthFragment() = runBlocking {
        MockWebServer().use { plex ->
            plex.start()
            val account = PlexAccount("test-device", pinsBase = plex.url("/api/v2/"))
            plex.enqueue(MockResponse().setBody("""{"id":8,"code":"abc+def&ghi#j","expiresIn":900}"""))
            val code = account.createCode(strong = true)
            val create = plex.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/v2/pins?strong=true", create.path)
            assertEquals("PLEXMINI", create.getHeader("X-Plex-Product"))
            assertEquals("test-device", create.getHeader("X-Plex-Client-Identifier"))

            val login = account.signInUrl(code).toHttpUrl()
            assertEquals("https://app.plex.tv/auth", login.newBuilder().fragment(null).build().toString())
            val fragment = requireNotNull(login.encodedFragment)
            assertTrue(fragment.startsWith("?"))
            val parameters = login.newBuilder().encodedQuery(fragment.removePrefix("?")).build()
            assertEquals(setOf("clientID", "code", "context[device][product]"), parameters.queryParameterNames)
            assertEquals("test-device", parameters.queryParameter("clientID"))
            assertEquals(code.code, parameters.queryParameter("code"))
            assertEquals("PLEXMINI", parameters.queryParameter("context[device][product]"))
        }
    }
}
