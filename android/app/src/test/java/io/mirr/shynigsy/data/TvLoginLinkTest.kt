package io.mirr.shynigsy.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class TvLoginLinkTest {
    private val nonce = "Ab_09-".repeat(7) + "Z"
    private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun rawLink(server: String, code: String = nonce): String =
        "shyniflex://tv-login?server=${encoded(server)}&nonce=${encoded(code)}"

    @Test fun qrRoundTripContainsOnlyCanonicalOriginAndPublicNonce() {
        val uri = tvLoginQrUrl("https://MEDIA.example.test:8443/", nonce)
        assertEquals(TvLoginLink("https://media.example.test:8443", nonce), parseTvLoginLink(uri))
        assertFalse(uri.contains("claim_secret"))
        assertFalse(uri.contains("token="))
        assertFalse(uri.contains("password"))
    }

    @Test fun ipv6OriginIsEncodedAndRoundTripsWithoutLosingItsPort() {
        val origin = "https://[2001:db8::1]:8443"
        val uri = tvLoginQrUrl(origin, nonce)
        assertTrue(uri.contains("%5B2001%3Adb8%3A%3A1%5D"))
        assertEquals(TvLoginLink(origin, nonce), parseTvLoginLink(uri))
    }

    @Test fun unicodeIdnBecomesTheSameCanonicalHttpsOrigin() {
        val parsed = parseTvLoginLink(tvLoginQrUrl("https://예시.한국:8443", nonce))!!
        assertTrue(parsed.server.startsWith("https://xn--"))
        assertEquals(nonce, parsed.nonce)
        assertTrue(sameTvLoginServer("https://예시.한국:8443/", parsed.server))
    }

    @Test fun defaultHttpsPortAndHostCaseAreEquivalentButOtherOriginsAreNot() {
        assertTrue(sameTvLoginServer("https://EXAMPLE.test:443/", "https://example.test"))
        assertFalse(sameTvLoginServer("https://example.test:8443", "https://example.test"))
        assertFalse(sameTvLoginServer("https://example.test", "https://other.example.test"))
        assertFalse(sameTvLoginServer("http://example.test", "https://example.test"))
        assertFalse(sameTvLoginServer("https://user@example.test", "https://example.test"))
    }

    @Test fun queryOrderAndPercentEncodingDoNotChangeIdentity() {
        val uri = "SHYNIFLEX://TV-LOGIN?nonce=${encoded(nonce)}&server=${encoded("https://example.test:443/")}"
        assertEquals(TvLoginLink("https://example.test", nonce), parseTvLoginLink(uri))
    }

    @Test fun missingDuplicateAndEncodedDuplicateParametersAreRejected() {
        val valid = rawLink("https://example.test")
        for (uri in listOf(
            "shyniflex://tv-login?nonce=$nonce", "shyniflex://tv-login?server=https%3A%2F%2Fexample.test",
            "$valid&nonce=$nonce", "$valid&%6Eonce=$nonce", "$valid&server=https%3A%2F%2Fother.test",
            "$valid&%73erver=https%3A%2F%2Fother.test", "$valid&", valid.replace("?server", "?&server"),
        )) assertNull(uri, parseTvLoginLink(uri))
    }

    @Test fun secretsAndAllUnknownQueryFieldsAreRejected() {
        val valid = rawLink("https://example.test")
        for (key in listOf("claim_secret", "ticket", "token", "password", "username", "redirect_uri", "source")) {
            assertNull(key, parseTvLoginLink("$valid&$key=untrusted"))
        }
    }

    @Test fun wrongSchemeAuthorityPathPortAndFragmentsCannotOpenApproval() {
        val query = "server=${encoded("https://example.test")}&nonce=$nonce"
        for (uri in listOf(
            "https://tv-login?$query", "shyniflex://other?$query", "shyniflex://user@tv-login?$query",
            "shyniflex://@tv-login?$query", "shyniflex://tv-login:443?$query", "shyniflex://tv-login:?$query",
            "shyniflex://tv-login/?$query", "shyniflex://tv-login/approve?$query", "shyniflex://tv-login%2F?$query",
            "shyniflex://tv-login?$query#nonce=other", "shyniflex://tv-login?$query#",
        )) assertNull(uri, parseTvLoginLink(uri))
    }

    @Test fun serverCredentialsAndEveryNonOriginComponentAreRejected() {
        for (origin in listOf(
            "http://example.test", "ftp://example.test", "https://user:secret@example.test", "https://@example.test",
            "https://example.test/path", "https://example.test/..", "https://example.test/%2e", "https://example.test//",
            "https://example.test?token=secret", "https://example.test?", "https://example.test#fragment", "https://example.test#",
            "https://example.test:65536", "https://example.test:", "https:\\example.test", " https://example.test",
            "https://[broken-ipv6", "https://example.test/%ZZ",
        )) {
            assertNull(origin, parseTvLoginLink(rawLink(origin)))
            assertThrows(origin, IllegalArgumentException::class.java) { tvLoginQrUrl(origin, nonce) }
        }
    }

    @Test fun invalidNonceLengthAlphabetOrPaddingIsRejected() {
        for (code in listOf("", "a".repeat(31), "a".repeat(257), "a".repeat(32) + "=", "a".repeat(32) + "/",
            "a".repeat(32) + " ", "a".repeat(32) + "\n", "a".repeat(32) + "%41")) {
            assertNull(parseTvLoginLink(rawLink("https://example.test", code)))
            assertThrows(IllegalArgumentException::class.java) { tvLoginQrUrl("https://example.test", code) }
        }
    }

    @Test fun nonceBoundariesAreAcceptedWithoutTruncation() {
        for (size in listOf(32, 256)) {
            val code = "a".repeat(size)
            assertEquals(code, parseTvLoginLink(tvLoginQrUrl("https://example.test", code))!!.nonce)
        }
    }

    @Test fun malformedEscapeAndDoubleEncodedServerCannotBeReinterpreted() {
        assertNull(parseTvLoginLink("shyniflex://tv-login?server=%ZZ&nonce=$nonce"))
        assertNull(parseTvLoginLink("shyniflex://tv-login?server=${encoded(encoded("https://example.test"))}&nonce=$nonce"))
        assertNull(parseTvLoginLink("shyniflex://tv-login?server=https%3A%2F%2Fexample.test&nonce=%"))
    }

    @Test fun controlsWhitespaceAndOversizeOuterLinksAreRejected() {
        val valid = rawLink("https://example.test")
        assertNull(parseTvLoginLink(" $valid"))
        assertNull(parseTvLoginLink("$valid\n"))
        assertNull(parseTvLoginLink("$valid&" + "a".repeat(4096)))
        assertNull(parseTvLoginLink(""))
    }

    @Test fun invalidServerComparisonNeverThrowsOrTreatsTwoInvalidOriginsAsEqual() {
        for (bad in listOf("", "not a server", "http://example.test", "https://example.test/path", "https://example.test#")) {
            assertFalse(sameTvLoginServer(bad, bad))
            assertFalse(sameTvLoginServer("https://example.test", bad))
        }
    }
}
