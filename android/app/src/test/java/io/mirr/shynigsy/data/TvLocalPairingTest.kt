package io.mirr.shynigsy.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.io.DataInputStream

class TvLocalPairingTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test fun qrRoundTripsOnlyPrivateAddressesAndRandomKey() {
        val url = localTvQrUrl("192.168.4.20", 20400, key)
        val parsed = requireNotNull(parseLocalTvQr(url))
        assertEquals("192.168.4.20", parsed.address)
        assertEquals(20400, parsed.port)
        assertArrayEquals(key, parsed.secret)
        assertNull(parseLocalTvQr(url + "&key=duplicate"))
        assertNull(parseLocalTvQr(url.replace("192.168.4.20", "127.0.0.1")))
        assertNull(parseLocalTvQr(url.replace("192.168.4.20", "8.8.8.8")))
        assertNull(parseLocalTvQr(url.replace("192.168.4.20", "192.168.04.20")))
        assertNull(parseLocalTvQr(url.replace("tv-local", "user@tv-local")))
        assertNull(parseLocalTvQr(url + "#fragment"))
        assertNull(parseLocalTvQr(url.replace("port=20400", "port=0")))
    }

    @Test fun encryptionAuthenticatesCiphertextAndDirectionWithoutSendingPlaintext() {
        val plaintext = "https://server.example.test:8443".toByteArray()
        val encrypted = localPairingCrypt(key, "phone-to-tv", plaintext, true)
        val encryptedAgain = localPairingCrypt(key, "phone-to-tv", plaintext, true)
        assertFalse(encrypted.contentEquals(encryptedAgain))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("server.example.test"))
        assertArrayEquals(plaintext, localPairingCrypt(key, "phone-to-tv", encrypted, false))
        assertTrue(runCatching { localPairingCrypt(key, "tv-to-phone", encrypted, false) }.isFailure)
        val altered = encrypted.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertTrue(runCatching { localPairingCrypt(key, "phone-to-tv", altered, false) }.isFailure)
        assertTrue(runCatching { localPairingCrypt(ByteArray(32), "phone-to-tv", encrypted, false) }.isFailure)
    }

    @Test fun localQrDoesNotExposeAccountOrServerCredentials() {
        val url = localTvQrUrl("10.1.2.3", 12345, key)
        assertFalse(url.contains("server="))
        assertFalse(url.contains("token="))
        assertFalse(url.contains("password="))
        assertFalse(url.contains("claim_secret="))
        assertNull(parseLocalTvQr(url + "&server=https%3A%2F%2Fexample.test"))
    }

    @Test fun encryptedLocalExchangeIsOneUseAndPreservesServerOrigin() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val serverSocket = ServerSocket(0, 5, loopback).apply { soTimeout = 100 }
        val port = serverSocket.localPort
        val local = LocalTvPairing(serverSocket, key.clone(), "test", System.nanoTime() / 1_000_000L + 10_000)
        try {
            val tv = async(Dispatchers.IO) {
                local.accept { server, sendLink ->
                    assertEquals("https://server.example.test:8443", server)
                    sendLink(tvLoginQrUrl(server, "n".repeat(32)))
                    "received"
                }
            }
            val phone = async(Dispatchers.IO) {
                Socket(loopback, port).use { exchangeLocalTv(it, key, "https://server.example.test:8443") }
            }
            val link = withTimeout(5_000) { phone.await() }
            assertEquals("n".repeat(32), link.nonce)
            assertEquals("received", withTimeout(5_000) { tv.await() })
            assertTrue("A consumed code cannot establish another connection", runCatching { Socket(loopback, port).close() }.isFailure)
        } finally { local.close() }
    }

    @Test fun expiredSessionDoesNotAcceptConnections() = runBlocking {
        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).apply { soTimeout = 100 }
        val local = LocalTvPairing(serverSocket, key.clone(), "test", System.nanoTime() / 1_000_000L - 1)
        try {
            val failure = runCatching { local.accept { _, _ -> fail("Expired code was accepted") } }.exceptionOrNull()
            assertTrue(failure is LocalPairingExpired)
        } finally { local.close() }
    }

    @Test fun slowPartialFrameCannotExtendCodeLifetime() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val serverSocket = ServerSocket(0, 5, loopback).apply { soTimeout = 100 }
        val port = serverSocket.localPort
        val local = LocalTvPairing(serverSocket, key.clone(), "test", System.nanoTime() / 1_000_000L + 1_200)
        try {
            val waiting = async(Dispatchers.IO) {
                runCatching { local.accept { _, _ -> fail("Incomplete packet was accepted") } }.exceptionOrNull()
            }
            Socket(loopback, port).use { socket ->
                socket.getOutputStream().write(0)
                socket.getOutputStream().flush()
                assertTrue(withTimeout(3_500) { waiting.await() } is LocalPairingExpired)
            }
        } finally { local.close() }
    }

    @Test fun cancellingPhoneExchangeClosesItsSocket() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val listener = ServerSocket(0, 5, loopback)
        val requestRead = CompletableDeferred<Unit>()
        try {
            val receiver = async(Dispatchers.IO) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = DataInputStream(socket.getInputStream())
                    val encrypted = ByteArray(input.readInt())
                    input.readFully(encrypted)
                    requestRead.complete(Unit)
                    input.read()
                }
            }
            val phone = launch {
                connectLocalTvSocket(InetSocketAddress(loopback, listener.localPort), key, "https://server.example.test")
            }
            withTimeout(3_000) { requestRead.await() }
            withTimeout(3_000) { phone.cancelAndJoin() }
            assertEquals(-1, withTimeout(3_000) { receiver.await() })
        } finally { listener.close() }
    }
}
