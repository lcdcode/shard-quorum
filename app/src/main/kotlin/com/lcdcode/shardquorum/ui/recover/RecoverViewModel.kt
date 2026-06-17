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

    /** Parses and files a single scanned/typed token. Returns true if accepted. */
    fun addInput(text: String): Boolean {
        error = null
        val parsed = try {
            ShareReader.parse(text)
        } catch (e: IllegalArgumentException) {
            error = RecoverError.UNRECOGNIZED_INPUT
            return false
        }
        return when (file(parsed)) {
            FileOutcome.ADDED, FileOutcome.ENVELOPE_SET -> true
            FileOutcome.DUPLICATE -> {
                error = RecoverError.DUPLICATE_SHARD
                false
            }
            FileOutcome.DIFFERENT_SPLIT -> {
                error = RecoverError.DIFFERENT_SPLIT
                false
            }
        }
    }

    /**
     * Lenient import for pasted text or a saved words file: files every line
     * that parses (the share UR/words line and, in KEK mode, the envelope line),
     * silently skipping human-readable text and duplicates. Returns true if at
     * least one new item was added.
     */
    fun addBundle(text: String): Boolean {
        error = null
        // Single-line input (typed words, one UR) is parsed whole; multi-line
        // blobs are scanned line by line.
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = if (lines.size <= 1) listOf(text.trim()) else lines

        var added = 0
        var recognized = 0
        var pendingError: RecoverError? = null
        for (candidate in candidates) {
            val parsed = try {
                ShareReader.parse(candidate)
            } catch (e: IllegalArgumentException) {
                continue
            }
            recognized++
            when (file(parsed)) {
                FileOutcome.ADDED, FileOutcome.ENVELOPE_SET -> added++
                FileOutcome.DUPLICATE -> {}
                FileOutcome.DIFFERENT_SPLIT -> pendingError = RecoverError.DIFFERENT_SPLIT
            }
        }
        if (recognized == 0) {
            error = RecoverError.UNRECOGNIZED_INPUT
            return false
        }
        if (added == 0 && pendingError != null) {
            error = pendingError
            return false
        }
        return added > 0
    }

    /** Decodes every QR in a picked image, then files them all. */
    fun addFromImage(imageBytes: ByteArray, decoder: QrDecoder): Boolean {
        val texts = try {
            decoder.decode(imageBytes)
        } catch (e: QrDecodeException) {
            error = RecoverError.IMAGE_DECODE_FAILED
            return false
        }
        return addBundle(texts.joinToString("\n"))
    }

    private enum class FileOutcome { ADDED, ENVELOPE_SET, DUPLICATE, DIFFERENT_SPLIT }

    /** Files a parsed input into [shares]/[envelope] without touching [error]. */
    private fun file(parsed: ShareImport): FileOutcome = when (parsed) {
        is ShareImport.Envelope -> {
            envelope = parsed.bytes
            FileOutcome.ENVELOPE_SET
        }
        is ShareImport.Share -> {
            val existing = shares
            when {
                existing.isNotEmpty() &&
                    existing.first().metadata.identifier != parsed.metadata.identifier ->
                    FileOutcome.DIFFERENT_SPLIT
                existing.any { it.metadata.memberIndex == parsed.metadata.memberIndex } ->
                    FileOutcome.DUPLICATE
                else -> {
                    shares = existing + parsed
                    FileOutcome.ADDED
                }
            }
        }
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
        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}
