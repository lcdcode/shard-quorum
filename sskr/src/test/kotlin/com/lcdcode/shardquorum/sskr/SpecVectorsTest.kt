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

    /**
     * End-to-end reconstruction of the official BCR-2020-011 worked example
     * (docs/reference/bcr-2020-011-sskr.md), the only published SSKR vector that
     * ships a full share set alongside its master secret. These shares were
     * produced by the Blockchain Commons reference implementation, so recovering
     * the exact master secret from them validates our GF(256) Shamir AND the
     * HMAC digest-share construction cross-tool, not merely against ourselves.
     *
     * The vector is a 2-group split (group threshold 2; group 0 is 2-of-3,
     * group 1 is 3-of-5). ShardQuorum's Sskr facade is single-group only, so the
     * outer group layer is reconstructed here directly via Shamir.combine: each
     * group's members rebuild that group's share value, then the group values
     * rebuild the master secret. The digest is verified at BOTH Shamir layers.
     */
    @Test
    fun officialMultiGroupVectorReconstructsMasterSecret() {
        val masterSecret = hex("7daa851251002874e1a1995f0897e6b1")
        val shares = listOf(
            // Group 0: 2 of 3.
            "4bbf1101003e990c1f0435e2b33c721535c74603d0",
            "4bbf1101010c8ba39a7502a325ed07b8d597d1b80f",
            "4bbf1101025abd490ee65b6084859854ee67736e75",
            // Group 1: 3 of 5.
            "4bbf11120044ef453f66923d32653b377de5c94b39",
            "4bbf1112016ffb1b0cc5ab485f5a67136c802bc67b",
            "4bbf111202a3763155fcfdb5887abce6ee69c4bbcd",
            "4bbf11120388626f665fc4c0e545e0c2ff0c26368f",
            "4bbf1112046334a0db7838a5c6c4d2dcb2e5b65911",
        ).map { SskrShare.deserialize(hex(it)) }

        // Recover each group's share value from a threshold-sized subset of its
        // members; Shamir.combine throws if the digest at x=254 fails to match.
        val groupValues = shares.groupBy { it.groupIndex }.mapValues { (_, members) ->
            val threshold = members.first().memberThreshold
            val subset = members.take(threshold).associate { it.memberIndex to it.value }
            Shamir.combine(threshold, subset)
        }

        // The outer group layer: group values sit at x = groupIndex and rebuild
        // the master secret, again digest-checked at x=254.
        val groupThreshold = shares.first().groupThreshold
        val recovered = Shamir.combine(groupThreshold, groupValues)

        assertArrayEquals(masterSecret, recovered)
    }
}
