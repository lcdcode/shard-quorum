package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gf256Test {

    /**
     * FIPS-197 worked example: in the AES field, 0x57 * 0x83 = 0xc1. This pins
     * the reducing polynomial to 0x11b independently of the table generator.
     */
    @Test
    fun multiplicationMatchesFips197() {
        assertEquals(0xc1, Gf256.mul(0x57, 0x83))
        assertEquals(0xc1, Gf256.mul(0x83, 0x57)) // commutativity
        assertEquals(0xfe, Gf256.mul(0x57, 0x13)) // second FIPS-197 example
    }

    @Test
    fun additionIsXor() {
        assertEquals(0x57 xor 0x83, Gf256.add(0x57, 0x83))
        assertEquals(0, Gf256.add(0xab, 0xab)) // every element is its own inverse
    }

    @Test
    fun zeroMultiplicationAbsorbs() {
        assertEquals(0, Gf256.mul(0, 0xff))
        assertEquals(0, Gf256.mul(0xff, 0))
    }

    @Test
    fun divisionInvertsMultiplication() {
        for (a in 0..255) {
            for (b in 1..255) {
                val product = Gf256.mul(a, b)
                assertEquals("($a * $b) / $b should be $a", a, Gf256.div(product, b))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun divisionByZeroThrows() {
        Gf256.div(0x10, 0)
    }

    /**
     * If 3 is a primitive generator, the exp table must visit all 255 nonzero
     * elements exactly once. We verify indirectly: every nonzero element has a
     * multiplicative inverse, which only holds when the tables form a bijection.
     */
    @Test
    fun everyNonzeroElementHasAnInverse() {
        for (a in 1..255) {
            val inverse = Gf256.div(1, a)
            assertEquals("$a * inverse($a) should be 1", 1, Gf256.mul(a, inverse))
            assertTrue("inverse must be nonzero", inverse != 0)
        }
    }

    /** A degree-1 polynomial through two points reconstructs at any x. */
    @Test
    fun interpolationRecoversConstantTerm() {
        // Line through (1, {0x10}) and (2, {0x20}); evaluate at x = 0.
        val points = listOf(1 to byteArrayOf(0x10), 2 to byteArrayOf(0x20))
        val atZero = Gf256.interpolate(points, 0)
        // Reconstruct the same line from its value at 0 and one original point.
        val rebuilt = Gf256.interpolate(listOf(0 to atZero, 1 to byteArrayOf(0x10)), 2)
        assertEquals(0x20.toByte(), rebuilt[0])
    }
}
