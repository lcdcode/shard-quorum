package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the recovery specification against silent drift from the implementation.
 *
 * docs/RECOVERY-SPEC.md must be self-contained, so Section 6.1 inlines the full
 * 256-word ByteWords list. That necessarily duplicates [Bytewords.wordList], and
 * an archival spec whose wordlist disagrees with the code is worse than no spec.
 * This test extracts the inlined list and fails if it differs, so a wordlist edit
 * cannot land on either side without the matching edit on the other.
 */
class SpecConsistencyTest {

    @Test
    fun specWordlistMatchesImplementation() {
        val spec = File(System.getProperty("recoverySpecFile") ?: "../docs/RECOVERY-SPEC.md")
        assertTrue("recovery spec not found at ${spec.absolutePath}", spec.isFile)

        val text = spec.readText()
        val start = text.indexOf("able acid also apex")
        val endMarker = "zone zoom"
        val end = text.indexOf(endMarker, start)
        assertTrue("ByteWords block not found in ${spec.absolutePath}", start >= 0 && end >= 0)

        val block = text.substring(start, end + endMarker.length)
        val words = block.split(Regex("\\s+")).filter { it.isNotEmpty() }

        assertEquals("spec Section 6.1 must inline exactly 256 ByteWords", 256, words.size)
        assertEquals(
            "docs/RECOVERY-SPEC.md ByteWords list has drifted from Bytewords.wordList()",
            Bytewords.wordList(),
            words,
        )
    }
}
