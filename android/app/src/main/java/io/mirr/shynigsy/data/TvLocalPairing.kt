package io.mirr.shynigsy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** LAN bootstrap only: no server password, token, or pairing claim secret is transmitted. */
internal data class TvLocalLink(val address: String, val port: Int, val secret: ByteArray)

private val localRandom = SecureRandom()
private val urlBase64 = Base64.getUrlEncoder().withoutPadding()
private val urlDecoder = Base64.getUrlDecoder()
private const val MAX_FRAME = 4096
private const val LOCAL_LIFETIME_MS = 120_000L

internal fun privateTvAddress(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                (it.length > 1 && it[0] == '0') }) return false
    val bytes = parts.map { it.toIntOrNull() ?: return false }
    if (bytes.any { it !in 0..255 }) return false
    return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
        (bytes[0] == 192 && bytes[1] == 168)
}

internal fun localTvQrUrl(address: String, port: Int, secret: ByteArray): String {
    require(privateTvAddress(address) && port in 1..65535 && secret.size == 32)
    return "shyniflex://tv-local?v=1&host=$address&port=$port&key=${urlBase64.encodeToString(secret)}"
}

/** Reject arbitrary destinations, duplicate parameters, alternate schemes, and malformed keys. */
internal fun parseLocalTvQr(value: String): TvLocalLink? = runCatching {
    require(value.length in 1..512 && value == value.trim() && value.none { it.isWhitespace() || it.isISOControl() })
    val uri = URI(value)
    require(uri.scheme == "shyniflex" && uri.rawAuthority == "tv-local" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
    val values = linkedMapOf<String, String>()
    for (part in requireNotNull(uri.rawQuery).split('&')) {
        val fields = part.split('=')
        require(fields.size == 2 && fields[0] in setOf("v", "host", "port", "key") && fields[0] !in values)
        values[fields[0]] = fields[1]
    }
    require(values.keys == setOf("v", "host", "port", "key") && values["v"] == "1")
    val address = requireNotNull(values["host"])
    val portText = requireNotNull(values["port"])
    require(portText.all { it in '0'..'9' } && portText.length in 1..5 && (portText.length == 1 || portText[0] != '0'))
    val port = portText.toInt()
    val keyText = requireNotNull(values["key"])
    require(Regex("[A-Za-z0-9_-]{43}").matches(keyText))
    val secret = urlDecoder.decode(keyText)
    require(urlBase64.encodeToString(secret) == keyText && privateTvAddress(address) && port in 1..65535 && secret.size == 32)
    TvLocalLink(address, port, secret)
}.getOrNull()

internal fun localPairingCrypt(secret: ByteArray, direction: String, bytes: ByteArray, encrypt: Boolean): ByteArray {
    require(secret.size == 32 && direction in setOf("phone-to-tv", "tv-to-phone"))
    require(encrypt || bytes.size >= 28)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("shyniflex-tv-local-v1:$direction:".toByteArray(Charsets.UTF_8))
    val key = SecretKeySpec(digest.digest(secret), "AES")
    val iv = if (encrypt) ByteArray(12).also(localRandom::nextBytes) else bytes.copyOfRange(0, 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    cipher.updateAAD("shyniflex-tv-local-v1:$direction".toByteArray(Charsets.UTF_8))
    return if (encrypt) iv + cipher.doFinal(bytes) else cipher.doFinal(bytes, 12, bytes.size - 12)
}

private fun writeEncrypted(socket: Socket, secret: ByteArray, direction: String, value: String) {
    val data = value.toByteArray(Charsets.UTF_8)
    require(data.size in 1..(MAX_FRAME - 28))
    val encrypted = localPairingCrypt(secret, direction, data, true)
    DataOutputStream(socket.getOutputStream()).apply { writeInt(encrypted.size); write(encrypted); flush() }
}

private fun readEncrypted(socket: Socket, secret: ByteArray, direction: String, timeoutMs: Long): String {
    val deadline = System.nanoTime() / 1_000_000L + timeoutMs
    val input = socket.getInputStream()
    fun readExact(bytes: ByteArray) {
        var done = 0
        while (done < bytes.size) {
            val remaining = deadline - System.nanoTime() / 1_000_000L
            if (remaining <= 0) throw SocketTimeoutException()
            socket.soTimeout = remaining.coerceAtMost(1_000).toInt().coerceAtLeast(1)
            val count = try { input.read(bytes, done, bytes.size - done) } catch (_: SocketTimeoutException) { continue }
            if (count < 0) throw java.io.EOFException()
            done += count
        }
    }
    val length = ByteArray(4)
    readExact(length)
    val size = ByteBuffer.wrap(length).int
    require(size in 29..MAX_FRAME)
    val bytes = ByteArray(size)
    readExact(bytes)
    return localPairingCrypt(secret, direction, bytes, false).toString(Charsets.UTF_8)
}

/** Listening only begins after the user opens TV QR login. A session accepts exactly one valid scan. */
internal class LocalPairingExpired : IllegalStateException("QR 코드가 만료되었습니다.")

internal class LocalTvPairing internal constructor(private val listener: ServerSocket, private val secret: ByteArray,
    val qrUrl: String, private val expiresAt: Long) : AutoCloseable {
    companion object {
        fun create(context: Context): LocalTvPairing {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val candidates = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks.toList()
            val address = candidates.distinct().asSequence().filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }.flatMap { network -> connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence() }
                .map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                .firstOrNull(::privateTvAddress) ?: throw IllegalStateException("같은 네트워크에 연결해 주세요.")
            val socket = ServerSocket().apply { reuseAddress = false; bind(InetSocketAddress(address, 0)); soTimeout = 1_000 }
            val secret = ByteArray(32).also(localRandom::nextBytes)
            return LocalTvPairing(socket, secret, localTvQrUrl(address, socket.localPort, secret),
                System.nanoTime() / 1_000_000L + LOCAL_LIFETIME_MS)
        }
    }

    suspend fun <T> accept(block: suspend (server: String, sendLink: (String) -> Unit) -> T): T = withContext(Dispatchers.IO) {
        while (System.nanoTime() / 1_000_000L < expiresAt) {
            currentCoroutineContext().ensureActive()
            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
            try {
                val remaining = (expiresAt - System.nanoTime() / 1_000_000L).coerceAtMost(3_000L)
                if (remaining <= 0) break
                val server = try { readEncrypted(socket, secret, "phone-to-tv", remaining) } catch (_: Exception) { continue }
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() / 1_000_000L >= expiresAt) break
                // Enforce the same canonical HTTPS validation used by the existing server relay.
                val canonical = parseTvLoginLink(runCatching { tvLoginQrUrl(server, "A".repeat(32)) }.getOrDefault(""))?.server
                    ?: continue
                listener.close() // consume the one-time QR after the first authenticated request
                return@withContext block(canonical) { link -> writeEncrypted(socket, secret, "tv-to-phone", link) }
            } finally { socket.close() }
        }
        throw LocalPairingExpired()
    }

    override fun close() { runCatching { listener.close() }; secret.fill(0) }
}

