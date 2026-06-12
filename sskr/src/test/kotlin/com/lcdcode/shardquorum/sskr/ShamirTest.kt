package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class ShamirTest {

    private val rng = SecureRandom().apply { setSeed(byteArrayOf(1, 2, 3, 4)) }

    private fun secret(size: Int): ByteArray =
        ByteArray(size) { ((it * 7 + 1) and 0xff).toByte() }

    private fun shareMap(shares: List<ByteArray>, indices: List<Int>): Map<Int, ByteArray> =
        indices.associateWith { shares[it] }

    @Test
    fun roundTripAcrossSchemesAndSecretSizes() {
        val schemes = listOf(2 to 3, 3 to 5, 5 to 5, 1 to 1, 16 to 16, 9 to 16)
        for (size in listOf(16, 32)) {
            val original = secret(size)
            for ((threshold, count) in schemes) {
                val shares = Shamir.split(threshold, count, original, rng)
                assertEquals(count, shares.size)
                // Reconstruct from the first `threshold` shares.
                val recovered = Shamir.combine(threshold, shareMap(shares, (0 until threshold).toList()))
                assertArrayEquals("scheme $threshold-of-$count size $size", original, recovered)
            }
        }
    }

    @Test
    fun anyThresholdSizedSubsetReconstructs() {
        val original = secret(32)
        val shares = Shamir.split(3, 5, original, rng)
        val subsets = listOf(
            listOf(0, 1, 2), listOf(0, 1, 4), listOf(2, 3, 4), listOf(1, 3, 4),
        )
        for (subset in subsets) {
            assertArrayEquals(original, Shamir.combine(3, shareMap(shares, subset)))
        }
    }

    @Test
    fun supersetOfThresholdReconstructs() {
        val original = secret(32)
        val shares = Shamir.split(3, 5, original, rng)
        // All five shares (more than the threshold) still reconstruct.
        assertArrayEquals(original, Shamir.combine(3, shareMap(shares, (0 until 5).toList())))
    }

    @Test
    fun insufficientSharesDoNotRecoverSecret() {
        val original = secret(32)
        val shares = Shamir.split(3, 5, original, rng)
        // Two shares with a threshold of 3: the digest check must reject this.
        val tooFew = shareMap(shares, listOf(0, 1))
        assertThrows(IllegalArgumentException::class.java) {
            Shamir.combine(3, tooFew)
        }
    }

    @Test
    fun corruptedShareIsDetected() {
        val original = secret(32)
        val shares = Shamir.split(2, 3, original, rng)
        val tampered = shares[0].copyOf().also { it[5] = (it[5] + 1).toByte() }
        val withTampered = mapOf(0 to tampered, 1 to shares[1])
        assertThrows(IllegalArgumentException::class.java) {
            Shamir.combine(2, withTampered)
        }
    }

    @Test
    fun thresholdOneYieldsSecretInEveryShare() {
        val original = secret(16)
        val shares = Shamir.split(1, 4, original, rng)
        for (share in shares) assertArrayEquals(original, share)
        assertArrayEquals(original, Shamir.combine(1, mapOf(2 to shares[2])))
    }

    @Test
    fun distinctSplitsProduceDistinctShares() {
        // Randomized splits of the same secret must differ (fresh digest/random part).
        val original = secret(32)
        val a = Shamir.split(2, 3, original, rng)[0]
        val b = Shamir.split(2, 3, original, rng)[0]
        assertFalse("two independent splits should not collide", a.contentEquals(b))
    }

    @Test
    fun rejectsOutOfRangeParameters() {
        val s = secret(32)
        assertThrows(IllegalArgumentException::class.java) { Shamir.split(0, 3, s, rng) }
        assertThrows(IllegalArgumentException::class.java) { Shamir.split(4, 3, s, rng) }
        assertThrows(IllegalArgumentException::class.java) { Shamir.split(2, 17, s, rng) }
        assertThrows(IllegalArgumentException::class.java) {
            Shamir.split(2, 3, ByteArray(15), rng) // too short
        }
        assertThrows(IllegalArgumentException::class.java) {
            Shamir.split(2, 3, ByteArray(17), rng) // odd length
        }
    }
}
