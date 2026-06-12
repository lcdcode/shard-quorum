package com.lcdcode.shardquorum.sskr

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KEK (key-encrypting-key) envelope: the indirection layer between the user's
 * secret and the distributed shares.
 *
 * Instead of sharding the secret directly, [protect] generates a random 256-bit
 * KEK, encrypts the secret under it with AES-256-GCM, and shards the KEK via
 * SSKR. Consequences:
 *  - The secret can be re-sealed (changed) under the same KEK without
 *    invalidating shares already distributed.
 *  - Share size is fixed (32-byte KEK) regardless of secret length, and the
 *    secret itself is not bound by SSKR's 16..32-even-bytes constraint.
 *  - Reconstruction needs BOTH a share quorum and the envelope ciphertext; the
 *    envelope alone reveals nothing without the KEK.
 *
 * Envelope wire format (versioned, app-internal, not an interop format):
 * ```
 *   magic    4 bytes   "SQKE"
 *   version  1 byte    0x01
 *   nonce   12 bytes   random per seal
 *   ct       n bytes   AES-256-GCM ciphertext incl. 16-byte auth tag
 * ```
 * The magic+version header is bound as GCM associated data, so header
 * tampering fails authentication rather than being silently accepted.
 *
 * Callers own the lifetime of secret/KEK arrays they pass in and should zero
 * them when done; this object zeroes the KEKs it generates internally.
 */
object KekEnvelope {
    const val KEK_LENGTH = 32

    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH = TAG_LENGTH_BITS / 8
    private const val VERSION: Byte = 0x01
    private val MAGIC = "SQKE".toByteArray(Charsets.US_ASCII)
    private val HEADER = MAGIC + VERSION
    private val HEADER_LENGTH = HEADER.size
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Envelope ciphertext plus the SSKR shares of the KEK that seals it. */
    class Protected(val envelope: ByteArray, val shares: List<ByteArray>)

    /**
     * One-shot protection of [secret]: generates a fresh KEK, seals the secret
     * under it, and splits the KEK into [shareCount] SSKR shares with the given
     * [threshold]. The KEK is zeroed before returning; it exists nowhere except
     * inside the shares.
     */
    fun protect(
        threshold: Int,
        shareCount: Int,
        secret: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): Protected {
        val kek = generateKek(random)
        try {
            val envelope = seal(kek, secret, random)
            val shares = Sskr.generate(threshold, shareCount, kek, random)
            return Protected(envelope, shares)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Reconstructs the KEK from [shares] and opens [envelope] with it. The
     * reconstructed KEK is zeroed before returning.
     */
    fun recover(envelope: ByteArray, shares: List<ByteArray>): ByteArray {
        val kek = Sskr.combine(shares)
        try {
            return open(kek, envelope)
        } finally {
            kek.fill(0)
        }
    }

    fun generateKek(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(KEK_LENGTH).also(random::nextBytes)

    /** Encrypts [secret] under [kek] with a fresh random nonce. */
    fun seal(kek: ByteArray, secret: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(kek.size == KEK_LENGTH) { "KEK must be $KEK_LENGTH bytes, got ${kek.size}" }
        require(secret.isNotEmpty()) { "secret must not be empty" }
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(kek, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce),
            )
            updateAAD(HEADER)
        }
        return HEADER + nonce + cipher.doFinal(secret)
    }

    /**
     * Authenticates and decrypts [envelope] with [kek]. Throws
     * [IllegalArgumentException] on a malformed envelope, an unsupported
     * version, a wrong KEK, or any tampering (GCM authentication failure).
     */
    fun open(kek: ByteArray, envelope: ByteArray): ByteArray {
        require(kek.size == KEK_LENGTH) { "KEK must be $KEK_LENGTH bytes, got ${kek.size}" }
        require(envelope.size > HEADER_LENGTH + NONCE_LENGTH + TAG_LENGTH) {
            "envelope too short (${envelope.size} bytes) to contain header, nonce, and ciphertext"
        }
        val header = envelope.copyOfRange(0, HEADER_LENGTH)
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "not a ShardQuorum envelope (bad magic)"
        }
        require(header[MAGIC.size] == VERSION) {
            "unsupported envelope version ${header[MAGIC.size]}"
        }
        val nonce = envelope.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + NONCE_LENGTH)
        val ciphertext = envelope.copyOfRange(HEADER_LENGTH + NONCE_LENGTH, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(kek, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce),
            )
            updateAAD(header)
        }
        try {
            return cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException(
                "envelope authentication failed: wrong shares/KEK or tampered envelope", e,
            )
        }
    }
}
