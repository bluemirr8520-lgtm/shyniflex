package io.mirr.shynigsy

import io.mirr.shynigsy.data.TvLoginLink

/** Phone confirmation data; a public relay nonce is never a session or claim secret. */
data class TvLoginApprovalState(
    val link: TvLoginLink,
    val expiresAtMs: Long,
    val busy: Boolean = false,
    val error: String? = null,
    val approved: Boolean = false,
)
