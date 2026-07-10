package com.lcdcode.shardquorum.ui.recover

import com.lcdcode.shardquorum.MainDispatcherRule
import com.lcdcode.shardquorum.qr.QrDecodeException
import com.lcdcode.shardquorum.qr.QrDecoder
import com.lcdcode.shardquorum.qr.UnavailableQrDecoder
import com.lcdcode.shardquorum.ui.components.spellcheckBytewords
import com.lcdcode.shardquorum.ui.create.CreateSecretViewModel
import com.lcdcode.shardquorum.ui.create.ShardPage
import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.Sskr
import com.lcdcode.shardquorum.sskr.Ur
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val random = SecureRandom()

    /** ViewModel whose image decodes run on the test scheduler. */
    private fun viewModel() = RecoverViewModel(decodeDispatcher = mainDispatcherRule.dispatcher)

    // --- KEK-mode recovery (shares + envelope) ---

    @Test
    fun recoversKekSecretFromShareAndEnvelopeUrs() {
        val secret = "correct horse battery staple"
        val protected = KekEnvelope.protect(3, 5, secret.toByteArray(), random)
        val vm = RecoverViewModel()

        // Add a threshold of shares via their QR strings, in scrambled order.
        listOf(4, 1, 2).forEach { vm.addInput(Ur.toUr(protected.shares[it])) }
        assertEquals(3, vm.threshold)
        assertFalse(vm.canRecover.not()) // 3 of 3 collected
        vm.addInput(Ur.toEnvelopeUr(protected.envelope))
        assertTrue(vm.hasEnvelope)

        vm.recover()
        assertNull(vm.error)
        assertEquals(RecoveredSecret(secret, isHex = false), vm.result)
    }

    @Test
    fun mixedInputMethodsCombine() {
        // One share typed as bytewords, one scanned as UR - same split.
        val protected = KekEnvelope.protect(2, 3, "s3cret".toByteArray(), random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toStandardBytewords(protected.shares[0]))
        vm.addInput(Ur.toUr(protected.shares[2]))
        vm.addInput(Ur.toEnvelopeUr(protected.envelope))
        vm.recover()
        assertEquals("s3cret", vm.result?.display)
    }

    // --- Direct-mode recovery (shares only) ---

    @Test
    fun recoversDirectModeAsHex() {
        val secretHex = "ff00112233445566778899aabbccddee"
        val secret = ByteArray(16) { secretHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val shares = Sskr.generate(2, 3, secret, random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(shares[0]))
        vm.addInput(Ur.toUr(shares[1]))
        assertTrue(vm.canRecover)
        vm.recover()
        assertNull(vm.error)
        assertEquals(RecoveredSecret(secretHex, isHex = true), vm.result)
    }

    // --- envelope-vs-plain-SSKR detection at recovery ---

    @Test
    fun kekSharesWithoutEnvelopeFlagMaybeEncrypted() {
        // Recovering a KEK secret but forgetting the envelope yields
        // the 32-byte KEK, which must be flagged rather than shown as the secret.
        val protected = KekEnvelope.protect(2, 3, "real secret".toByteArray(), random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(protected.shares[0]))
        vm.addInput(Ur.toUr(protected.shares[1]))
        vm.recover()
        assertEquals(true, vm.result?.maybeEncrypted)
        assertEquals(true, vm.result?.isHex)
    }

    @Test
    fun kekSharesWithEnvelopeAreNotFlagged() {
        val protected = KekEnvelope.protect(2, 3, "real secret".toByteArray(), random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(protected.shares[0]))
        vm.addInput(Ur.toUr(protected.shares[1]))
        vm.addInput(Ur.toEnvelopeUr(protected.envelope))
        vm.recover()
        assertEquals("real secret", vm.result?.display)
        assertEquals(false, vm.result?.maybeEncrypted)
    }

    @Test
    fun damagedEnvelopeReportsEnvelopeError() {
        // Correct shares but a corrupted envelope: distinguished from a
        // shards-do-not-combine failure so the user knows where to look.
        val protected = KekEnvelope.protect(2, 3, "vault key".toByteArray(), random)
        val tampered = protected.envelope.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(protected.shares[0]))
        vm.addInput(Ur.toUr(protected.shares[1]))
        vm.addInput(Ur.toEnvelopeUr(tampered))
        vm.recover()
        assertEquals(RecoverError.ENVELOPE_INVALID, vm.error)
        assertNull(vm.result)
    }

    @Test
    fun corruptedShareReportsRecoveryFailed() {
        // A share whose value is damaged (header intact, so it is still
        // collected) fails the digest at combine, before the envelope is opened.
        val protected = KekEnvelope.protect(2, 3, "vault key".toByteArray(), random)
        val corrupt = protected.shares[0].copyOf().also { it[6] = (it[6] + 1).toByte() }
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(corrupt))
        vm.addInput(Ur.toUr(protected.shares[1]))
        vm.addInput(Ur.toEnvelopeUr(protected.envelope))
        vm.recover()
        assertEquals(RecoverError.RECOVERY_FAILED, vm.error)
        assertNull(vm.result)
    }

    @Test
    fun shortDirectSecretIsNotFlagged() {
        // A 16-byte direct secret cannot be a KEK, so no warning.
        val shares = Sskr.generate(2, 3, ByteArray(16) { it.toByte() }, random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(shares[0]))
        vm.addInput(Ur.toUr(shares[1]))
        vm.recover()
        assertEquals(false, vm.result?.maybeEncrypted)
        assertEquals(true, vm.result?.isHex)
    }

    @Test
    fun dismissResultReturnsToCollectionKeepingShards() {
        val protected = KekEnvelope.protect(2, 3, "x".toByteArray(), random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(protected.shares[0]))
        vm.addInput(Ur.toUr(protected.shares[1]))
        vm.recover()
        assertNotNull(vm.result)
        vm.dismissResult()
        assertNull(vm.result)
        assertEquals(2, vm.shares.size) // shards kept so the envelope can be added
        // Adding the envelope and recovering again yields the real secret.
        vm.addInput(Ur.toEnvelopeUr(protected.envelope))
        vm.recover()
        assertEquals("x", vm.result?.display)
    }

    // --- Collection rules ---

    @Test
    fun rejectsSharesFromDifferentSplits() {
        val a = Sskr.generate(2, 3, ByteArray(16) { 1 }, random)
        val b = Sskr.generate(2, 3, ByteArray(16) { 2 }, random)
        val vm = RecoverViewModel()
        assertTrue(vm.addInput(Ur.toUr(a[0])))
        assertFalse(vm.addInput(Ur.toUr(b[0])))
        assertEquals(RecoverError.DIFFERENT_SPLIT, vm.error)
        assertEquals(1, vm.shares.size)
    }

    @Test
    fun ignoresDuplicateShard() {
        val shares = Sskr.generate(2, 3, ByteArray(16) { 1 }, random)
        val vm = RecoverViewModel()
        assertTrue(vm.addInput(Ur.toUr(shares[0])))
        assertFalse(vm.addInput(Ur.toUr(shares[0])))
        assertEquals(RecoverError.DUPLICATE_SHARD, vm.error)
        assertEquals(1, vm.shares.size)
    }

    @Test
    fun rejectsUnrecognizedInput() {
        val vm = RecoverViewModel()
        assertFalse(vm.addInput("this is not a shard"))
        assertEquals(RecoverError.UNRECOGNIZED_INPUT, vm.error)
    }

    @Test
    fun recoverBelowThresholdFails() {
        val shares = Sskr.generate(3, 5, ByteArray(16) { 1 }, random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(shares[0]))
        vm.addInput(Ur.toUr(shares[1]))
        assertFalse(vm.canRecover)
        vm.recover()
        assertEquals(RecoverError.NOT_ENOUGH_SHARDS, vm.error)
        assertNull(vm.result)
    }

    @Test
    fun removeShardAndClearEnvelopeWork() {
        val protected = KekEnvelope.protect(2, 3, "x".toByteArray(), random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(protected.shares[0]))
        vm.addInput(Ur.toUr(protected.shares[1]))
        vm.addInput(Ur.toEnvelopeUr(protected.envelope))
        vm.removeShardAt(protected.shares[0].let {
            com.lcdcode.shardquorum.sskr.SskrShare.deserialize(it).memberIndex
        })
        assertEquals(1, vm.shares.size)
        vm.clearEnvelope()
        assertFalse(vm.hasEnvelope)
    }

    // --- Image path (stubbed decoder; decode runs async off the main thread) ---

    @Test
    fun imageDecodeReportsFailureWhenDecoderThrows() = runTest {
        // A decoder that finds no QR (here the always-throwing stub) surfaces a
        // decode failure, not a crash.
        val vm = viewModel()
        vm.addFromImage(ByteArray(8), UnavailableQrDecoder())
        advanceUntilIdle()
        assertEquals(RecoverError.IMAGE_DECODE_FAILED, vm.error)
        assertFalse(vm.isDecoding)
    }

    @Test
    fun imageDecodeFilesResultFromWorkingDecoder() = runTest {
        val shares = Sskr.generate(2, 3, ByteArray(16) { 1 }, random)
        val fakeDecoder = QrDecoder { listOf(Ur.toUr(shares[0])) }
        val vm = viewModel()
        vm.addFromImage(ByteArray(8), fakeDecoder)
        advanceUntilIdle()
        assertEquals(1, vm.shares.size)
        assertNull(vm.error)
    }

    @Test
    fun bundledImageFilesBothShardAndEnvelope() = runTest {
        // A saved sheet PNG holds two QRs: the shard and the recovery envelope.
        val protected = KekEnvelope.protect(2, 3, "bundled".toByteArray(), random)
        val bundledDecoder = QrDecoder {
            listOf(
                Ur.toUr(protected.shares[0]),
                Ur.toEnvelopeUr(protected.envelope),
            )
        }
        val vm = viewModel()
        vm.addFromImage(ByteArray(8), bundledDecoder)
        advanceUntilIdle()
        assertEquals(1, vm.shares.size)
        assertTrue(vm.hasEnvelope)
    }

    @Test
    fun isDecodingCoversTheDecodeAndBlocksReentry() = runTest {
        val shares = Sskr.generate(2, 3, ByteArray(16) { 1 }, random)
        val first = QrDecoder { listOf(Ur.toUr(shares[0])) }
        val second = QrDecoder { listOf(Ur.toUr(shares[1])) }
        val vm = viewModel()
        vm.addFromImage(ByteArray(8), first)
        assertTrue(vm.isDecoding)
        // A pick while a decode is in flight is dropped, not queued.
        vm.addFromImage(ByteArray(8), second)
        advanceUntilIdle()
        assertFalse(vm.isDecoding)
        assertEquals(1, vm.shares.size)
    }

    // --- Bundle import (saved words file / pasted blob) ---

    @Test
    fun addBundleRoundTripsCreateShareText() {
        // The exact text the create flow writes to a saved .txt must re-import:
        // it should pull out both the share line and the envelope line.
        val protected = KekEnvelope.protect(2, 3, "vault key".toByteArray(), random)
        val envelopeUr = Ur.toEnvelopeUr(protected.envelope).uppercase()
        val vm = RecoverViewModel()
        for (i in 0..1) {
            val share = protected.shares[i]
            val page = ShardPage(
                index = i + 1,
                count = 3,
                threshold = 2,
                secretName = "ignored",
                shareUrForQr = Ur.toUr(share).uppercase(),
                shareBytewords = Ur.toStandardBytewords(share),
                envelopeUrForQr = envelopeUr,
            )
            assertTrue(vm.addBundle(CreateSecretViewModel.shareText(page)))
        }
        assertTrue(vm.hasEnvelope)
        assertTrue(vm.canRecover)
        vm.recover()
        assertEquals("vault key", vm.result?.display)
    }

    @Test
    fun addBundleSkipsHumanTextAndDuplicates() {
        val shares = Sskr.generate(2, 3, ByteArray(16) { 1 }, random)
        val blob = buildString {
            appendLine("ShardQuorum shard 1 of 3")
            appendLine("Any 2 of 3 shards rebuild the secret.")
            appendLine(Ur.toUr(shares[0]).lowercase())
            appendLine(Ur.toStandardBytewords(shares[0])) // same share, duplicate
        }
        val vm = RecoverViewModel()
        assertTrue(vm.addBundle(blob))
        assertEquals(1, vm.shares.size) // duplicate folded, human lines ignored
        assertNull(vm.error)
    }

    @Test
    fun addBundleRejectsTextWithNothingRecognizable() {
        val vm = RecoverViewModel()
        assertFalse(vm.addBundle("just some notes\nnothing to see here"))
        assertEquals(RecoverError.UNRECOGNIZED_INPUT, vm.error)
    }

    // --- Spellcheck ---

    @Test
    fun spellcheckFlagsBadWordsWithSuggestions() {
        val checks = spellcheckBytewords("able axes zzzz")
        assertEquals(3, checks.size)
        assertTrue(checks[0].recognized)
        assertFalse(checks[1].recognized)
        assertTrue(checks[1].suggestions.contains("axis"))
        assertFalse(checks[2].recognized)
        assertTrue(checks[2].suggestions.isNotEmpty())
    }

    @Test
    fun resetClearsState() {
        val shares = Sskr.generate(2, 3, ByteArray(16) { 1 }, random)
        val vm = RecoverViewModel()
        vm.addInput(Ur.toUr(shares[0]))
        vm.reset()
        assertTrue(vm.shares.isEmpty())
        assertNull(vm.result)
        assertNull(vm.error)
        assertNull(vm.threshold)
    }
}
