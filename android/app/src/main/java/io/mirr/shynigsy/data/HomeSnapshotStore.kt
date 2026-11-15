package io.mirr.shynigsy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional encrypted cache. Call on an IO dispatcher; failures remain a normal cache miss. */
internal class HomeSnapshotStore(context: Context) {
    private val appContext = context.applicationContext
    private val file by lazy { AtomicFile(File(appContext.noBackupFilesDir, "home_snapshot_v1.cache")) }

    fun read(scope: String, nowMs: Long = System.currentTimeMillis()): HomeSnapshot? = synchronized(lock) {
        runCatching {
            if (!validScope.matches(scope)) return@synchronized null
            val bytes = file.openRead().use { input ->
                if (input.channel.size() !in MIN_FILE_BYTES.toLong()..MAX_FILE_BYTES.toLong()) return@synchronized null
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_FILE_BYTES) return@synchronized null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size < MIN_FILE_BYTES || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) return@synchronized null
            val key = key(create = false) ?: return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(magic.size, magic.size + IV_BYTES)))
                updateAAD(scope.toByteArray(Charsets.UTF_8))
            }
            val plaintext = cipher.doFinal(bytes, magic.size + IV_BYTES, bytes.size - magic.size - IV_BYTES)
            decodeHomeSnapshot(scope, plaintext, nowMs)
        }.getOrNull()
    }

    fun write(scope: String, snapshot: HomeSnapshot) = synchronized(lock) {
        runCatching {
            val plaintext = encodeHomeSnapshot(scope, snapshot) ?: return@synchronized
            val secret = key(create = true) ?: return@synchronized
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secret)
                updateAAD(scope.toByteArray(Charsets.UTF_8))
            }
            check(cipher.iv.size == IV_BYTES)
            val ciphertext = cipher.doFinal(plaintext)
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(magic)
                stream.write(cipher.iv)
                stream.write(ciphertext)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        Unit
    }

    fun clear() = synchronized(lock) { runCatching { file.delete() }; Unit }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object {
        val lock = Any()
        val validScope = Regex("[a-f0-9]{64}")
        val magic = "SHYNIH1".toByteArray(Charsets.US_ASCII)
        const val KEY_ALIAS = "shyniflex_home_snapshot_v1"
        const val IV_BYTES = 12
        const val MIN_FILE_BYTES = 7 + IV_BYTES + 16
        const val MAX_FILE_BYTES = HOME_SNAPSHOT_MAX_BYTES + MIN_FILE_BYTES
    }
}
