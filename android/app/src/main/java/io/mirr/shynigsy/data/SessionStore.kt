package io.mirr.shynigsy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedSession(val server: String, val token: String, val userName: String)

/** Only the session is saved; passwords and one-time codes are never persisted. */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
    private val alias = "shyniflex_session_v1"

    @Synchronized private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(server: String, token: String, userName: String) {
        val payload = JSONObject().put("server", server).put("token", token).put("userName", userName)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(server, record).putString("last_server", server).commit()) {
            "로그인 정보를 안전하게 저장하지 못했습니다."
        }
    }

    fun loadLast(): SavedSession? = preferences.getString("last_server", null)?.let(::load)

    fun load(server: String): SavedSession? = runCatching {
        val record = preferences.getString(server, null) ?: return null
        val parts = record.split(':', limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        val decrypted = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        val data = JSONObject(String(decrypted, Charsets.UTF_8))
        if (data.getString("server") != server) return null
        SavedSession(server, data.getString("token"), data.optString("userName"))
    }.getOrNull()

    fun clear(server: String) {
        val edit = preferences.edit().remove(server)
        if (preferences.getString("last_server", null) == server) edit.remove("last_server")
        edit.commit()
    }
}
