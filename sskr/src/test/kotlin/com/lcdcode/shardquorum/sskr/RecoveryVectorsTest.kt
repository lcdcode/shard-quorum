package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Emits canonical recovery vectors as a build artifact and, in the same pass,
 * proves they are correct end to end.
 *
 * Purpose: the recovery specification (docs/RECOVERY-SPEC.md) needs a fixed,
 * self-verifying set of "these inputs recover to this secret" vectors, with the
 * intermediate values exposed so a reimplementation can localize a bug to a
 * single stage (bytewords decode, UR/CBOR unwrap, Shamir combine, envelope
 * open). Rather than maintain those by hand, this test generates them from the
 * real implementation. The canonical copy lives at docs/recovery-vectors.json;
 * this test regenerates the content and fails if the committed copy has drifted,
 * so the vectors can never silently fall out of step with the code. Regenerate
 * after an intended format change with:
 *
 *   ./gradlew :sskr:test -PupdateVectors --tests "*.RecoveryVectorsTest"
 *
 * A future port (the TypeScript recover.html, or any LLM-built tool) is correct
 * if and only if it reproduces every value in that file.
 *
 * Determinism: all randomness comes from [DeterministicRandom], seeded per
 * vector by name, so the artifact is byte-stable across runs and machines and
 * does not churn. The RNG is for reproducibility only; these are throwaway test
 * secrets, not real key material.
 *
 * Correctness: every vector is recovered here from its transcribable forms
 * (ur:sskr / standard bytewords, and ur:sq-env for the envelope) and asserted to
 * round-trip to the original secret before it is written out. A failing build
 * therefore means the vectors are wrong, not merely that they changed.
 */
class RecoveryVectorsTest {

