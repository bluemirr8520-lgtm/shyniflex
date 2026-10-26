package io.mirr.shynigsy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.mirr.shynigsy.data.parseTvLoginLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The validated approval URL contains a public nonce, never the TV-only claim secret. */
internal fun encodeTvLoginQr(url: String): BitMatrix {
    require(parseTvLoginLink(url) != null) { "Invalid TV approval URL" }
    return QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 1, 1, mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
    ))
}

internal data class TvQrPlacement(val modulePixels: Int, val left: Int, val top: Int, val sizePixels: Int)

/** Whole physical pixels keep quiet zones and finder patterns sharp on TV scaling densities. */
internal fun tvQrPlacement(width: Int, height: Int, modules: Int): TvQrPlacement? {
    if (modules <= 0 || width < modules || height < modules) return null
    val step = minOf(width, height) / modules
    val size = step * modules
    return TvQrPlacement(step, (width - size) / 2, (height - size) / 2, size)
}

private data class TvQrDrawing(val loading: Boolean = true, val modules: BitMatrix? = null)

/** Encoding runs once per URL away from the UI thread; drawing creates no Android bitmap. */
@Composable
internal fun TvLoginQrCode(url: String, modifier: Modifier = Modifier) {
    val drawing by produceState(TvQrDrawing(), url) {
        value = TvQrDrawing()
        val modules = withContext(Dispatchers.Default) { runCatching { encodeTvLoginQr(url) }.getOrNull() }
        value = TvQrDrawing(loading = false, modules = modules)
    }
    Box(modifier.size(216.dp).background(Color.White), contentAlignment = Alignment.Center) {
        val modules = drawing.modules
        when {
            drawing.loading -> CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp)
            modules == null -> Text("QR 코드를 표시할 수 없습니다.\n새로 만들어 주세요.",
                color = Color.Black, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.testTag("tv-login-qr-render-error"))
            else -> Canvas(Modifier.size(216.dp).testTag("tv-login-qr-code").semantics {
                contentDescription = "휴대폰으로 스캔할 TV 로그인 QR 코드"
            }) {
                val placement = tvQrPlacement(size.width.toInt(), size.height.toInt(), modules.width)
                    ?: return@Canvas
                for (y in 0 until modules.height) {
                    var x = 0
                    while (x < modules.width) {
                        if (!modules[x, y]) { x++; continue }
                        val start = x
                        while (x < modules.width && modules[x, y]) x++
                        drawRect(Color.Black,
                            topLeft = Offset((placement.left + start * placement.modulePixels).toFloat(),
                                (placement.top + y * placement.modulePixels).toFloat()),
                            size = Size(((x - start) * placement.modulePixels).toFloat(), placement.modulePixels.toFloat()))
                    }
                }
            }
        }
    }
}
