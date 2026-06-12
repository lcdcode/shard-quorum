package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BytewordsTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun roundTripsEveryStyle() {
        val payload = ByteArray(40) { (it * 6 + 1).toByte() }
        for (style in Bytewords.Style.entries) {
            val encoded = Bytewords.encode(payload, style)
            assertArrayEquals("style $style", payload, Bytewords.decode(encoded, style))
        }
    }

    @Test
    fun minimalUsesTwoCharsPerByte() {
        val payload = bytes(0x00, 0xff)
        // 2 payload bytes + 4 checksum bytes = 6 bytes -> 12 chars.
        assertEquals(12, Bytewords.encode(payload, Bytewords.Style.MINIMAL).length)
    }

    @Test
    fun firstAndLastByteWordsAreCorrect() {
        // 0x00 -> "able", 0xff -> "zoom".
        val standard = Bytewords.encode(bytes(0x00), Bytewords.Style.STANDARD)
        assertEquals("able", standard.substringBefore(' '))
        assertEquals("zoom", Bytewords.encode(bytes(0xff), Bytewords.Style.STANDARD).substringBefore(' '))
    }

    @Test
    fun decodeIsCaseInsensitive() {
        val payload = bytes(0x10, 0x20, 0x30)
        val encoded = Bytewords.encode(payload, Bytewords.Style.STANDARD).uppercase()
        assertArrayEquals(payload, Bytewords.decode(encoded, Bytewords.Style.STANDARD))
    }

    @Test
    fun corruptedChecksumIsRejected() {
        val payload = bytes(0xaa, 0xbb, 0xcc, 0xdd)
        val words = Bytewords.encode(payload, Bytewords.Style.STANDARD).split(' ').toMutableList()
        words[words.size - 1] = "zoom" // clobber the last checksum word
        assertThrows(IllegalArgumentException::class.java) {
            Bytewords.decode(words.joinToString(" "), Bytewords.Style.STANDARD)
        }
    }

    @Test
    fun unknownWordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Bytewords.decode("able acid zzzz", Bytewords.Style.STANDARD)
        }
    }

    @Test
    fun oddLengthMinimalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Bytewords.decode("abc", Bytewords.Style.MINIMAL)
        }
    }

    @Test
    fun tooShortInputIsRejected() {
        // Three words decode to 3 bytes, fewer than the 4-byte checksum.
        assertThrows(IllegalArgumentException::class.java) {
            Bytewords.decode("able acid also", Bytewords.Style.STANDARD)
        }
    }
}