    @Test
    fun recoveryVectorsMatchCommittedCopy() {
        val vectors = listOf(
            directVector("direct-2of3-16byte", threshold = 2, shareCount = 3,
                secret = fromHex("00112233445566778899aabbccddeeff")),
            directVector("direct-3of5-32byte", threshold = 3, shareCount = 5,
                secret = fromHex(
                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")),
            kekVector("kek-2of3-text", threshold = 2, shareCount = 3,
                secret = "vault master key".toByteArray(Charsets.UTF_8)),
            kekVector("kek-3of5-text", threshold = 3, shareCount = 5,
                secret = "correct horse battery staple".toByteArray(Charsets.UTF_8)),
        )

        val root = linkedMapOf<String, Any?>(
            "specVersion" to 1,
            "sqkeEnvelopeVersion" to 1,
            "note" to "Deterministic, reproducible recovery vectors for ShardQuorum. " +
                "Any 'threshold' of a vector's shares reconstruct it. Vectors with " +
                "mode 'kek' also require the envelope. An implementation is correct " +
                "iff it reproduces every value here.",
            "vectors" to vectors,
        )

        val generated = toJson(root, 0) + "\n"
        val file = File(
            System.getProperty("recoveryVectorsFile") ?: "../docs/recovery-vectors.json",
        )

        if (System.getProperty("updateVectors") == "true") {
            file.parentFile.mkdirs()
            file.writeText(generated)
            println("Updated recovery vectors at ${file.absolutePath}")
            return
        }

        assertTrue(
            "committed recovery vectors missing at ${file.absolutePath}; " +
                "regenerate with: ./gradlew :sskr:test -PupdateVectors",
            file.isFile,
        )
        assertEquals(
            "committed recovery vectors (${file.absolutePath}) have drifted from the " +
                "implementation; if this change is intended, regenerate with: " +
                "./gradlew :sskr:test -PupdateVectors",
            generated,
            file.readText(),
        )
    }

    // --- vector builders (each asserts recovery before returning its JSON) ---

    private fun directVector(
        name: String,
        threshold: Int,
        shareCount: Int,
        secret: ByteArray,
    ): Map<String, Any?> {
        val random = DeterministicRandom(name)
        val shares = Sskr.generate(threshold, shareCount, secret, random)

        verifyTranscribableForms(shares)
        val quorum = shares.take(threshold)
        assertArrayEquals("direct recovery must yield the secret",
            secret, Sskr.combine(quorum))

        return linkedMapOf(
            "name" to name,
            "mode" to "direct",
            "threshold" to threshold,
            "shareCount" to shareCount,
            "secretHex" to toHex(secret),
            "shares" to shares.map(::shareJson),
        )
    }

    private fun kekVector(
        name: String,
        threshold: Int,
        shareCount: Int,
        secret: ByteArray,
    ): Map<String, Any?> {
        val random = DeterministicRandom(name)
        val protected = KekEnvelope.protect(threshold, shareCount, secret, random)
        val shares = protected.shares

        verifyTranscribableForms(shares)
        val quorum = shares.take(threshold)
        val kek = Sskr.combine(quorum)
        assertArrayEquals("kek-mode recovery must yield the secret",
            secret, KekEnvelope.open(kek, protected.envelope))
        assertArrayEquals("envelope ur must round-trip",
            protected.envelope, Ur.fromEnvelopeUr(Ur.toEnvelopeUr(protected.envelope)))

        return linkedMapOf(
            "name" to name,
            "mode" to "kek",
            "threshold" to threshold,
            "shareCount" to shareCount,
            "secretHex" to toHex(secret),
            "secretUtf8" to secret.toString(Charsets.UTF_8),
            "combinedKekHex" to toHex(kek),
            "envelope" to linkedMapOf<String, Any?>(
                "rawHex" to toHex(protected.envelope),
                "ur" to Ur.toEnvelopeUr(protected.envelope),
            ),
            "shares" to shares.map(::shareJson),
        )
    }

    /** Every share must survive both transcribable round-trips byte-for-byte. */
    private fun verifyTranscribableForms(shares: List<ByteArray>) {
        for (serialized in shares) {
            assertArrayEquals("ur:sskr round-trip",
                serialized, Ur.fromUr(Ur.toUr(serialized)))
            assertArrayEquals("standard bytewords round-trip",
                serialized, Ur.fromStandardBytewords(Ur.toStandardBytewords(serialized)))
        }
    }

    private fun shareJson(serialized: ByteArray): Map<String, Any?> {
        val share = SskrShare.deserialize(serialized)
        return linkedMapOf(
            "memberIndex" to share.memberIndex,
            "serializedHex" to toHex(serialized),
            "header" to linkedMapOf(
                "identifier" to share.identifier,
                "groupThreshold" to share.groupThreshold,
                "groupCount" to share.groupCount,
                "groupIndex" to share.groupIndex,
                "memberThreshold" to share.memberThreshold,
                "memberIndex" to share.memberIndex,
            ),
            "ur" to Ur.toUr(serialized),
            "standardBytewords" to Ur.toStandardBytewords(serialized),
        )
    }

    // --- helpers ---

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fromHex(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }

    /** Minimal pretty JSON writer: objects, arrays, strings, numbers, booleans, null. */
    private fun toJson(value: Any?, indent: Int): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Int, is Long, is Boolean -> value.toString()
        is Map<*, *> -> {
            if (value.isEmpty()) "{}" else {
                val pad = "  ".repeat(indent + 1)
                val close = "  ".repeat(indent)
                value.entries.joinToString(",\n", "{\n", "\n$close}") { (k, v) ->
                    "$pad${quote(k.toString())}: ${toJson(v, indent + 1)}"
                }
            }
        }
        is List<*> -> {
            if (value.isEmpty()) "[]" else {
                val pad = "  ".repeat(indent + 1)
                val close = "  ".repeat(indent)
                value.joinToString(",\n", "[\n", "\n$close]") { "$pad${toJson(it, indent + 1)}" }
            }
        }
        else -> quote(value.toString())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    /**
     * Reproducible [SecureRandom]: a SHA-256 counter-mode keystream over a fixed
     * seed label. Overriding [nextBytes] is sufficient, since SecureRandom routes
     * next()/nextInt() through it. Not cryptographically meaningful; used only to
     * make the emitted vectors byte-stable.
     */
    private class DeterministicRandom(seedLabel: String) : SecureRandom() {
        private val seed = seedLabel.toByteArray(Charsets.UTF_8)
        private var counter = 0L
        private var buffer = ByteArray(0)
        private var pos = 0

        @Synchronized
        override fun nextBytes(bytes: ByteArray) {
            var i = 0
            while (i < bytes.size) {
                if (pos >= buffer.size) {
                    buffer = block(counter++)
                    pos = 0
                }
                bytes[i++] = buffer[pos++]
            }
        }

        private fun block(c: Long): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(seed)
            for (shift in 0 until 8) md.update((c ushr (shift * 8)).toByte())
            return md.digest()
        }
    }
}
