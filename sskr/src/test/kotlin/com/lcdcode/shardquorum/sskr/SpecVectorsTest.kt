package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-tool validation against the official test vectors in
 * docs/reference/bcr-2020-012-bytewords.md and bcr-2020-011-sskr.md. Passing
 * these means ShardQuorum's encoding is byte-identical to the Blockchain Commons
 * reference, not merely internally self-consistent.
 */
class SpecVectorsTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }

    // --- Bytewords spec (BCR-2020-012) ---

    @Test
    fun standardSeedVector() {
        val body = hex("d99d6ca20150c7098580125e2ab0981253468b2dbc5202c11947da")
        val expected = "tuna next jazz oboe acid good slot axis limp lava " +
            "brag holy door puff monk brag guru frog luau drop " +
            "roof grim also safe chef fuel twin solo aqua work bald"
        assertEquals(expected, Bytewords.encode(body, Bytewords.Style.STANDARD))
        assertEquals(
            "tantjzoeadgdstaslplabghydrpfmkbggufgludprfgmaosecffltnsoaawkbd",
            Bytewords.encode(body, Bytewords.Style.MINIMAL),
        )
        assertArrayEquals(body, Bytewords.decode(expected, Bytewords.Style.STANDARD))
    }

    @Test
    fun brutalSeedVector() {
        val payload = hex("c7098580125e2ab0981253468b2dbc52")
        val expectedStandard = "slot axis limp lava brag holy door puff monk brag " +
            "guru frog luau drop roof grim zone plus belt wand"
        assertEquals(expectedStandard, Bytewords.encode(payload, Bytewords.Style.STANDARD))
        assertEquals(
            "staslplabghydrpfmkbggufgludprfgmzepsbtwd",
            Bytewords.encode(payload, Bytewords.Style.MINIMAL),
        )
        assertArrayEquals(
            payload,
            Bytewords.decode("staslplabghydrpfmkbggufgludprfgmzepsbtwd", Bytewords.Style.MINIMAL),
        )
    }

    // --- SSKR spec (BCR-2020-011) ---

    @Test
    fun sskrShareHeaderDecodesToSpecFields() {
        val share = SskrShare.deserialize(hex("4bbf1101025abd490ee65b6084859854ee67736e75"))
        assertEquals(0x4bbf, share.identifier)
        assertEquals(2, share.groupThreshold)
        assertEquals(2, share.groupCount)
        assertEquals(0, share.groupIndex)
        assertEquals(2, share.memberThreshold)
        assertEquals(2, share.memberIndex)
        assertArrayEquals(hex("5abd490ee65b6084859854ee67736e75"), share.value)
        // Re-serialization reproduces the canonical bytes exactly.
        assertArrayEquals(hex("4bbf1101025abd490ee65b6084859854ee67736e75"), share.serialize())
    }

    @Test
    fun sskrShareStandardBytewordsVector() {
        // The CBOR-tagged (#6.40309) share as shown in the spec.
        val tagged = hex("d99d75554bbf1101025abd490ee65b6084859854ee67736e75")
        val expected = "tuna next keep gyro gear runs body acid also heat " +
            "ruby gala beta visa help horn liar limp monk gush " +
            "waxy into junk jolt keep lion leaf ruby purr"
        assertEquals(expected, Bytewords.encode(tagged, Bytewords.Style.STANDARD))
    }
}
