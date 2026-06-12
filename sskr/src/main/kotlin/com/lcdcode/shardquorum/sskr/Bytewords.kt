package com.lcdcode.shardquorum.sskr

import java.util.zip.CRC32

/**
 * Bytewords encoding (BCR-2020-012): maps each byte to one of 256 four-letter
 * English words, appending a 4-byte CRC-32 checksum (big-endian) over the
 * payload. Used to render SSKR shares for hand transcription and QR text.
 *
 * Three styles share one codec:
 *  - [Style.STANDARD]: full words separated by spaces.
 *  - [Style.URI]: full words separated by hyphens (URI/QR-safe).
 *  - [Style.MINIMAL]: only each word's first and last letter, concatenated
 *    (2 chars per byte, as compact as hex). The first+last letter pair is unique
 *    per word by the wordlist's construction.
 *
 * The wordlist is transcribed verbatim from the spec; see docs/reference.
 */
object Bytewords {
    enum class Style { STANDARD, URI, MINIMAL }

    const val CHECKSUM_LENGTH = 4

    // 256 words, index == byte value. Sorted as in BCR-2020-012.
    private val WORDS: List<String> = (
        "able acid also apex aqua arch atom aunt away axis back bald barn belt beta bias " +
        "blue body brag brew bulb buzz calm cash cats chef city claw code cola cook cost " +
        "crux curl cusp cyan dark data days deli dice diet door down draw drop drum dull " +
        "duty each easy echo edge epic even exam exit eyes fact fair fern figs film fish " +
        "fizz flap flew flux foxy free frog fuel fund gala game gear gems gift girl glow " +
        "good gray grim guru gush gyro half hang hard hawk heat help high hill holy hope " +
        "horn huts iced idea idle inch inky into iris iron item jade jazz join jolt jowl " +
        "judo jugs jump junk jury keep keno kept keys kick kiln king kite kiwi knob lamb " +
        "lava lazy leaf legs liar limp lion list logo loud love luau luck lung main many " +
        "math maze memo menu meow mild mint miss monk nail navy need news next noon note " +
        "numb obey oboe omit onyx open oval owls paid part peck play plus poem pool pose " +
        "puff puma purr quad quiz race ramp real redo rich road rock roof ruby ruin runs " +
        "rust safe saga scar sets silk skew slot soap solo song stub surf swan taco task " +
        "taxi tent tied time tiny toil tomb toys trip tuna twin ugly undo unit urge user " +
        "vast very veto vial vibe view visa void vows wall wand warm wasp wave waxy webs " +
        "what when whiz wolf work yank yawn yell yoga yurt zaps zero zest zinc zone zoom"
        ).split(" ")

    private val wordToByte: Map<String, Int> =
        WORDS.withIndex().associate { (i, w) -> w to i }

    private val minimalToByte: Map<String, Int> =
        WORDS.withIndex().associate { (i, w) -> "${w.first()}${w.last()}" to i }

    init {
        check(WORDS.size == 256) { "wordlist must contain exactly 256 words, found ${WORDS.size}" }
        check(minimalToByte.size == 256) { "first+last letter pairs must be unique" }
    }

    fun encode(payload: ByteArray, style: Style): String {
        val full = payload + checksum(payload)
        return when (style) {
            Style.STANDARD -> full.joinToString(" ") { WORDS[it.toInt() and 0xff] }
            Style.URI -> full.joinToString("-") { WORDS[it.toInt() and 0xff] }
            Style.MINIMAL -> buildString(full.size * 2) {
                for (b in full) {
                    val w = WORDS[b.toInt() and 0xff]
                    append(w.first()); append(w.last())
                }
            }
        }
    }

    /**
     * Decodes a bytewords string and verifies its checksum, returning the
     * payload (without the 4 checksum bytes). Throws [IllegalArgumentException]
     * on an unknown word, malformed input, or checksum mismatch.
     */
    fun decode(text: String, style: Style): ByteArray {
        val bytes = when (style) {
            Style.STANDARD -> decodeWords(text.trim().split(' ').filter { it.isNotEmpty() }, wordToByte)
            Style.URI -> decodeWords(text.trim().split('-').filter { it.isNotEmpty() }, wordToByte)
            Style.MINIMAL -> decodeMinimal(text.trim())
        }
        require(bytes.size >= CHECKSUM_LENGTH) { "input too short to contain a checksum" }
        val payload = bytes.copyOfRange(0, bytes.size - CHECKSUM_LENGTH)
        val provided = bytes.copyOfRange(bytes.size - CHECKSUM_LENGTH, bytes.size)
        require(checksum(payload).contentEquals(provided)) { "bytewords checksum mismatch" }
        return payload
    }

    private fun decodeWords(tokens: List<String>, table: Map<String, Int>): ByteArray {
        val out = ByteArray(tokens.size)
        for (i in tokens.indices) {
            val key = tokens[i].lowercase()
            val value = table[key] ?: throw IllegalArgumentException("unknown byteword: ${tokens[i]}")
            out[i] = value.toByte()
        }
        return out
    }

    private fun decodeMinimal(text: String): ByteArray {
        val clean = text.lowercase()
        require(clean.length % 2 == 0) { "minimal bytewords must have an even character count" }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val pair = clean.substring(i, i + 2)
            val value = minimalToByte[pair] ?: throw IllegalArgumentException("unknown byteword pair: $pair")
            out[i / 2] = value.toByte()
            i += 2
        }
        return out
    }

    /** CRC-32 over [payload], emitted big-endian (network order) per the spec. */
    private fun checksum(payload: ByteArray): ByteArray {
        val crc = CRC32().apply { update(payload) }.value
        return byteArrayOf(
            (crc ushr 24 and 0xff).toByte(),
            (crc ushr 16 and 0xff).toByte(),
            (crc ushr 8 and 0xff).toByte(),
            (crc and 0xff).toByte(),
        )
    }
}
