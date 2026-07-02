package com.lcdcode.shardquorum.qr

import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * Decodes a QR code from an encoded image file (PNG/JPEG/etc.) using the
 * pure-JVM zxing core reader. Memory-safe: malformed or hostile input yields a
 * [QrDecodeException], never memory corruption - which is why a JVM decoder was
 * chosen over a native one for parsing custodian-supplied images.
 *
 * Image dimensions are read without allocation first, then the bitmap is
 * downsampled so its pixel buffer stays under [MAX_PIXELS]. This bounds the
 * IntArray a maliciously huge image could force us to allocate, while leaving
 * normal high-resolution photos of a printed shard decodable.
 *
 * Decoding runs synchronously on the caller's thread and is expensive: with
 * TRY_HARDER, a photo near the pixel cap can take seconds. Callers MUST invoke
 * it off the main thread; the ViewModels dispatch it to a background dispatcher.
 */
class ZxingQrDecoder : QrDecoder {

    override fun decode(imageBytes: ByteArray): List<String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw QrDecodeException("could not read image data")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            ?: throw QrDecodeException("could not read image data")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            return try {
                // decodeMultiple finds every QR in the image (e.g. a bundled
                // shard + envelope sheet), throwing when none are present.
                QRCodeMultiReader().decodeMultiple(binary, HINTS).map { it.text }
            } catch (e: ReaderException) {
                throw QrDecodeException("no QR code found in image", e)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Smallest power-of-two subsample that brings the pixel count under [MAX_PIXELS]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > MAX_PIXELS) sample *= 2
        return sample
    }

    private companion object {
        // ~8M pixels caps the decoded IntArray near 32 MB. Above this the image
        // is downsampled; a QR sheet stays well within QR-detectable resolution.
        const val MAX_PIXELS = 8_000_000L

        val HINTS = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
    }
}
