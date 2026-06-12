package com.lcdcode.shardquorum.sskr

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GF(256) Shamir secret sharing as specified by SLIP-39 and reused by SSKR
 * (BCR-2020-011). This is the cryptographic core: it splits a fixed-size secret
 * into [shareCount] shares such that any [threshold] of them reconstruct it,
 * while fewer than [threshold] reveal nothing (information-theoretic security).
 *
 * Integrity: rather than store the secret directly at one interpolation point,
 * the scheme reserves x = 255 for the secret and x = 254 for a digest share
 * carrying a 4-byte HMAC-SHA256 over the secret. On reconstruction the digest is
 * recomputed and compared, so assembling the wrong or corrupt shares is detected
 * cryptographically (not merely by the bytewords CRC at the transcription layer).
 */
object Shamir {
    const val SECRET_INDEX = 255
    const val DIGEST_INDEX = 254
    const val DIGEST_LENGTH = 4
    const val MIN_SECRET_LENGTH = 16
    const val MAX_SECRET_LENGTH = 32
    const val MAX_SHARES = 16

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Splits [secret] into [shareCount] shares with the given recovery
     * [threshold]. Returns a list indexed 0..shareCount-1, where element i is the
     * raw share value at x-coordinate i.
     *
     * @param random source of cryptographic randomness; injected for testability.
     */
    fun split(
        threshold: Int,
        shareCount: Int,
        secret: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): List<ByteArray> {
        require(shareCount in 1..MAX_SHARES) { "shareCount must be in 1..$MAX_SHARES" }
        require(threshold in 1..shareCount) { "threshold must be in 1..shareCount" }
        require(secret.size in MIN_SECRET_LENGTH..MAX_SECRET_LENGTH && secret.size % 2 == 0) {
            "secret must be an even length in $MIN_SECRET_LENGTH..$MAX_SECRET_LENGTH bytes"
        }

        // Threshold of 1: every share is the secret itself (no field arithmetic).
        if (threshold == 1) return List(shareCount) { secret.copyOf() }

        // Build the base set the recovery polynomial is fixed by:
        //   - (threshold - 2) random shares at x = 0..(threshold-3)
        //   - the digest share at x = 254
        //   - the secret at x = 255
        val randomShareCount = threshold - 2
        val baseShares = ArrayList<Pair<Int, ByteArray>>(threshold)
        for (i in 0 until randomShareCount) {
            baseShares.add(i to ByteArray(secret.size).also(random::nextBytes))
        }
        val randomPart = ByteArray(secret.size - DIGEST_LENGTH).also(random::nextBytes)
        val digestShare = createDigest(randomPart, secret) + randomPart
        baseShares.add(DIGEST_INDEX to digestShare)
        baseShares.add(SECRET_INDEX to secret.copyOf())

        // The first randomShareCount outputs are reused directly; the rest are
        // interpolated from the base set.
        return List(shareCount) { i ->
            if (i < randomShareCount) baseShares[i].second.copyOf()
            else Gf256.interpolate(baseShares, i)
        }
    }

    /**
     * Reconstructs the secret from [shares], a map of x-coordinate to share
     * value. At least [threshold] shares must be supplied. Throws
     * [IllegalArgumentException] if the recomputed digest does not match, which
     * indicates wrong, insufficient, or corrupted shares.
     */
    fun combine(threshold: Int, shares: Map<Int, ByteArray>): ByteArray {
        require(threshold >= 1) { "threshold must be >= 1" }
        require(shares.size >= threshold) { "need at least $threshold shares, got ${shares.size}" }

        // Threshold of 1: any share is the secret; there is no digest share.
        if (threshold == 1) return shares.values.first().copyOf()

        val points = shares.map { it.key to it.value }
        val secret = Gf256.interpolate(points, SECRET_INDEX)
        val digestShare = Gf256.interpolate(points, DIGEST_INDEX)

        val recoveredDigest = digestShare.copyOfRange(0, DIGEST_LENGTH)
        val randomPart = digestShare.copyOfRange(DIGEST_LENGTH, digestShare.size)
        val expectedDigest = createDigest(randomPart, secret)
        require(constantTimeEquals(recoveredDigest, expectedDigest)) {
            "share digest mismatch: wrong, insufficient, or corrupted shares"
        }
        return secret
    }

    /** First [DIGEST_LENGTH] bytes of HMAC-SHA256(key = randomData, msg = secret). */
    private fun createDigest(randomData: ByteArray, secret: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(randomData, HMAC_ALGORITHM))
        return mac.doFinal(secret).copyOfRange(0, DIGEST_LENGTH)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
