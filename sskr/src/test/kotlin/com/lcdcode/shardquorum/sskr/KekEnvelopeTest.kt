package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class KekEnvelopeTest {

    private val random = SecureRandom()

    @Test
    fun sealOpenRoundTrip() {
        val kek = KekEnvelope.generateKek(random)
        // Secret lengths the raw SSKR layer could not take directly: odd, short, long.
        for (length in listOf(1, 15, 16, 33, 64, 1024)) {
            val secret = ByteArray(length) { (it * 13).toByte() }
            val envelope = KekEnvelope.seal(kek, secret, random)
            assertArrayEquals(secret, KekEnvelope.open(kek, envelope))
        }
    }

    @Test
    fun protectRecoverEndToEnd() {
        val secret = "correct horse battery staple".toByteArray()
        val protected = KekEnvelope.protect(3, 5, secret, random)
        assertEquals(5, protected.shares.size)
        // Any 3 of 5 shares recover the secret.
        val quorum = listOf(protected.shares[4], protected.shares[0], protected.shares[2])
        assertArrayEquals(secret, KekEnvelope.recover(protected.envelope, quorum))
    }

    @Test
    fun recoverFailsBelowThreshold() {
        val protected = KekEnvelope.protect(3, 5, ByteArray(16) { 1 }, random)
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.recover(protected.envelope, protected.shares.take(2))
        }
    }

    @Test
    fun resealUnderSameKekKeepsExistingSharesValid() {
        // The reason the KEK layer exists: rotate the secret, not the shares.
        val kek = KekEnvelope.generateKek(random)
        val shares = Sskr.generate(2, 3, kek, random)
        val oldEnvelope = KekEnvelope.seal(kek, "old secret value!".toByteArray(), random)
        val newSecret = "new secret value!".toByteArray()
        val newEnvelope = KekEnvelope.seal(kek, newSecret, random)
        assertFalse(oldEnvelope.contentEquals(newEnvelope))
        assertArrayEquals(newSecret, KekEnvelope.recover(newEnvelope, shares.drop(1)))
    }

    @Test
    fun wrongKekRejected() {
        val secret = ByteArray(32) { 7 }
        val envelope = KekEnvelope.seal(KekEnvelope.generateKek(random), secret, random)
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.open(KekEnvelope.generateKek(random), envelope)
        }
    }

    @Test
    fun wrongSharesRejected() {
        // A valid quorum from a DIFFERENT split must not open this envelope.
        val protected = KekEnvelope.protect(2, 3, ByteArray(16) { 3 }, random)
        val other = KekEnvelope.protect(2, 3, ByteArray(16) { 4 }, random)
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.recover(protected.envelope, other.shares.take(2))
        }
    }

    @Test
    fun tamperedCiphertextRejected() {
        val kek = KekEnvelope.generateKek(random)
        val envelope = KekEnvelope.seal(kek, ByteArray(20) { 9 }, random)
        val tampered = envelope.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { KekEnvelope.open(kek, tampered) }
    }

    @Test
    fun tamperedNonceRejected() {
        val kek = KekEnvelope.generateKek(random)
        val envelope = KekEnvelope.seal(kek, ByteArray(20) { 9 }, random)
        val tampered = envelope.copyOf().also { it[6] = (it[6] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { KekEnvelope.open(kek, tampered) }
    }

    @Test
    fun badMagicRejected() {
        val kek = KekEnvelope.generateKek(random)
        val envelope = KekEnvelope.seal(kek, ByteArray(16) { 5 }, random)
        val tampered = envelope.copyOf().also { it[0] = 'X'.code.toByte() }
        val error = assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.open(kek, tampered)
        }
        assertEquals("not a ShardQuorum envelope (bad magic)", error.message)
    }

    @Test
    fun unknownVersionRejected() {
        val kek = KekEnvelope.generateKek(random)
        val envelope = KekEnvelope.seal(kek, ByteArray(16) { 5 }, random)
        val tampered = envelope.copyOf().also { it[4] = 0x7f }
        assertThrows(IllegalArgumentException::class.java) { KekEnvelope.open(kek, tampered) }
    }

    @Test
    fun truncatedEnvelopeRejected() {
        val kek = KekEnvelope.generateKek(random)
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.open(kek, ByteArray(20))
        }
    }

    @Test
    fun invalidArgumentsRejected() {
        val kek = KekEnvelope.generateKek(random)
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.seal(ByteArray(16), ByteArray(16) { 1 }, random)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.seal(kek, ByteArray(0), random)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KekEnvelope.open(ByteArray(31), ByteArray(64))
        }
    }

    @Test
    fun noncesAreUniquePerSeal() {
        val kek = KekEnvelope.generateKek(random)
        val secret = ByteArray(16) { 2 }
        val first = KekEnvelope.seal(kek, secret, random)
        val second = KekEnvelope.seal(kek, secret, random)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun envelopeAndSharesAreQrEncodable() {
        // Integration with the UR layer: shares of a protected secret survive
        // the full QR encode/decode round trip.
        val secret = ByteArray(24) { (it + 1).toByte() }
        val protected = KekEnvelope.protect(2, 3, secret, random)
        val scanned = protected.shares.map { Ur.fromUr(Ur.toUr(it)) }
        assertArrayEquals(secret, KekEnvelope.recover(protected.envelope, scanned.take(2)))
    }
}
