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

    // A real quorum needs at least two shares. threshold == 1 is a degenerate
    // split where every share equals the secret and there is no digest share, so
    // it offers neither secrecy nor integrity; the scheme rejects it outright.
    const val MIN_THRESHOLD = 2

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Splits [secret] into [shareCount] shares with the given recovery
     * [threshold]. Returns a list indexed 0..shareCount-1, where element i is the
     * raw share value at x-coordinate i. Both [threshold] and [shareCount] must
     * be at least [MIN_THRESHOLD]; 1-of-N is rejected (see [MIN_THRESHOLD]).
     *
     * @param random source of cryptographic randomness; injected for testability.
     */
    fun split(
        threshold: Int,
        shareCount: Int,
        secret: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): List<ByteArray> {
        require(shareCount in MIN_THRESHOLD..MAX_SHARES) {
            "shareCount must be in $MIN_THRESHOLD..$MAX_SHARES"
        }
        require(threshold in MIN_THRESHOLD..shareCount) {
            "threshold must be in $MIN_THRESHOLD..shareCount"
        }
        require(secret.size in MIN_SECRET_LENGTH..MAX_SECRET_LENGTH && secret.size % 2 == 0) {
            "secret must be an even length in $MIN_SECRET_LENGTH..$MAX_SECRET_LENGTH bytes"
        }

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
     * value. At least [threshold] shares must be supplied.
     *
     * Trust boundary: the caller is trusted only to supply share values; this
     * function decides nothing from the share headers (the SSKR layer reads the
     * threshold and passes it in). Integrity rests on the 4-byte HMAC digest
     * share, which makes assembling a wrong, insufficient, or corrupted set
     * detectable with ~2^-32 false-accept probability.
     *
     * The recovery polynomial is fixed by exactly [threshold] shares; any
     * surplus shares are then checked to lie on that same polynomial rather than
     * folded into a higher-degree fit (which could mask a single bad share).
     * Throws [IllegalArgumentException] on a digest mismatch or an inconsistent
     * surplus share.
     */
    fun combine(threshold: Int, shares: Map<Int, ByteArray>): ByteArray {
        require(threshold >= MIN_THRESHOLD) { "threshold must be >= $MIN_THRESHOLD" }
        require(shares.size >= threshold) { "need at least $threshold shares, got ${shares.size}" }

        val points = shares.map { it.key to it.value }
        val defining = points.take(threshold)
        val secret = Gf256.interpolate(defining, SECRET_INDEX)
        val digestShare = Gf256.interpolate(defining, DIGEST_INDEX)

        val recoveredDigest = digestShare.copyOfRange(0, DIGEST_LENGTH)
        val randomPart = digestShare.copyOfRange(DIGEST_LENGTH, digestShare.size)
        val expectedDigest = createDigest(randomPart, secret)
        require(constantTimeEquals(recoveredDigest, expectedDigest)) {
            "share digest mismatch: wrong, insufficient, or corrupted shares"
        }

        // Every surplus share must lie on the recovered polynomial; a mismatch
        // means the set mixes shares from different splits or includes a corrupt
        // one, even though the defining subset alone passed the digest.
        for ((x, value) in points.drop(threshold)) {
            val onPolynomial = Gf256.interpolate(defining, x)
            require(constantTimeEquals(onPolynomial, value)) {
                "inconsistent share at index $x: not on the recovered polynomial"
            }
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
