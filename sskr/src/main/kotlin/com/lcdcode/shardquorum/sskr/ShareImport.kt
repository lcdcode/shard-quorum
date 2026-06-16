package com.lcdcode.shardquorum.sskr

/**
 * One recognized input on the recovery side: either an SSKR share or a KEK
 * recovery envelope. Not data classes - the byte arrays would break structural
 * equality (same pitfall avoided in [KekEnvelope.Protected]).
 */
sealed interface ShareImport {
    /** A serialized SSKR share, with its parsed metadata header. */
    class Share(val bytes: ByteArray, val metadata: SskrShare) : ShareImport

    /** A KEK recovery envelope (SQKE ciphertext blob). */
    class Envelope(val bytes: ByteArray) : ShareImport
}

/**
 * Parses the three recovery input forms into a [ShareImport]:
 *  - "ur:sskr/..."   (scanned/uploaded share QR)        -> [ShareImport.Share]
 *  - "ur:sq-env/..." (scanned/uploaded envelope QR)     -> [ShareImport.Envelope]
 *  - standard Bytewords (hand-typed share transcription) -> [ShareImport.Share]
 *
 * Throws [IllegalArgumentException] if the text matches none of these or fails
 * its checksum/structure validation underneath.
 */
object ShareReader {
    private val SHARE_UR_PREFIX = "ur:${Ur.UR_TYPE}/"
    private val ENVELOPE_UR_PREFIX = "ur:${Ur.ENVELOPE_UR_TYPE}/"

    fun parse(text: String): ShareImport {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty input" }
        return when {
            trimmed.startsWith(SHARE_UR_PREFIX, ignoreCase = true) ->
                shareOf(Ur.fromUr(trimmed))
            trimmed.startsWith(ENVELOPE_UR_PREFIX, ignoreCase = true) ->
                ShareImport.Envelope(Ur.fromEnvelopeUr(trimmed))
            else ->
                shareOf(Ur.fromStandardBytewords(trimmed))
        }
    }

    private fun shareOf(bytes: ByteArray): ShareImport.Share =
        ShareImport.Share(bytes, SskrShare.deserialize(bytes))
}
