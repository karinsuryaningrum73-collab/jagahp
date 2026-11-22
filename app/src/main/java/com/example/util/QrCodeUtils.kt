package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeUtils {

    private const val QR_PREFIX = "jagacam://pair/"

    fun buildPairPayload(roomCode: String): String = "$QR_PREFIX${roomCode.trim().uppercase()}"

    fun parseRoomCodeFromQr(raw: String): String? {
        val trimmed = raw.trim()
        val code = if (trimmed.startsWith(QR_PREFIX, ignoreCase = true)) {
            trimmed.substring(QR_PREFIX.length).trim()
        } else {
            trimmed
        }
        val normalized = code.uppercase()
        return if (Regex("^[A-Z0-9_\\-]{6,32}$").matches(normalized)) normalized else null
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }

    fun decodeQrFromImageProxy(image: ImageProxy): String? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = QRCodeReader().decode(binaryBitmap)
            parseRoomCodeFromQr(result.text)
        } catch (_: Exception) {
            null
        }
    }
}
