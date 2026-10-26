package io.mirr.shynigsy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.mirr.shynigsy.data.parseTvLoginLink
import io.mirr.shynigsy.data.tvLoginQrUrl

/** Route validated public QR data without changing ordinary launcher and player navigation. */
class TvLoginLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val link = if (intent?.action == Intent.ACTION_VIEW) {
                intent.dataString?.let(::parseTvLoginLink)
            } else null
            if (link != null) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(tvLoginQrUrl(link.server, link.nonce))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        } finally {
            finish()
        }
    }
}
