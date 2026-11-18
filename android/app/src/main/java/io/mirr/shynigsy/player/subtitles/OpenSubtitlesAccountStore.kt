package io.mirr.shynigsy.player.subtitles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.mirr.shynigsy.BuildConfig
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A separate encrypted provider account. Neither Shyni sessions nor passwords are persisted here. */
internal class OpenSubtitlesAccountStore(context: Context, private val appApiKey: String = BuildConfig.OPENSUBTITLES_API_KEY) {
    private val preferences = context.getSharedPreferences("opensubtitles_account_v1", Context.MODE_PRIVATE)
    private val cipher = OpenSubtitlesAccountCipher(::key) { appApiKey }

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun load(): OpenSubtitlesAccount? {
        val record = preferences.getString(RECORD, null) ?: return null
        return cipher.decrypt(record)
    }

    @Synchronized fun save(account: OpenSubtitlesAccount) {
        val record = try { cipher.encrypt(account) } catch (_: Exception) {
            throw OpenSubtitlesException("OpenSubtitles 연결 정보를 안전하게 저장하지 못했습니다.")
        }
        if (!preferences.edit().putString(RECORD, record).commit())
            throw OpenSubtitlesException("OpenSubtitles 연결 정보를 안전하게 저장하지 못했습니다.")
    }

    @Synchronized fun clear() {
        if (!preferences.edit().remove(RECORD).commit())
            throw OpenSubtitlesException("OpenSubtitles 연결 정보를 삭제하지 못했습니다. 다시 시도해 주세요.")
    }

    private companion object {
        const val KEY_ALIAS = "shyniflex_opensubtitles_v1"
        const val RECORD = "account"
    }
}

/** The injectable key source permits offline AES-GCM tamper/serialization tests without Android. */
internal class OpenSubtitlesAccountCipher(private val key: () -> SecretKey, private val appApiKey: () -> String) {
    fun encrypt(account: OpenSubtitlesAccount): String {
        validate(account)
        val payload = JSONObject().put("version", 1)
            .put("token", account.token ?: JSONObject.NULL).put("userName", account.userName)
            .put("baseHost", account.baseHost).put("allowedDownloads", account.allowedDownloads ?: JSONObject.NULL)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad())
            }
            return "1:" + Base64.getEncoder().encodeToString(cipher.iv) + ":" +
                Base64.getEncoder().encodeToString(cipher.doFinal(bytes))
        } finally { bytes.fill(0) }
    }

    fun decrypt(record: String): OpenSubtitlesAccount? = try {
        if (record.length > 24_000) null else {
            val pieces = record.split(':')
            if (pieces.size != 3 || pieces[0] != "1") null else {
                val iv = Base64.getDecoder().decode(pieces[1])
                if (iv.size != 12) null else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(aad())
                    }
                    val bytes = cipher.doFinal(Base64.getDecoder().decode(pieces[2]))
                    try {
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        if (json.optInt("version") != 1) null else {
                            val account = OpenSubtitlesAccount(appApiKey(), json.opt("token") as? String,
                                json.getString("userName"), json.getString("baseHost"),
                                if (json.isNull("allowedDownloads")) null else json.getInt("allowedDownloads"))
                            validate(account)
                            account
                        }
                    } finally { bytes.fill(0) }
                }
            }
        }
    } catch (_: Exception) { null }

    private fun validate(account: OpenSubtitlesAccount) {
        require(OpenSubtitlesClient.validatedKey(account.apiKey) == account.apiKey)
        require(account.apiKey == appApiKey())
        require(account.token == null || OpenSubtitlesClient.validHeaderValue(account.token, 8192))
        require(account.userName.length <= 200 && account.userName.none { it.isISOControl() })
        require(account.baseHost in OpenSubtitlesClient.API_HOSTS)
        require(account.baseHost != "vip-api.opensubtitles.com" || account.token != null)
        require(account.allowedDownloads == null || account.allowedDownloads >= 0)
    }

    private fun aad(): ByteArray = "shyniflex:opensubtitles-account:v1".toByteArray(Charsets.UTF_8) +
        MessageDigest.getInstance("SHA-256").digest(appApiKey().toByteArray(Charsets.UTF_8))
}
