package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BytewordsSpellcheckTest {

    @Test
    fun recognizesValidWordsCaseInsensitively() {
        assertTrue(Bytewords.isWord("able"))
        assertTrue(Bytewords.isWord("ABLE"))
        assertTrue(Bytewords.isWord("  zoom  "))
        assertFalse(Bytewords.isWord("xyzq"))
        assertFalse(Bytewords.isWord(""))
    }

    @Test
    fun wordListIsTheFull256() {
        assertEquals(256, Bytewords.wordList().size)
        assertEquals("able", Bytewords.wordList().first())
    }

    @Test
    fun exactMatchSuggestsOnlyItself() {
        assertEquals(listOf("axis"), Bytewords.suggestions("axis"))
    }

    @Test
    fun correctsAMiddleLetterTypo() {
        // "axes" -> "axis": same first/last letter, edit distance 1.
        assertEquals("axis", Bytewords.suggestions("axes").first())
    }

    @Test
    fun suggestionsPreferSharedFirstAndLastLetter() {
        // A typo keeping g..d should surface "good" / "gold"-like g..d words first.
        val s = Bytewords.suggestions("goad")
        assertTrue("expected a g..d word near the top, got $s", s.first().let {
            it.first() == 'g' && it.last() == 'd'
        })
    }

    @Test
    fun respectsLimit() {
        assertEquals(2, Bytewords.suggestions("zzzz", limit = 2).size)
        assertTrue(Bytewords.suggestions("zzzz", limit = 5).size <= 5)
    }
}
