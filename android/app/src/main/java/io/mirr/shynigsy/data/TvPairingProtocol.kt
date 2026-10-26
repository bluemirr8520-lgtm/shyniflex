package io.mirr.shynigsy.data

import org.json.JSONObject
import java.math.BigDecimal

/** The relay belongs to the TV. Only its public nonce may be put in an approval QR. */
class TvPairingSession internal constructor(
    internal val nonce: String,
    private val claimSecret: String,
    val expiresInSeconds: Int,
) {
    internal fun claimBody(deviceName: String): JSONObject = JSONObject().apply {
        put("nonce", nonce)
        put("claim_secret", claimSecret)
        put("device_name", deviceName)
        put("app_name", "shyniflex")
    }

    // Do not make this a data class: its generated toString/copy would expose a claim credential.
    override fun toString(): String = "TvPairingSession(expiresInSeconds=$expiresInSeconds)"
}

sealed interface TvPairingClaim {
    data object Pending : TvPairingClaim
    class Approved(val login: LoginOutcome.Success) : TvPairingClaim {
        override fun toString(): String = "TvPairingClaim.Approved"
    }
}

interface TvPairingApi {
    suspend fun createTvPairingSession(): TvPairingSession
    suspend fun claimTvPairing(session: TvPairingSession, deviceName: String): TvPairingClaim
}

internal const val MAX_TV_PAIRING_SECONDS = 300
internal val tvPairingCredential = Regex("[A-Za-z0-9_-]{32,256}")

internal fun parseTvPairingSession(json: JSONObject): TvPairingSession {
    fun invalid(): Nothing = throw ApiException("서버의 QR 로그인 정보가 올바르지 않습니다.")
    val nonce = (json.opt("nonce") as? String)?.takeIf(tvPairingCredential::matches) ?: invalid()
    val secret = (json.opt("claim_secret") as? String)?.takeIf(tvPairingCredential::matches) ?: invalid()
    if (secret == nonce) invalid()
    val rawSeconds = json.opt("expires_in") as? Number ?: invalid()
    val seconds = runCatching { BigDecimal(rawSeconds.toString()).longValueExact() }.getOrNull()
        ?.takeIf { it in 1..MAX_TV_PAIRING_SECONDS.toLong() } ?: invalid()
    return TvPairingSession(nonce, secret, seconds.toInt())
}

/** Parsing an approval does not mutate the active client or store an account session. */
internal fun parseTvPairingClaim(json: JSONObject): TvPairingClaim {
    fun invalid(): Nothing = throw ApiException("서버의 QR 로그인 응답이 올바르지 않습니다.")
    if (json.optBoolean("require_totp")) invalid()
    return when (json.opt("status")) {
        "pending" -> {
            if (json.has("token") && !json.isNull("token")) invalid()
            TvPairingClaim.Pending
        }
        "approved" -> {
            val token = (json.opt("token") as? String)?.takeIf { value ->
                value.isNotBlank() && value.length <= 8192 && value.none { it.code < 32 || it.code == 127 } &&
                    (if (value.startsWith("cookie:")) value.removePrefix("cookie:").isNotBlank() else value.none(Char::isWhitespace))
            } ?: invalid()
            val username = (json.opt("username") as? String)?.takeIf { value ->
                value.isNotBlank() && value.length <= 200 && value.none { it.code < 32 || it.code == 127 }
            } ?: invalid()
            TvPairingClaim.Approved(LoginOutcome.Success(token, username))
        }
        else -> invalid()
    }
}
