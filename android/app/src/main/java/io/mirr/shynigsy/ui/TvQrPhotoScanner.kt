package io.mirr.shynigsy.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.mirr.shynigsy.data.parseLocalTvQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The camera app writes one private temporary photo; it is removed after decoding. */
@Composable
internal fun rememberTvQrPhotoScanner(onRead: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val read by rememberUpdatedState(onRead)
    val photo = File(File(context.cacheDir, "tvqr"), "scan.jpg")
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        if (!captured) { photo.delete(); return@rememberLauncherForActivityResult }
        scope.launch {
            val value = withContext(Dispatchers.Default) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(photo.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
                    val image = BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: return@withContext null
                    try {
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        val source = RGBLuminanceSource(image.width, image.height, pixels)
                        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
                        runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
                            .recoverCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
                            .getOrNull()
                    } finally { image.recycle() }
                } finally { photo.delete() }
            }
            if (value == null || parseLocalTvQr(value) == null)
                Toast.makeText(context, "TV QR을 인식하지 못했습니다. 화면을 가까이 찍어 다시 시도하세요.", Toast.LENGTH_LONG).show()
            else {
                Toast.makeText(context, "TV에 연결하고 있습니다.", Toast.LENGTH_SHORT).show()
                read(value)
            }
        }
    }
    return {
        try {
            photo.parentFile?.mkdirs()
            photo.delete()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.tvqr", photo)
            camera.launch(uri)
        } catch (_: Exception) {
            photo.delete()
            Toast.makeText(context, "카메라를 열 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }
}
