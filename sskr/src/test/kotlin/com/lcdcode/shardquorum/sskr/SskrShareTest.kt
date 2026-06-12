package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SskrShareTest {

    private fun value(size: Int = 16): ByteArray = ByteArray(size) { (0xa0 + it).toByte() }

    /** Pins the exact header byte layout against a hand-computed example. */
    @Test
    fun serializesHeaderExactly() {
        val share = SskrShare(
            identifier = 0x1234,
            groupThreshold = 1,
            groupCount = 1,
            groupIndex = 0,
            memberThreshold = 2,
            memberIndex = 3,
            value = value(16),
        )
        val bytes = share.serialize()
        // byte2 = (GT-1)<<4 | (GC-1) = 0x00; byte3 = GI<<4 | (MT-1) = 0x01; byte4 = MI = 0x03
        val expectedHeader = byteArrayOf(0x12, 0x34, 0x00, 0x01, 0x03)
        assertArrayEquals(expectedHeader, bytes.copyOfRange(0, SskrShare.METADATA_LENGTH))
        assertArrayEquals(value(16), bytes.copyOfRange(SskrShare.METADATA_LENGTH, bytes.size))
    }

    @Test
    fun packsMaximumFieldValues() {
        val share = SskrShare(
            identifier = 0xffff,
            groupThreshold = 16,
            groupCount = 16,
            groupIndex = 15,
            memberThreshold = 16,
            memberIndex = 15,
            value = value(16),
        )
        val bytes = share.serialize()
        assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f),
            bytes.copyOfRange(0, SskrShare.METADATA_LENGTH),
        )
    }

    @Test
    fun roundTripsThroughSerialization() {
        val original = SskrShare(0xabcd, 2, 3, 1, 4, 9, value(32))
        assertEquals(original, SskrShare.deserialize(original.serialize()))
    }

    @Test
    fun rejectsNonZeroReservedNibble() {
        val bytes = SskrShare(0x1234, 1, 1, 0, 2, 3, value(16)).serialize()
        bytes[4] = (bytes[4].toInt() or 0x10).toByte() // set a reserved bit
        assertThrows(IllegalArgumentException::class.java) { SskrShare.deserialize(bytes) }
    }

    @Test
    fun rejectsTooShortInput() {
        assertThrows(IllegalArgumentException::class.java) {
            SskrShare.deserialize(byteArrayOf(0, 0, 0, 0, 0)) // header only, no value
        }
    }

    @Test
    fun rejectsOutOfRangeFields() {
        assertThrows(IllegalArgumentException::class.java) {
            SskrShare(0x1234, 1, 1, 0, 17, 3, value(16)) // memberThreshold > 16
        }
        assertThrows(IllegalArgumentException::class.java) {
            SskrShare(0x1234, 3, 2, 0, 2, 3, value(16)) // groupThreshold > groupCount
        }
    }
}
