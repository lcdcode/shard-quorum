package com.lcdcode.shardquorum.sskr

/**
 * UR and CBOR framing for SSKR shares (BCR-2020-011), the outermost transport
 * layer that turns a serialized [SskrShare] into the two human/QR forms the
 * spec defines:
 *
 *  - [toStandardBytewords]: the share wrapped in CBOR tag #6.40309 and rendered
 *    as standard (space-separated) ByteWords. Self-identifying, interoperable
 *    with generic SSKR tools, used for printed/transcribed output.
 *  - [toUr]: the *untagged* CBOR rendered as minimal ByteWords behind a
 *    "ur:sskr/" prefix - the compact Uniform Resource form (BCR-2020-005) that
 *    goes into a QR code.
 *
 * The CBOR involved is deliberately minimal: a single byte string holding the
 * 5-byte metadata header plus the share value, optionally wrapped in the SSKR
 * tag. We therefore hand-roll just the two CBOR shapes we need rather than pull
 * in a CBOR dependency. The byte-string length header is encoded canonically,
 * so it is NOT a fixed prefix: a 16-byte secret yields a 21-byte share (header
 * 0x55) while a 32-byte secret yields a 37-byte share (header 0x58 0x25).
 */
object Ur {
    const val UR_TYPE = "sskr"
    private const val UR_PREFIX = "ur:$UR_TYPE/"

    /**
     * ShardQuorum-proprietary UR type for the KEK envelope ciphertext (the
     * SQKE blob from [KekEnvelope]). Not an IANA-registered type; the prefix
     * is deliberately distinct from "ur:sskr/" so a generic SSKR scanner never
     * misparses an envelope QR as a share. Format: untagged CBOR byte string
     * rendered as minimal bytewords, same framing as [toUr].
     */
    const val ENVELOPE_UR_TYPE = "sq-env"
    private const val ENVELOPE_UR_PREFIX = "ur:$ENVELOPE_UR_TYPE/"

    /** SSKR CBOR tag, IANA-registered as #6.40309. */
    private const val SSKR_CBOR_TAG = 40309

    // CBOR major type 2 (byte string) and 6 (tag), shifted into the high 3 bits.
    private const val MAJOR_BYTE_STRING = 2 shl 5
    private const val MAJOR_TAG = 6 shl 5

    // Additional-info values selecting how the argument (length/tag) is carried.
    private const val AI_DIRECT_MAX = 23      // 0..23 packed into the initial byte
    private const val AI_ONE_BYTE = 24        // argument in the next 1 byte
    private const val AI_TWO_BYTES = 25       // argument in the next 2 bytes
    private const val MAX_TWO_BYTE_VALUE = 0xffff

    /** Tagged share as standard ByteWords (for printing / hand transcription). */
    fun toStandardBytewords(share: ByteArray): String =
        Bytewords.encode(taggedCbor(share), Bytewords.Style.STANDARD)

    /** Untagged share as a "ur:sskr/..." string (for QR codes). */
    fun toUr(share: ByteArray): String =
        UR_PREFIX + Bytewords.encode(untaggedCbor(share), Bytewords.Style.MINIMAL)

    /** Recovers the raw serialized share from tagged standard ByteWords. */
    fun fromStandardBytewords(text: String): ByteArray =
        decodeTaggedCbor(Bytewords.decode(text, Bytewords.Style.STANDARD))

    /** Recovers the raw serialized share from a "ur:sskr/..." string. */
    fun fromUr(text: String): ByteArray = fromTypedUr(text, UR_PREFIX)

    /** KEK envelope bytes as a "ur:sq-env/..." string (for QR codes). */
    fun toEnvelopeUr(envelope: ByteArray): String =
        ENVELOPE_UR_PREFIX + Bytewords.encode(untaggedCbor(envelope), Bytewords.Style.MINIMAL)

