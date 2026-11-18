package io.mirr.shynigsy.player.subtitles

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class OpenSubtitlesAccountStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = OpenSubtitlesAccountCipher({ key }) { "synthetic-key" }
    private val account = OpenSubtitlesAccount("synthetic-key", "synthetic-jwt", "synthetic-user", "vip-api.opensubtitles.com", 100)

    @Test fun accountRoundTripsWithoutPlaintextCredentials() {
        val encrypted = cipher.encrypt(account)
        assertFalse(encrypted.contains(account.apiKey)); assertFalse(encrypted.contains(account.token!!))
        assertFalse(encrypted.contains(account.userName)); assertFalse(encrypted.contains("password"))
        assertEquals(account, cipher.decrypt(encrypted))
        assertNotEquals(encrypted, cipher.encrypt(account))
        assertFalse(account.toString().contains("synthetic"))
        val parts = encrypted.split(':')
        val raw = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])))
            updateAAD("shyniflex:opensubtitles-account:v1".toByteArray(Charsets.UTF_8) +
                MessageDigest.getInstance("SHA-256").digest(account.apiKey.toByteArray(Charsets.UTF_8)))
        }.doFinal(Base64.getDecoder().decode(parts[2]))
        val payload = JSONObject(String(raw, Charsets.UTF_8))
        assertFalse(payload.has("apiKey")); assertFalse(payload.has("password"))
    }

    @Test fun keyOnlyAccountRoundTripsWithoutInventingAToken() {
        val anonymous = OpenSubtitlesAccount("synthetic-key")
        assertEquals(anonymous, cipher.decrypt(cipher.encrypt(anonymous)))
    }

    @Test fun tamperingOrAnotherKeyCannotRestoreTheAccount() {
        val encrypted = cipher.encrypt(account)
        val parts = encrypted.split(':')
        val bytes = Base64.getDecoder().decode(parts[2])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        assertNull(cipher.decrypt("${parts[0]}:${parts[1]}:${Base64.getEncoder().encodeToString(bytes)}"))
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(OpenSubtitlesAccountCipher({ wrongKey }) { "synthetic-key" }.decrypt(encrypted))
        assertNull(OpenSubtitlesAccountCipher({ key }) { "different-app-key" }.decrypt(encrypted))
    }

    @Test fun invalidRecordsAndProviderHostsAreRejected() {
        assertNull(cipher.decrypt("not encrypted")); assertNull(cipher.decrypt("x".repeat(24_001)))
        for (value in listOf(account.copy(baseHost = "evil.test"), account.copy(token = "bad\nheader"),
            account.copy(apiKey = ""), account.copy(token = null))) {
            try { cipher.encrypt(value); fail("Invalid account must not be persisted") } catch (_: IllegalArgumentException) {
            } catch (_: OpenSubtitlesException) { }
        }
    }
}
