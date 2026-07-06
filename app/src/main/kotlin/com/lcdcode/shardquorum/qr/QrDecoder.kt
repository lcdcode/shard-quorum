package com.lcdcode.shardquorum.qr

/**
 * Decodes a QR code into its text payload. The production implementation is
 * [ZxingQrDecoder] (zxing core, chosen for memory safety over a native
 * decoder); this abstraction keeps the ViewModels decoupled from it and lets
 * unit tests substitute a stub.
 */
fun interface QrDecoder {
    /**
     * Returns the decoded text of every QR code found in [imageBytes] - more
     * than one when a saved sheet bundles, e.g., a shard QR and an envelope QR.
     * @throws QrDecodeException if no QR is found or decoding is unavailable.
     */
    fun decode(imageBytes: ByteArray): List<String>
}

class QrDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Always-failing decoder; used by unit tests to exercise the decode-failure path. */
class UnavailableQrDecoder : QrDecoder {
    override fun decode(imageBytes: ByteArray): List<String> =
        throw QrDecodeException("QR decoding is not implemented yet")
}
