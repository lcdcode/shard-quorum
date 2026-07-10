package com.lcdcode.shardquorum.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class RecoveryKitTest {

    private fun zip(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { RecoveryKit.writeZip(entries, it) }.toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                out[entry.name] = zin.readBytes()
                entry = zin.nextEntry
            }
        }
        return out
    }

    @Test
    fun writeZip_roundTripsEveryEntryWithExactBytes() {
        val entries = mapOf(
            "README.txt" to "hello".toByteArray(),
            "shard-2-of-3.txt" to "tuna next jazz".toByteArray(),
            "recovery-vectors.json" to byteArrayOf(0, 1, 2, 3, 127, -1),
        )
        val read = unzip(zip(entries))
        assertEquals(entries.keys, read.keys)
        for ((name, data) in entries) assertArrayEquals(name, data, read[name])
    }

    @Test
    fun writeZip_isDeterministicRegardlessOfInsertionOrder() {
        val a = zip(linkedMapOf("b.txt" to "B".toByteArray(), "a.txt" to "A".toByteArray()))
        val b = zip(linkedMapOf("a.txt" to "A".toByteArray(), "b.txt" to "B".toByteArray()))
        assertArrayEquals(a, b)
    }

    @Test
    fun writeZip_entriesAreSortedByName() {
        val bytes = zip(linkedMapOf("z.txt" to ByteArray(0), "a.txt" to ByteArray(0)))
        val order = unzip(bytes).keys.toList()
        assertEquals(listOf("a.txt", "z.txt"), order)
    }

    @Test
    fun shardEntryNames_encodeIndexAndCount() {
        assertEquals("shard-2-of-3.png", RecoveryKit.shardPngName(2, 3))
        assertEquals("shard-2-of-3.txt", RecoveryKit.shardTextName(2, 3))
    }

    @Test
    fun bundledFiles_matchAssetsExpectedByTheKit() {
        assertTrue("ShardQuorum-recover.html" in RecoveryKit.BUNDLED_FILES)
        assertTrue("RECOVERY-SPEC.md" in RecoveryKit.BUNDLED_FILES)
        assertTrue("recovery-vectors.json" in RecoveryKit.BUNDLED_FILES)
        assertTrue("README.txt" in RecoveryKit.BUNDLED_FILES)
    }

    @Test
    fun personalizeReadme_substitutesNameIndexAndCount() {
        val out = RecoveryKit.personalizeReadme(
            "Title: '{secret_name}' - Shard {index} of {count}", "Family vault", 2, 5,
        )
        assertEquals("Title: 'Family vault' - Shard 2 of 5", out)
    }

    @Test
    fun personalizeReadme_blankNameGetsNeutralLabelNotRawPlaceholder() {
        val out = RecoveryKit.personalizeReadme(
            "Kit for '{secret_name}', shard {index} of {count}", "  ", 1, 3,
        )
        assertEquals("Kit for 'unnamed secret', shard 1 of 3", out)
    }

    @Test
    fun personalizeReadme_doesNotSubstituteIntoTheSecretName() {
        val out = RecoveryKit.personalizeReadme(
            "'{secret_name}' is shard {index}/{count}", "vault {index}", 4, 7,
        )
        assertEquals("'vault {index}' is shard 4/7", out)
    }
}
