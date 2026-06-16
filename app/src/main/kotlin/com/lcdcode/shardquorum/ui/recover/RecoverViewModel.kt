package com.lcdcode.shardquorum.ui.recover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.lcdcode.shardquorum.qr.QrDecodeException
import com.lcdcode.shardquorum.qr.QrDecoder
import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.ShareImport
import com.lcdcode.shardquorum.sskr.ShareReader
import com.lcdcode.shardquorum.sskr.Sskr

enum class RecoverError {
    UNRECOGNIZED_INPUT,
    DIFFERENT_SPLIT,
    DUPLICATE_SHARD,
    NOT_ENOUGH_SHARDS,
    RECOVERY_FAILED,
    IMAGE_DECODE_FAILED,
}

/** The reconstructed secret, ready to display. */
data class RecoveredSecret(val display: String, val isHex: Boolean)

/** One shard already collected, summarized for the UI list. */
data class CollectedShard(val memberIndex: Int)

/** A single token from word-entry input, with spellcheck verdict. */
data class TokenCheck(val token: String, val recognized: Boolean, val suggestions: List<String>)

/**
 * Collects shares from any mix of input methods (scanned QR, uploaded image,
 * hand-typed Bytewords) plus an optional KEK recovery envelope, then
 * reconstructs the secret.
 *
 * Mode is inferred at recovery time: if an envelope was supplied the combined
 * shares are treated as the KEK and used to decrypt it; otherwise the combined
 * value is presented directly (direct-shard mode) as hex.
 */
class RecoverViewModel : ViewModel() {
    var shares by mutableStateOf<List<ShareImport.Share>>(emptyList())
        private set
    var envelope by mutableStateOf<ByteArray?>(null)
        private set
    var error by mutableStateOf<RecoverError?>(null)
        private set
    var result by mutableStateOf<RecoveredSecret?>(null)
        private set

    /** Member threshold (K) declared by the collected shares, once any exist. */
    val threshold: Int?
        get() = shares.firstOrNull()?.metadata?.memberThreshold

    val collected: List<CollectedShard>
        get() = shares.map { CollectedShard(it.metadata.memberIndex) }

    val hasEnvelope: Boolean
        get() = envelope != null

    val canRecover: Boolean
        get() = threshold?.let { shares.size >= it } ?: false

    /** Parses and files a scanned/typed input. Returns true if accepted. */
    fun addInput(text: String): Boolean {
        error = null
        val parsed = try {
            ShareReader.parse(text)
        } catch (e: IllegalArgumentException) {
            error = RecoverError.UNRECOGNIZED_INPUT
            return false
        }
        return when (parsed) {
            is ShareImport.Envelope -> {
                envelope = parsed.bytes
                true
            }
            is ShareImport.Share -> addShare(parsed)
        }
    }

    /** Decodes a picked image to its QR text, then files it like any input. */
    fun addFromImage(imageBytes: ByteArray, decoder: QrDecoder): Boolean {
        val text = try {
            decoder.decode(imageBytes)
        } catch (e: QrDecodeException) {
            error = RecoverError.IMAGE_DECODE_FAILED
            return false
        }
        return addInput(text)
    }

    private fun addShare(share: ShareImport.Share): Boolean {
        val existing = shares
        if (existing.isNotEmpty() &&
            existing.first().metadata.identifier != share.metadata.identifier
        ) {
            error = RecoverError.DIFFERENT_SPLIT
            return false
        }
        if (existing.any { it.metadata.memberIndex == share.metadata.memberIndex }) {
            error = RecoverError.DUPLICATE_SHARD
            return false
        }
        shares = existing + share
        return true
    }

    fun removeShardAt(memberIndex: Int) {
        shares = shares.filterNot { it.metadata.memberIndex == memberIndex }
    }

    fun clearEnvelope() {
        envelope = null
    }

    /** Attempts reconstruction; on success populates [result]. */
    fun recover() {
        error = null
        val needed = threshold
        if (needed == null || shares.size < needed) {
            error = RecoverError.NOT_ENOUGH_SHARDS
            return
        }
        val shareBytes = shares.map { it.bytes }
        val currentEnvelope = envelope
        try {
            result = if (currentEnvelope != null) {
                val secret = KekEnvelope.recover(currentEnvelope, shareBytes)
                RecoveredSecret(secret.toString(Charsets.UTF_8), isHex = false)
            } else {
                RecoveredSecret(Sskr.combine(shareBytes).toHex(), isHex = true)
            }
        } catch (e: IllegalArgumentException) {
            error = RecoverError.RECOVERY_FAILED
        }
    }

    fun reset() {
        shares = emptyList()
        envelope = null
        error = null
        result = null
    }

    companion object {
        /** Splits [input] on whitespace and spellchecks each word. */
        fun spellcheck(input: String): List<TokenCheck> =
            input.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { token ->
                val recognized = com.lcdcode.shardquorum.sskr.Bytewords.isWord(token)
                TokenCheck(
                    token = token,
                    recognized = recognized,
                    suggestions = if (recognized) {
                        emptyList()
                    } else {
                        com.lcdcode.shardquorum.sskr.Bytewords.suggestions(token)
                    },
                )
            }

        private val WHITESPACE = Regex("\\s+")

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}
