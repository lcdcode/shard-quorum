package com.lcdcode.shardquorum.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lcdcode.shardquorum.qr.QrDecodeException
import com.lcdcode.shardquorum.qr.QrDecoder
import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.Shamir
import com.lcdcode.shardquorum.sskr.ShareImport
import com.lcdcode.shardquorum.sskr.ShareReader
import com.lcdcode.shardquorum.sskr.Sskr
import com.lcdcode.shardquorum.sskr.SskrShare
import com.lcdcode.shardquorum.sskr.Ur
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CreateError {
    NAME_REQUIRED,
    SECRET_REQUIRED,
}

/** Stage of the create wizard. */
enum class CreatePhase { FORM, RECORD, VERIFY }

/** Outcome of the verify-before-distribute check. */
enum class VerifyState { COLLECTING, VERIFIED, MISMATCH }

enum class VerifyInputError { UNRECOGNIZED, DIFFERENT_SPLIT, IMAGE_DECODE_FAILED }

/** Everything one shard page renders. All strings, ready for QR/text display. */
data class ShardPage(
    val index: Int,
    val count: Int,
    val threshold: Int,
    val secretName: String,
    val shareUrForQr: String,
    val shareBytewords: String,
    val envelopeUrForQr: String?,
)

class CreateSecretViewModel(
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    var name by mutableStateOf("")
    var secretInput by mutableStateOf("")
    var threshold by mutableStateOf(DEFAULT_THRESHOLD)
        private set
    var shareCount by mutableStateOf(DEFAULT_SHARE_COUNT)
        private set
    var error by mutableStateOf<CreateError?>(null)
        private set
    var shards by mutableStateOf<List<ShardPage>?>(null)
        private set
    var phase by mutableStateOf(CreatePhase.FORM)
        private set
    var showCustomQuorum by mutableStateOf(false)
        private set

    // Verify-before-distribute state. Only a SHA-256 fingerprint of the secret
    // is kept (not the secret) plus the envelope ciphertext (KEK mode), so the
    // re-entered shards can be proven to rebuild the exact original.
    private var verifyFingerprint: ByteArray? = null
    private var verifyEnvelope: ByteArray? = null
    private var verifyIdentifier: Int? = null
    var verifyShares by mutableStateOf<List<ShareImport.Share>>(emptyList())
        private set
    var verifyState by mutableStateOf(VerifyState.COLLECTING)
        private set
    var verifyError by mutableStateOf<VerifyInputError?>(null)
        private set
    var isDecoding by mutableStateOf(false)
        private set

    val verifyCollectedIndices: List<Int>
        get() = verifyShares.map { it.metadata.memberIndex }

    fun setThresholdClamped(value: Int) {
        threshold = value.coerceIn(MIN_QUORUM, Shamir.MAX_SHARES)
        if (shareCount < threshold) shareCount = threshold
    }

    fun setShareCountClamped(value: Int) {
        shareCount = value.coerceIn(MIN_QUORUM, Shamir.MAX_SHARES)
        if (threshold > shareCount) threshold = shareCount
    }

    fun selectPreset(threshold: Int, shareCount: Int) {
        setThresholdClamped(threshold)
        setShareCountClamped(shareCount)
        showCustomQuorum = false
    }

    fun toggleCustomQuorum() {
        showCustomQuorum = !showCustomQuorum
    }

    /**
     * Validates the form and produces the shard pages. Secret/KEK material is
     * zeroed before returning; only the rendered share/envelope strings remain.
     * (The TextField input itself is a String and cannot be zeroed - a known,
     * accepted limitation of Compose text input.)
     */
    fun generate() {
        error = null
        if (name.isBlank()) {
            error = CreateError.NAME_REQUIRED
            return
        }
        if (secretInput.isEmpty()) {
            error = CreateError.SECRET_REQUIRED
            return
        }
        val secret = secretInput.toByteArray(Charsets.UTF_8)
        try {
            val protected = KekEnvelope.protect(threshold, shareCount, secret)
            armVerification(secret, protected.envelope, protected.shares.first())
            val envelopeUr = Ur.toEnvelopeUr(protected.envelope).uppercase()
            shards = toPages(protected.shares, envelopeUr)
            phase = CreatePhase.RECORD
        } finally {
            secret.fill(0)
        }
    }

    /** Drops the generated shards (e.g. when navigating back to the form). */
    fun discardShards() {
        shards = null
        phase = CreatePhase.FORM
    }

    /** Moves from recording shards to the verify step. */
    fun startVerify() {
        clearVerifyCollection()
        phase = CreatePhase.VERIFY
    }

    /** Returns from verify to the shard pages. */
    fun backToRecord() {
        clearVerifyCollection()
        phase = CreatePhase.RECORD
    }

    /** Clears the entire wizard for a fresh run. */
    fun reset() {
        name = ""
        secretInput = ""
        threshold = DEFAULT_THRESHOLD
        shareCount = DEFAULT_SHARE_COUNT
        showCustomQuorum = false
        error = null
        shards = null
        phase = CreatePhase.FORM
        clearVerifyCollection()
        verifyFingerprint = null
        verifyEnvelope = null
        verifyIdentifier = null
    }

    private fun clearVerifyCollection() {
        verifyShares = emptyList()
        verifyState = VerifyState.COLLECTING
        verifyError = null
    }

    /** Captures what verify needs (secret fingerprint + envelope + split id). */
    private fun armVerification(secret: ByteArray, envelope: ByteArray?, firstShare: ByteArray) {
        verifyFingerprint = sha256(secret)
        verifyEnvelope = envelope
        verifyIdentifier = SskrShare.deserialize(firstShare).identifier
    }

    /**
     * Files re-entered shards for verification (lenient, like recovery: scans
     * each line, skips human text/envelope/duplicates). When the threshold is
     * met it reconstructs and compares the fingerprint, setting [verifyState].
     */
    fun addVerifyText(text: String): Boolean {
        verifyError = null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = if (lines.size <= 1) listOf(text.trim()) else lines

        var added = 0
        var recognized = 0
        var pendingError: VerifyInputError? = null
        for (candidate in candidates) {
            val parsed = try {
                ShareReader.parse(candidate)
            } catch (e: IllegalArgumentException) {
                continue
            }
            recognized++
            if (parsed !is ShareImport.Share) continue // envelope: we hold the real one
            when {
                verifyIdentifier != null && parsed.metadata.identifier != verifyIdentifier ->
                    pendingError = VerifyInputError.DIFFERENT_SPLIT
                verifyShares.any { it.metadata.memberIndex == parsed.metadata.memberIndex } -> {} // dup
                else -> {
                    verifyShares = verifyShares + parsed
                    added++
                }
            }
        }
        if (recognized == 0) {
            verifyError = VerifyInputError.UNRECOGNIZED
            return false
        }
        if (added == 0 && pendingError != null) {
            verifyError = pendingError
            return false
        }
        if (verifyShares.size >= threshold) attemptVerify()
        return added > 0
    }

    /**
     * Decodes every QR in a picked image, then files them for verification.
     * Decoding a multi-megapixel photo can take seconds, so it runs on
     * [decodeDispatcher] with [isDecoding] set for the duration; results land
     * via [verifyShares]/[verifyError] when the launched work completes. No-op
     * while a decode is in flight.
     */
    fun addVerifyImage(imageBytes: ByteArray, decoder: QrDecoder) {
        if (isDecoding) return
        isDecoding = true
        verifyError = null
        viewModelScope.launch {
            try {
                val texts = withContext(decodeDispatcher) { decoder.decode(imageBytes) }
                addVerifyText(texts.joinToString("\n"))
            } catch (e: QrDecodeException) {
                verifyError = VerifyInputError.IMAGE_DECODE_FAILED
            } finally {
                isDecoding = false
            }
        }
    }

    private fun attemptVerify() {
        val fingerprint = verifyFingerprint ?: return
        val shareBytes = verifyShares.map { it.bytes }
        val reconstructed = try {
            verifyEnvelope?.let { KekEnvelope.recover(it, shareBytes) } ?: Sskr.combine(shareBytes)
        } catch (e: IllegalArgumentException) {
            verifyState = VerifyState.MISMATCH
            return
        }
        verifyState = try {
            if (sha256(reconstructed).contentEquals(fingerprint)) {
                VerifyState.VERIFIED
            } else {
                VerifyState.MISMATCH
            }
        } finally {
            reconstructed.fill(0)
        }
    }

    private fun toPages(shares: List<ByteArray>, envelopeUr: String?): List<ShardPage> =
        shares.mapIndexed { i, share ->
            ShardPage(
                index = i + 1,
                count = shares.size,
                threshold = threshold,
                secretName = name.trim(),
                shareUrForQr = Ur.toUr(share).uppercase(),
                shareBytewords = Ur.toStandardBytewords(share),
                envelopeUrForQr = envelopeUr,
            )
        }

    companion object {
        // Floor for both K and N. 1-of-N gives no protection (any single shard
        // rebuilds the secret); we require at least 3-of-N. Set to 2 to also
        // allow the common 2-of-3 scheme.
        const val MIN_QUORUM = 3
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_SHARE_COUNT = 5

        /** Cap on the secret name: it is printed on every shard, and bounds PNG width. */
        const val MAX_NAME_LENGTH = 24

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        /**
         * Plain-text rendering of one shard for sharing/saving. The first line
         * is the secret's name so multiple shards can be told apart. Words come
         * before the UR string to avoid confusing the two; the UR is lowercased
         * back to canonical form. In KEK mode the recovery envelope is appended.
         *
         * Note: the name identifies what this shard protects, so a found shard
         * reveals that - an accepted trade for manageability (see project notes).
         */
        fun shareText(page: ShardPage): String = buildString {
            appendLine(page.secretName)
            appendLine("ShardQuorum shard ${page.index} of ${page.count}")
            appendLine(
                "Any ${page.threshold} of ${page.count} of these shards together can " +
                    "rebuild the secret. Keep this shard private and apart from the others.",
            )
            appendLine()
            appendLine("Shard words (type these):")
            appendLine(page.shareBytewords)
            appendLine()
            appendLine("Shard QR (UR):")
            appendLine(page.shareUrForQr.lowercase())
            page.envelopeUrForQr?.let { envelope ->
                appendLine()
                appendLine("Recovery envelope (needed to rebuild; keep it with this shard):")
                appendLine(envelope.lowercase())
            }
        }

    }
}
