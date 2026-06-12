package com.lcdcode.shardquorum.sskr

/**
 * One SSKR share (BCR-2020-011): a 5-byte metadata header followed by the raw
 * GF(256) Shamir share value.
 *
 * Header bit layout (40 bits, big-endian within each byte):
 * ```
 *   identifier        16 bits   common to every share of one split
 *   groupThreshold-1   4 bits
 *   groupCount-1       4 bits
 *   groupIndex         4 bits
 *   memberThreshold-1  4 bits
 *   reserved (= 0)     4 bits
 *   memberIndex        4 bits
 * ```
 * Thresholds and counts are stored as (value - 1) so the 4-bit fields cover the
 * usable range 1..16; indices are stored raw (0..15).
 */
data class SskrShare(
    val identifier: Int,
    val groupThreshold: Int,
    val groupCount: Int,
    val groupIndex: Int,
    val memberThreshold: Int,
    val memberIndex: Int,
    val value: ByteArray,
) {
    init {
        require(identifier in 0..0xffff) { "identifier must fit in 16 bits" }
        require(groupThreshold in COUNT_RANGE) { "groupThreshold out of range" }
        require(groupCount in COUNT_RANGE) { "groupCount out of range" }
        require(groupThreshold <= groupCount) { "groupThreshold must be <= groupCount" }
        require(groupIndex in INDEX_RANGE) { "groupIndex out of range" }
        require(memberThreshold in COUNT_RANGE) { "memberThreshold out of range" }
        require(memberIndex in INDEX_RANGE) { "memberIndex out of range" }
        require(value.isNotEmpty()) { "share value must not be empty" }
    }

    /** Serializes to [METADATA_LENGTH] header bytes followed by the value. */
    fun serialize(): ByteArray {
        val out = ByteArray(METADATA_LENGTH + value.size)
        out[0] = (identifier ushr 8).toByte()
        out[1] = (identifier and 0xff).toByte()
        out[2] = (((groupThreshold - 1) shl 4) or (groupCount - 1)).toByte()
        out[3] = ((groupIndex shl 4) or (memberThreshold - 1)).toByte()
        out[4] = memberIndex.toByte() // reserved high nibble is 0
        value.copyInto(out, METADATA_LENGTH)
        return out
    }

    // Data classes with array fields need explicit equals/hashCode for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SskrShare) return false
        return identifier == other.identifier &&
            groupThreshold == other.groupThreshold &&
            groupCount == other.groupCount &&
            groupIndex == other.groupIndex &&
            memberThreshold == other.memberThreshold &&
            memberIndex == other.memberIndex &&
            value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = identifier
        result = 31 * result + groupThreshold
        result = 31 * result + groupCount
        result = 31 * result + groupIndex
        result = 31 * result + memberThreshold
        result = 31 * result + memberIndex
        result = 31 * result + value.contentHashCode()
        return result
    }

    companion object {
        const val METADATA_LENGTH = 5
        private val COUNT_RANGE = 1..16
        private val INDEX_RANGE = 0..15
        private const val RESERVED_MASK = 0xf0

        /** Parses a serialized share, validating the reserved nibble and field ranges. */
        fun deserialize(bytes: ByteArray): SskrShare {
            require(bytes.size > METADATA_LENGTH) { "share too short to contain a value" }
            require(bytes[4].toInt() and RESERVED_MASK == 0) {
                "reserved nibble must be zero"
            }
            val identifier = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
            val b2 = bytes[2].toInt() and 0xff
            val b3 = bytes[3].toInt() and 0xff
            return SskrShare(
                identifier = identifier,
                groupThreshold = (b2 ushr 4) + 1,
                groupCount = (b2 and 0x0f) + 1,
                groupIndex = b3 ushr 4,
                memberThreshold = (b3 and 0x0f) + 1,
                memberIndex = bytes[4].toInt() and 0x0f,
                value = bytes.copyOfRange(METADATA_LENGTH, bytes.size),
            )
        }
    }
}
