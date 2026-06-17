package com.lcdcode.shardquorum.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.Shamir
import com.lcdcode.shardquorum.sskr.Sskr
import com.lcdcode.shardquorum.sskr.Ur

/**
 * How the secret is protected.
 *
 * [KEK]: a random 256-bit key is sharded and the secret (any non-empty UTF-8
 * text) is stored AES-GCM-encrypted in an envelope that must accompany the
 * shards. Supports later rotation without redistributing shards.
 *
 * [DIRECT]: the secret bytes themselves (hex, 16..32 bytes, even length) are
 * sharded as bare standard SSKR - recoverable by any SSKR-compatible tool, no
 * envelope involved.
 */
enum class SecretMode { KEK, DIRECT }

enum class CreateError {
    NAME_REQUIRED,
    SECRET_REQUIRED,
    HEX_INVALID,
    HEX_LENGTH,
}

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

class CreateSecretViewModel : ViewModel() {
    var name by mutableStateOf("")
    var secretInput by mutableStateOf("")
    var mode by mutableStateOf(SecretMode.KEK)
    var threshold by mutableStateOf(DEFAULT_THRESHOLD)
        private set
    var shareCount by mutableStateOf(DEFAULT_SHARE_COUNT)
        private set
    var error by mutableStateOf<CreateError?>(null)
        private set
    var shards by mutableStateOf<List<ShardPage>?>(null)
        private set

    fun setThresholdClamped(value: Int) {
        threshold = value.coerceIn(MIN_QUORUM, Shamir.MAX_SHARES)
        if (shareCount < threshold) shareCount = threshold
    }

    fun setShareCountClamped(value: Int) {
        shareCount = value.coerceIn(MIN_QUORUM, Shamir.MAX_SHARES)
        if (threshold > shareCount) threshold = shareCount
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
        when (mode) {
            SecretMode.KEK -> generateKekMode()
            SecretMode.DIRECT -> generateDirectMode()
        }
    }

    /** Drops the generated shards (e.g. when navigating back to the form). */
    fun discardShards() {
        shards = null
    }

    /** Clears the entire wizard for a fresh run. */
    fun reset() {
        name = ""
        secretInput = ""
        mode = SecretMode.KEK
        threshold = DEFAULT_THRESHOLD
        shareCount = DEFAULT_SHARE_COUNT
        error = null
        shards = null
    }

    private fun generateKekMode() {
        if (secretInput.isEmpty()) {
            error = CreateError.SECRET_REQUIRED
            return
        }
        val secret = secretInput.toByteArray(Charsets.UTF_8)
        try {
            val protected = KekEnvelope.protect(threshold, shareCount, secret)
            val envelopeUr = Ur.toEnvelopeUr(protected.envelope).uppercase()
            shards = toPages(protected.shares, envelopeUr)
        } finally {
            secret.fill(0)
        }
    }

    private fun generateDirectMode() {
        val secret = parseHex(secretInput)
        if (secret == null) {
            error = if (secretInput.isBlank()) CreateError.SECRET_REQUIRED else CreateError.HEX_INVALID
            return
        }
        if (secret.size !in Shamir.MIN_SECRET_LENGTH..Shamir.MAX_SECRET_LENGTH || secret.size % 2 != 0) {
            secret.fill(0)
            error = CreateError.HEX_LENGTH
            return
        }
        try {
            shards = toPages(Sskr.generate(threshold, shareCount, secret), envelopeUr = null)
        } finally {
            secret.fill(0)
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

        /** Strict hex parse: whitespace tolerated, returns null on any other defect. */
        fun parseHex(text: String): ByteArray? {
            val clean = text.filterNot(Char::isWhitespace)
            if (clean.isEmpty() || clean.length % 2 != 0) return null
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