/** The phone only sends its trusted current server; approval remains explicit in the existing UI. */
internal suspend fun connectLocalTv(link: TvLocalLink, server: String): TvLoginLink {
    require(parseLocalTvQr(localTvQrUrl(link.address, link.port, link.secret)) != null)
    val canonical = requireNotNull(parseTvLoginLink(tvLoginQrUrl(server, "A".repeat(32)))).server
    return connectLocalTvSocket(InetSocketAddress(link.address, link.port), link.secret, canonical)
}

internal suspend fun connectLocalTvSocket(address: InetSocketAddress, secret: ByteArray, server: String): TvLoginLink = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        val result = try {
            socket.connect(address, 5_000)
            Result.success(exchangeLocalTv(socket, secret, server))
        } catch (error: Exception) {
            Result.failure(error)
        } finally { runCatching { socket.close() } }
        continuation.resumeWith(result)
    }
}

/** Socket must already be connected; kept separate so the encrypted protocol can be tested on loopback. */
internal fun exchangeLocalTv(socket: Socket, secret: ByteArray, server: String): TvLoginLink {
    val canonical = requireNotNull(parseTvLoginLink(tvLoginQrUrl(server, "A".repeat(32)))).server
    writeEncrypted(socket, secret, "phone-to-tv", canonical)
    val result = requireNotNull(parseTvLoginLink(readEncrypted(socket, secret, "tv-to-phone", 35_000)))
    require(sameTvLoginServer(result.server, canonical))
    return result
}
