package com.lcdcode.shardquorum.sskr

import java.security.SecureRandom

/**
 * High-level SSKR facade for the single-group case: a plain K-of-N split with no
 * outer group layer (groupThreshold = groupCount = 1).
 *
 * This is the scheme ShardQuorum v1 exposes. Multi-group SSKR (a threshold of
 * groups, each with its own member threshold) adds an outer Shamir layer over
 * per-group secrets and is intentionally not implemented yet; [combine] rejects
 * multi-group input rather than silently mishandling it.
 */
object Sskr {
    private const val SINGLE_GROUP_INDEX = 0
    private const val SINGLE_GROUP = 1

    // A real quorum needs at least two shares. threshold == 1 is a degenerate
    // split where every share equals the secret and there is no digest share
    // (no integrity), so the SSKR layer rejects it even though the lower-level
    // Shamir primitive can express it. This is the library-level floor; the UI
    // may impose a higher one.
    private const val MIN_THRESHOLD = 2

    /**
     * Splits [secret] into [memberCount] serialized SSKR shares, any
     * [memberThreshold] of which reconstruct it. All shares share one random
     * 16-bit identifier so they can be recognized as belonging together.
     */
    fun generate(
        memberThreshold: Int,
        memberCount: Int,
        secret: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): List<ByteArray> {
        require(memberThreshold >= MIN_THRESHOLD) {
            "memberThreshold must be >= $MIN_THRESHOLD (1-of-N provides no protection)"
        }
        val identifier = random.nextInt(0x1_0000)
        val values = Shamir.split(memberThreshold, memberCount, secret, random)
        return values.mapIndexed { memberIndex, value ->
            SskrShare(
                identifier = identifier,
                groupThreshold = SINGLE_GROUP,
                groupCount = SINGLE_GROUP,
                groupIndex = SINGLE_GROUP_INDEX,
                memberThreshold = memberThreshold,
                memberIndex = memberIndex,
                value = value,
            ).serialize()
        }
    }

    /**
     * Reconstructs the secret from serialized [shares]. The shares must all carry
     * the same identifier, belong to a single group, and agree on the member
     * threshold; at least that many distinct members must be present.
     */
    fun combine(shares: List<ByteArray>): ByteArray {
        require(shares.isNotEmpty()) { "no shares provided" }
        val parsed = shares.map(SskrShare::deserialize)

        val identifiers = parsed.map { it.identifier }.toSet()
        require(identifiers.size == 1) { "shares come from different splits (identifier mismatch)" }
        require(parsed.all { it.groupCount == SINGLE_GROUP && it.groupThreshold == SINGLE_GROUP }) {
            "multi-group SSKR is not supported yet"
        }
        require(parsed.all { it.groupIndex == SINGLE_GROUP_INDEX }) { "unexpected group index" }

        val memberThreshold = parsed.map { it.memberThreshold }.toSet().single()
        require(memberThreshold >= MIN_THRESHOLD) {
            "memberThreshold must be >= $MIN_THRESHOLD (1-of-N provides no protection)"
        }
        // Deduplicate by member index (last value wins): identical duplicates are
        // harmless, and a conflicting survivor is still caught by Shamir's
        // digest/consistency checks.
        val byIndex = parsed.associate { it.memberIndex to it.value }
        require(byIndex.size >= memberThreshold) {
            "need at least $memberThreshold distinct shares, got ${byIndex.size}"
        }
        return Shamir.combine(memberThreshold, byIndex)
    }
}
