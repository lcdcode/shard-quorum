package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ShareReaderTest {

    private val random = SecureRandom()

    @Test
    fun parsesShareFromShareUr() {
        val share = Sskr.generate(2, 3, ByteArray(16) { it.toByte() }, random).first()
        val parsed = ShareReader.parse(Ur.toUr(share))
        assertTrue(parsed is ShareImport.Share)
        parsed as ShareImport.Share
        assertArrayEquals(share, parsed.bytes)
        assertEquals(2, parsed.metadata.memberThreshold)
    }

    @Test
    fun parsesShareFromTypedStandardBytewords() {
        val share = Sskr.generate(2, 3, ByteArray(16) { it.toByte() }, random)[1]
        val parsed = ShareReader.parse(Ur.toStandardBytewords(share))
        assertTrue(parsed is ShareImport.Share)
        assertArrayEquals(share, (parsed as ShareImport.Share).bytes)
    }

    @Test
    fun parsesEnvelopeFromEnvelopeUr() {
        val kek = KekEnvelope.generateKek(random)
        val envelope = KekEnvelope.seal(kek, "secret".toByteArray(), random)
        val parsed = ShareReader.parse(Ur.toEnvelopeUr(envelope))
        assertTrue(parsed is ShareImport.Envelope)
        assertArrayEquals(envelope, (parsed as ShareImport.Envelope).bytes)
    }

    @Test
    fun isCaseAndWhitespaceTolerant() {
        val share = Sskr.generate(2, 3, ByteArray(16) { 1 }, random).first()
        val parsed = ShareReader.parse("  " + Ur.toUr(share).uppercase() + "  ")
        assertArrayEquals(share, (parsed as ShareImport.Share).bytes)
    }

    @Test
    fun rejectsUnrecognizedInput() {
        assertThrows(IllegalArgumentException::class.java) { ShareReader.parse("") }
        assertThrows(IllegalArgumentException::class.java) { ShareReader.parse("hello world") }
        assertThrows(IllegalArgumentException::class.java) {
            ShareReader.parse("ur:sskr/notvalidbytewords")
        }
    }
}
