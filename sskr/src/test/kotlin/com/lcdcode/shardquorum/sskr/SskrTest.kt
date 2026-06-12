package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class SskrTest {

    private val rng = SecureRandom().apply { setSeed(byteArrayOf(9, 8, 7, 6)) }

    private fun secret(size: Int): ByteArray = ByteArray(size) { ((it * 11 + 3) and 0xff).toByte() }

    @Test
    fun endToEndRoundTrip() {
        val original = secret(32)
        val shares = Sskr.generate(3, 5, original, rng)
        assertEquals(5, shares.size)
        // Any 3 of the 5 serialized shares reconstruct the secret.
        assertArrayEquals(original, Sskr.combine(shares.slice(listOf(0, 2, 4))))
        assertArrayEquals(original, Sskr.combine(shares.slice(listOf(1, 3, 4))))
    }

    @Test
    fun allSharesShareOneIdentifier() {
        val shares = Sskr.generate(2, 4, secret(16), rng)
        val ids = shares.map { SskrShare.deserialize(it).identifier }.toSet()
        assertEquals(1, ids.size)
    }

    @Test
    fun duplicateSharesDoNotInflateCount() {
        val shares = Sskr.generate(3, 5, secret(16), rng)
        val withDuplicate = listOf(shares[0], shares[0], shares[1])
        // Three byte arrays but only two distinct members: below threshold.
        assertThrows(IllegalArgumentException::class.java) { Sskr.combine(withDuplicate) }
    }

    @Test
    fun rejectsMixedIdentifiers() {
        val a = Sskr.generate(2, 3, secret(16), rng)
        val b = Sskr.generate(2, 3, secret(16), rng)
        assertThrows(IllegalArgumentException::class.java) {
            Sskr.combine(listOf(a[0], b[1]))
        }
    }

    @Test
    fun tamperedShareValueIsRejected() {
        val shares = Sskr.generate(2, 3, secret(16), rng).toMutableList()
        val corrupt = shares[0].copyOf().also { it[SskrShare.METADATA_LENGTH] = (it[5] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            Sskr.combine(listOf(corrupt, shares[1]))
        }
    }
}
