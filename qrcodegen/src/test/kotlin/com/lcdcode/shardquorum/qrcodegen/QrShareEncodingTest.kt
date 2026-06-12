package com.lcdcode.shardquorum.qrcodegen

import com.lcdcode.shardquorum.sskr.Sskr
import com.lcdcode.shardquorum.sskr.Ur
import io.nayuki.qrcodegen.QrCode
import io.nayuki.qrcodegen.QrSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Smoke tests for the vendored Nayuki qrcodegen library against ShardQuorum's
 * actual usage: encoding "ur:sskr/..." share strings into QR codes. Upstream's
 * own test suite vouches for QR correctness; these pin the integration.
 */
class QrShareEncodingTest {

    private val random = SecureRandom()

    @Test
    fun uppercasedUrStringEncodesInAlphanumericMode() {
        // QR alphanumeric mode covers 0-9 A-Z space $%*+-./: but NOT lowercase.
        // Uppercasing the UR string (bytewords are case-insensitive) keeps the
        // QR in alphanumeric mode, roughly 45% smaller than byte mode.
        val share = Sskr.generate(2, 3, ByteArray(16) { it.toByte() }, random).first()
        val segments = QrSegment.makeSegments(Ur.toUr(share).uppercase())
        assertEquals(1, segments.size)
        assertEquals(QrSegment.Mode.ALPHANUMERIC, segments.single().mode)
    }

    @Test
    fun mixedCaseUrStringFallsBackToByteMode() {
        val share = Sskr.generate(2, 3, ByteArray(16) { it.toByte() }, random).first()
        val segments = QrSegment.makeSegments(Ur.toUr(share))
        assertEquals(QrSegment.Mode.BYTE, segments.single().mode)
    }

    @Test
    fun shareUrFitsInSmallQr() {
        // 16-byte secret -> 21-byte share -> 60-char UR; should stay near
        // version 3 (29x29), comfortably scannable from a printed page.
        val share = Sskr.generate(3, 5, ByteArray(16) { 7 }, random).first()
        val qr = QrCode.encodeText(Ur.toUr(share).uppercase(), QrCode.Ecc.MEDIUM)
        assertTrue("version ${qr.version} too large", qr.version <= 4)
        assertEquals(qr.version * 4 + 17, qr.size)
    }

    @Test
    fun maxLengthShareUrStillFitsReasonably() {
        // 32-byte secret -> 37-byte share -> 92-char UR.
        val share = Sskr.generate(3, 5, ByteArray(32) { 7 }, random).first()
        val qr = QrCode.encodeText(Ur.toUr(share).uppercase(), QrCode.Ecc.MEDIUM)
        assertTrue("version ${qr.version} too large", qr.version <= 6)
    }

    @Test
    fun qrContentRoundTripsToOriginalShare() {
        // What the QR carries (uppercased UR) must decode back to the share;
        // Ur/Bytewords decoding is case-insensitive by design.
        val share = Sskr.generate(2, 3, ByteArray(16) { 3 }, random).first()
        val qrContent = Ur.toUr(share).uppercase()
        assertArrayEquals(share, Ur.fromUr(qrContent))
    }

    @Test
    fun encodingIsDeterministic() {
        val share = Sskr.generate(2, 3, ByteArray(16) { 5 }, random).first()
        val text = Ur.toUr(share).uppercase()
        val first = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val second = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        assertEquals(first.size, second.size)
        for (y in 0 until first.size) {
            for (x in 0 until first.size) {
                assertEquals(first.getModule(x, y), second.getModule(x, y))
            }
        }
    }
}
