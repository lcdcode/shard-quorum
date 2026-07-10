package com.lcdcode.shardquorum.ui.recover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lcdcode.shardquorum.qr.QrDecodeException
import com.lcdcode.shardquorum.qr.QrDecoder
import com.lcdcode.shardquorum.sskr.EnvelopeException
import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.ShareImport
import com.lcdcode.shardquorum.sskr.ShareReader
import com.lcdcode.shardquorum.sskr.Sskr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecoverError {
    UNRECOGNIZED_INPUT,
    DIFFERENT_SPLIT,
    DUPLICATE_SHARD,
    NOT_ENOUGH_SHARDS,
    RECOVERY_FAILED,
    ENVELOPE_INVALID,
    IMAGE_DECODE_FAILED,
}

/**
 * The reconstructed secret, ready to display. [maybeEncrypted] is set when the
 * shards combined to exactly a KEK-sized value and no envelope was supplied -
 * i.e. this could be the raw KEK of an encrypted secret rather than the secret
 * itself, so the UI must warn rather than present it as final.
 */
data class RecoveredSecret(val display: String, val isHex: Boolean, val maybeEncrypted: Boolean = false)

/** One shard already collected, summarized for the UI list. */
data class CollectedShard(val memberIndex: Int)

/**
 * Collects shares from any mix of input methods (scanned QR, uploaded image,
 * hand-typed Bytewords) plus an optional KEK recovery envelope, then
 * reconstructs the secret.
 *
 * The path is inferred at recovery time: if an envelope was supplied the
 * combined shares are treated as the KEK and used to decrypt it; otherwise
 * (plain SSKR shares with no envelope) the combined value is presented as hex.
 */
class RecoverViewModel(
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    var shares by mutableStateOf<List<ShareImport.Share>>(emptyList())
        private set
    var envelope by mutableStateOf<ByteArray?>(null)
        private set
    var error by mutableStateOf<RecoverError?>(null)
        private set
    var result by mutableStateOf<RecoveredSecret?>(null)
        private set
    var isDecoding by mutableStateOf(false)
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
     * that parses (the share UR/words line and, if present, the envelope line),
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

    /**
     * Decodes every QR in a picked image, then files them all. Decoding a
     * multi-megapixel photo can take seconds, so it runs on [decodeDispatcher]
     * with [isDecoding] set for the duration; results land via [shares]/[error]
     * when the launched work completes. No-op while a decode is in flight.
     */
    fun addFromImage(imageBytes: ByteArray, decoder: QrDecoder) {
        if (isDecoding) return
        isDecoding = true
        error = null
        viewModelScope.launch {
            try {
                val texts = withContext(decodeDispatcher) { decoder.decode(imageBytes) }
                addBundle(texts.joinToString("\n"))
            } catch (e: QrDecodeException) {
                error = RecoverError.IMAGE_DECODE_FAILED
            } finally {
                isDecoding = false
            }
        }
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
            // The recovered ByteArray is the most sensitive material in the app;
            // zero it once the display String is built (mirrors the create flow).
            // The String itself cannot be zeroed - an accepted Compose limitation.
            result = if (currentEnvelope != null) {
                val secret = KekEnvelope.recover(currentEnvelope, shareBytes)
                try {
                    RecoveredSecret(secret.toString(Charsets.UTF_8), isHex = false)
                } finally {
                    secret.fill(0)
                }
            } else {
                val combined = Sskr.combine(shareBytes)
                try {
                    // A KEK is always exactly KEK_LENGTH bytes; a shorter combined
                    // value can only be a directly split plain-SSKR secret. A
                    // KEK-length value with no envelope is ambiguous - flag it so
                    // the UI warns.
                    RecoveredSecret(
                        display = combined.toHex(),
                        isHex = true,
                        maybeEncrypted = combined.size == KekEnvelope.KEK_LENGTH,
                    )
                } finally {
                    combined.fill(0)
                }
            }
        } catch (e: EnvelopeException) {
            // Shares combined into a KEK, but the envelope is wrong or damaged -
            // a distinct, more actionable failure than shares not combining.
            error = RecoverError.ENVELOPE_INVALID
        } catch (e: IllegalArgumentException) {
            error = RecoverError.RECOVERY_FAILED
        }
    }

    /** Returns from the recovered-secret view to the collection screen. */
    fun dismissResult() {
        result = null
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
