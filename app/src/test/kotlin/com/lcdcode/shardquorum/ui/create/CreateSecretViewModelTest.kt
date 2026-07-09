package com.lcdcode.shardquorum.ui.create

import com.lcdcode.shardquorum.MainDispatcherRule
import com.lcdcode.shardquorum.qr.QrDecoder
import com.lcdcode.shardquorum.qr.UnavailableQrDecoder
import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.Sskr
import com.lcdcode.shardquorum.sskr.Ur
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateSecretViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(configure: CreateSecretViewModel.() -> Unit = {}) =
        CreateSecretViewModel(decodeDispatcher = mainDispatcherRule.dispatcher).apply(configure)

    // --- Validation ---

    @Test
    fun blankNameRejected() {
        val vm = viewModel { secretInput = "hunter2!" }
        vm.generate()
        assertEquals(CreateError.NAME_REQUIRED, vm.error)
        assertNull(vm.shards)
    }

    @Test
    fun emptySecretRejected() {
        val vm = viewModel { name = "Test" }
        vm.generate()
        assertEquals(CreateError.SECRET_REQUIRED, vm.error)
    }

    // --- Quorum clamping ---

    @Test
    fun quorumStaysCoherent() {
        val vm = viewModel()
        // Count cannot drop below the minimum quorum; threshold follows it down.
        vm.setShareCountClamped(1)
        assertEquals(CreateSecretViewModel.MIN_QUORUM, vm.shareCount)
        assertEquals(CreateSecretViewModel.MIN_QUORUM, vm.threshold)
        // Threshold caps at the max share count, dragging count up with it.
        vm.setThresholdClamped(99)
        assertEquals(16, vm.threshold)
        assertEquals(16, vm.shareCount)
        // Threshold cannot fall below the minimum quorum.
        vm.setThresholdClamped(0)
        assertEquals(CreateSecretViewModel.MIN_QUORUM, vm.threshold)
    }

    @Test
    fun thresholdAndCountNeverGoBelowMinimum() {
        val vm = viewModel()
        vm.setThresholdClamped(CreateSecretViewModel.MIN_QUORUM - 1)
        assertEquals(CreateSecretViewModel.MIN_QUORUM, vm.threshold)
        vm.setShareCountClamped(CreateSecretViewModel.MIN_QUORUM - 1)
        assertEquals(CreateSecretViewModel.MIN_QUORUM, vm.shareCount)
    }

    // --- Generation ---

    @Test
    fun shardsRecoverTheSecret() {
        val vm = viewModel {
            name = "Vault passphrase"
            secretInput = "correct horse battery staple"
        }
        vm.generate()
        assertNull(vm.error)
        val pages = requireNotNull(vm.shards)
        assertEquals(5, pages.size)
        assertEquals(3, pages.first().threshold)
        assertNotNull(pages.first().envelopeUrForQr)

        // Recover from the rendered output only: 3 QR strings + the envelope QR.
        val envelope = Ur.fromEnvelopeUr(pages.first().envelopeUrForQr!!)
        val shares = pages.shuffled().take(3).map { Ur.fromUr(it.shareUrForQr) }
        assertArrayEquals(
            "correct horse battery staple".toByteArray(),
            KekEnvelope.recover(envelope, shares),
        )
    }

    @Test
    fun bytewordsAlsoRecoverable() {
        val vm = viewModel {
            name = "Test"
            secretInput = "s3cret"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        // The transcription fallback decodes to the same share as the QR.
        for (page in pages) {
            assertArrayEquals(
                Ur.fromUr(page.shareUrForQr),
                Ur.fromStandardBytewords(page.shareBytewords),
            )
        }
    }

    // --- Lifecycle ---

    @Test
    fun qrContentIsUppercaseAlphanumericSafe() {
        val vm = viewModel {
            name = "Test"
            secretInput = "abc"
        }
        vm.generate()
        val qr = requireNotNull(vm.shards).first().shareUrForQr
        assertEquals(qr.uppercase(), qr)
    }

    @Test
    fun shareTextStartsWithNameAndCarriesPayload() {
        val vm = viewModel {
            name = "My bank PIN"
            secretInput = "hunter2!"
        }
        vm.generate()
        val page = requireNotNull(vm.shards).first()
        val text = CreateSecretViewModel.shareText(page)

        // The secret name is the first line, for telling shards apart.
        assertEquals("My bank PIN", text.lineSequence().first())
        assertTrue(text.contains("shard 1 of 5"))
        assertTrue(text.contains(page.shareBytewords))
        assertTrue(text.contains(page.shareUrForQr.lowercase()))
        // Envelope must travel with the shard.
        assertTrue(text.contains(page.envelopeUrForQr!!.lowercase()))
        // Words appear before the UR string to avoid confusing the two.
        assertTrue(
            text.indexOf(page.shareBytewords) < text.indexOf(page.shareUrForQr.lowercase())
        )
    }

    // --- Verify-before-distribute ---

    @Test
    fun generateMovesToRecordPhase() {
        val vm = viewModel {
            name = "Test"
            secretInput = "abc"
        }
        vm.generate()
        assertEquals(CreatePhase.RECORD, vm.phase)
        vm.startVerify()
        assertEquals(CreatePhase.VERIFY, vm.phase)
    }

    @Test
    fun verifyingWithCorrectSharesSucceeds() {
        val vm = viewModel {
            name = "Vault"
            secretInput = "correct horse battery staple"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        vm.startVerify()
        // Re-enter the threshold of shards, as a recipient would.
        vm.addVerifyText(pages[4].shareUrForQr)
        vm.addVerifyText(pages[0].shareUrForQr)
        assertEquals(VerifyState.COLLECTING, vm.verifyState)
        vm.addVerifyText(pages[2].shareUrForQr)
        assertEquals(VerifyState.VERIFIED, vm.verifyState)
    }

    @Test
    fun verifyAcceptsTheFullSavedWordsBlob() {
        val vm = viewModel {
            name = "Seed"
            secretInput = "passphrase here"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        vm.startVerify()
        // The saved .txt blob carries both the share and the envelope line.
        pages.take(3).forEach { vm.addVerifyText(CreateSecretViewModel.shareText(it)) }
        assertEquals(VerifyState.VERIFIED, vm.verifyState)
    }

    @Test
    fun verifyRejectsSharesFromADifferentSplit() {
        val vm = viewModel {
            name = "Seed"
            secretInput = "abc"
        }
        vm.generate()
        vm.startVerify()
        // A share from an unrelated split must be refused, not counted.
        val foreign = Sskr.generate(3, 5, ByteArray(16) { 9 }, java.security.SecureRandom())
        assertFalse(vm.addVerifyText(Ur.toUr(foreign.first())))
        assertEquals(VerifyInputError.DIFFERENT_SPLIT, vm.verifyError)
        assertEquals(0, vm.verifyShares.size)
    }

    @Test
    fun verifyImageFilesDecodedSharesAndVerifies() = runTest {
        // A picked sheet image (decode runs async) verifies just like typed text.
        val vm = viewModel {
            name = "Vault"
            secretInput = "correct horse battery staple"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        vm.startVerify()
        vm.addVerifyImage(ByteArray(8), QrDecoder { pages.take(3).map { it.shareUrForQr } })
        assertTrue(vm.isDecoding)
        advanceUntilIdle()
        assertFalse(vm.isDecoding)
        assertEquals(VerifyState.VERIFIED, vm.verifyState)
    }

    @Test
    fun verifyImageDecodeFailureSurfacesError() = runTest {
        val vm = viewModel {
            name = "Vault"
            secretInput = "abc"
        }
        vm.generate()
        vm.startVerify()
        vm.addVerifyImage(ByteArray(8), UnavailableQrDecoder())
        advanceUntilIdle()
        assertEquals(VerifyInputError.IMAGE_DECODE_FAILED, vm.verifyError)
        assertFalse(vm.isDecoding)
        assertEquals(0, vm.verifyShares.size)
    }

    @Test
    fun backToRecordClearsVerifyCollection() {
        val vm = viewModel {
            name = "Seed"
            secretInput = "abc"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        vm.startVerify()
        vm.addVerifyText(pages[0].shareUrForQr)
        vm.backToRecord()
        assertEquals(CreatePhase.RECORD, vm.phase)
        assertEquals(0, vm.verifyShares.size)
        assertEquals(VerifyState.COLLECTING, vm.verifyState)
    }

    @Test
    fun resetClearsEverything() {
        val vm = viewModel {
            name = "Test"
            secretInput = "abc"
        }
        vm.generate()
        assertNotNull(vm.shards)
        vm.reset()
        assertNull(vm.shards)
        assertNull(vm.error)
        assertEquals("", vm.name)
        assertEquals("", vm.secretInput)
        assertEquals(CreateSecretViewModel.DEFAULT_THRESHOLD, vm.threshold)
        assertEquals(CreateSecretViewModel.DEFAULT_SHARE_COUNT, vm.shareCount)
        assertEquals(CreatePhase.FORM, vm.phase)
    }

    @Test
    fun discardShardsKeepsFormInputs() {
        val vm = viewModel {
            name = "Keep me"
            secretInput = "abc"
        }
        vm.generate()
        vm.discardShards()
        assertNull(vm.shards)
        assertEquals("Keep me", vm.name)
        assertEquals("abc", vm.secretInput)
    }
}
