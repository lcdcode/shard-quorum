package com.lcdcode.shardquorum.qr

/**
 * Decodes a QR code into its text payload. The concrete implementation (camera
 * frame and image-file decoding) is deferred until the scanner library is
 * chosen - see the parked zxing-cpp vs. memory-safe-Java decision. Abstracting
 * it here keeps the recovery flow buildable and testable in the meantime.
 */
fun interface QrDecoder {
    /**
     * Returns the decoded text of the single QR code in [imageBytes].
     * @throws QrDecodeException if no QR is found or decoding is unavailable.
     */
    fun decode(imageBytes: ByteArray): String
}

class QrDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Placeholder decoder until a scanner library is wired in; always fails. */
class UnavailableQrDecoder : QrDecoder {
    override fun decode(imageBytes: ByteArray): String =
        throw QrDecodeException("QR decoding is not implemented yet")
}
