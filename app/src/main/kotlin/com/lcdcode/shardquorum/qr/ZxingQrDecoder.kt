package com.lcdcode.shardquorum.qr

import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a QR code from an encoded image file (PNG/JPEG/etc.) using the
 * pure-JVM zxing core reader. Memory-safe: malformed or hostile input yields a
 * [QrDecodeException], never memory corruption - which is why a JVM decoder was
 * chosen over a native one for parsing custodian-supplied images.
 *
 * Decoding runs synchronously on the caller's thread. Callers should invoke it
 * off the main thread for large images; current call sites decode a single
 * picked image, which is acceptable inline.
 */
class ZxingQrDecoder : QrDecoder {

    override fun decode(imageBytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw QrDecodeException("could not read image data")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            return try {
                QRCodeReader().decode(binary, HINTS).text
            } catch (e: ReaderException) {
                throw QrDecodeException("no QR code found in image", e)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val HINTS = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
    }
}
