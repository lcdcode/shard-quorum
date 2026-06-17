package com.lcdcode.shardquorum.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * CameraX analyzer that decodes a QR code from each camera frame and invokes
 * [onQrDecoded] with its text. Decoding reads the frame's luminance (Y) plane
 * directly, so no bitmap conversion is needed. Frames with no QR are silently
 * ignored. Runs on the single-threaded analysis executor it is attached to.
 */
class QrFrameAnalyzer(private val onQrDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    override fun analyze(image: ImageProxy) {
        try {
            decode(image)?.let(onQrDecoded)
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            image.height,
            0,
            0,
            minOf(image.width, plane.rowStride),
            image.height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (e: ReaderException) {
            null // no QR in this frame
        } finally {
            reader.reset()
        }
    }
}
