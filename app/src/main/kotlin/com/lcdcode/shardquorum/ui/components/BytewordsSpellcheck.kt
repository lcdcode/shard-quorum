package com.lcdcode.shardquorum.ui.components

import com.lcdcode.shardquorum.sskr.Bytewords

/** One whitespace-separated token from word entry, with its spellcheck verdict. */
data class TokenCheck(val token: String, val recognized: Boolean, val suggestions: List<String>)

private val WHITESPACE = Regex("\\s+")

/** Splits [input] on whitespace and checks each token against the Bytewords list. */
fun spellcheckBytewords(input: String): List<TokenCheck> =
    input.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { token ->
        val recognized = Bytewords.isWord(token)
        TokenCheck(
            token = token,
            recognized = recognized,
            suggestions = if (recognized) emptyList() else Bytewords.suggestions(token),
        )
    }