    /** Recovers the KEK envelope bytes from a "ur:sq-env/..." string. */
    fun fromEnvelopeUr(text: String): ByteArray = fromTypedUr(text, ENVELOPE_UR_PREFIX)

    private fun fromTypedUr(text: String, prefix: String): ByteArray {
        val trimmed = text.trim()
        require(trimmed.startsWith(prefix, ignoreCase = true)) {
            "not a \"$prefix\" UR"
        }
        val body = trimmed.substring(prefix.length)
        return decodeUntaggedCbor(Bytewords.decode(body, Bytewords.Style.MINIMAL))
    }

    private fun untaggedCbor(share: ByteArray): ByteArray =
        byteStringHeader(share.size) + share

    private fun taggedCbor(share: ByteArray): ByteArray =
        tagHeader(SSKR_CBOR_TAG) + untaggedCbor(share)

    /** Canonical CBOR header for a byte string of [length] bytes. */
    private fun byteStringHeader(length: Int): ByteArray =
        encodeArgument(MAJOR_BYTE_STRING, length)

    /** Canonical CBOR header for a tag with the given value. */
    private fun tagHeader(tag: Int): ByteArray =
        encodeArgument(MAJOR_TAG, tag)

    private fun encodeArgument(major: Int, value: Int): ByteArray {
        require(value in 0..MAX_TWO_BYTE_VALUE) { "value $value outside supported CBOR range" }
        return when {
            value <= AI_DIRECT_MAX -> byteArrayOf((major or value).toByte())
            value <= 0xff -> byteArrayOf((major or AI_ONE_BYTE).toByte(), value.toByte())
            else -> byteArrayOf(
                (major or AI_TWO_BYTES).toByte(),
                (value ushr 8).toByte(),
                (value and 0xff).toByte(),
            )
        }
    }

    private fun decodeTaggedCbor(bytes: ByteArray): ByteArray {
        val afterTag = readTag(bytes, SSKR_CBOR_TAG)
        return decodeUntaggedCbor(bytes.copyOfRange(afterTag, bytes.size))
    }

    private fun decodeUntaggedCbor(bytes: ByteArray): ByteArray {
        val (length, headerSize) = readByteStringHeader(bytes)
        val end = headerSize + length
        require(end == bytes.size) {
            "trailing or missing data: header declares $length bytes, found ${bytes.size - headerSize}"
        }
        return bytes.copyOfRange(headerSize, end)
    }

    /** Validates the leading CBOR tag equals [expected]; returns the next offset. */
    private fun readTag(bytes: ByteArray, expected: Int): Int {
        require(bytes.isNotEmpty()) { "empty CBOR input" }
        val (major, value, size) = readArgument(bytes)
        require(major == MAJOR_TAG) { "expected a CBOR tag" }
        require(value == expected) { "expected SSKR tag $expected, got $value" }
        return size
    }

    /** Returns (byteStringLength, headerSize) for a CBOR byte string at offset 0. */
    private fun readByteStringHeader(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.isNotEmpty()) { "empty CBOR input" }
        val (major, value, size) = readArgument(bytes)
        require(major == MAJOR_BYTE_STRING) { "expected a CBOR byte string" }
        return value to size
    }

    /** Decodes a CBOR initial byte plus argument; returns (major, value, bytesRead). */
    private fun readArgument(bytes: ByteArray): Triple<Int, Int, Int> {
        val initial = bytes[0].toInt() and 0xff
        val major = initial and 0xe0
        return when (val ai = initial and 0x1f) {
            in 0..AI_DIRECT_MAX -> Triple(major, ai, 1)
            AI_ONE_BYTE -> {
                require(bytes.size >= 2) { "truncated CBOR argument" }
                Triple(major, bytes[1].toInt() and 0xff, 2)
            }
            AI_TWO_BYTES -> {
                require(bytes.size >= 3) { "truncated CBOR argument" }
                val value = ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
                Triple(major, value, 3)
            }
            else -> throw IllegalArgumentException("unsupported CBOR additional info $ai")
        }
    }
}
