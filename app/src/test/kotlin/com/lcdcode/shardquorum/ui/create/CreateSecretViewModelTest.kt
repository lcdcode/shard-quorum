package com.lcdcode.shardquorum.ui.create

import com.lcdcode.shardquorum.sskr.KekEnvelope
import com.lcdcode.shardquorum.sskr.Sskr
import com.lcdcode.shardquorum.sskr.Ur
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateSecretViewModelTest {

    private fun viewModel(configure: CreateSecretViewModel.() -> Unit = {}) =
        CreateSecretViewModel().apply(configure)

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

    @Test
    fun badHexRejectedInDirectMode() {
        val vm = viewModel {
            name = "Test"
            mode = SecretMode.DIRECT
            secretInput = "not hex at all"
        }
        vm.generate()
        assertEquals(CreateError.HEX_INVALID, vm.error)
    }

    @Test
    fun wrongHexLengthRejectedInDirectMode() {
        val vm = viewModel {
            name = "Test"
            mode = SecretMode.DIRECT
            secretInput = "aabb"
        }
        vm.generate()
        assertEquals(CreateError.HEX_LENGTH, vm.error)
    }

    @Test
    fun oddByteCountRejectedInDirectMode() {
        // 17 bytes: inside 16..32 but odd, which SSKR forbids.
        val vm = viewModel {
            name = "Test"
            mode = SecretMode.DIRECT
            secretInput = "00".repeat(17)
        }
        vm.generate()
        assertEquals(CreateError.HEX_LENGTH, vm.error)
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

    // --- Generation, KEK mode ---

    @Test
    fun kekModeShardsRecoverTheSecret() {
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
    fun kekModeBytewordsAlsoRecoverable() {
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

    // --- Generation, direct mode ---

    @Test
    fun directModeShardsRecoverTheSecretBytes() {
        val secretHex = "ff00112233445566778899aabbccddee"
        val vm = viewModel {
            name = "Seed"
            mode = SecretMode.DIRECT
            secretInput = secretHex
        }
        vm.setThresholdClamped(3)
        vm.setShareCountClamped(4)
        vm.generate()
        assertNull(vm.error)
        val pages = requireNotNull(vm.shards)
        assertEquals(4, pages.size)
        assertNull(pages.first().envelopeUrForQr)

        val shares = pages.take(3).map { Ur.fromUr(it.shareUrForQr) }
        assertArrayEquals(
            CreateSecretViewModel.parseHex(secretHex),
            Sskr.combine(shares),
        )
    }

    @Test
    fun directModeAcceptsWhitespaceInHex() {
        val vm = viewModel {
            name = "Seed"
            mode = SecretMode.DIRECT
            secretInput = "ff00 1122 3344 5566 7788 99aa bbcc ddee"
        }
        vm.generate()
        assertNull(vm.error)
        assertNotNull(vm.shards)
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
        // KEK mode: envelope must travel with the shard.
        assertTrue(text.contains(page.envelopeUrForQr!!.lowercase()))
        // Words appear before the UR string to avoid confusing the two.
        assertTrue(text.indexOf(page.shareBytewords) < text.indexOf(page.shareUrForQr.lowercase()))
    }

    @Test
    fun directModeShareTextHasNoEnvelope() {
        val vm = viewModel {
            name = "Seed"
            mode = SecretMode.DIRECT
            secretInput = "ff00112233445566778899aabbccddee"
        }
        vm.generate()
        val text = CreateSecretViewModel.shareText(requireNotNull(vm.shards).first())
        assertFalse(text.contains("envelope"))
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
    fun verifyingWithCorrectKekSharesSucceeds() {
        val vm = viewModel {
            name = "Vault"
            secretInput = "correct horse battery staple"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        vm.startVerify()
        // Re-enter the threshold of shards plus the envelope, as a recipient would.
        vm.addVerifyText(pages[4].shareUrForQr)
        vm.addVerifyText(pages[0].shareUrForQr)
        assertEquals(VerifyState.COLLECTING, vm.verifyState)
        vm.addVerifyText(pages[2].shareUrForQr)
        assertEquals(VerifyState.VERIFIED, vm.verifyState)
    }

    @Test
    fun verifyingDirectModeSucceeds() {
        val vm = viewModel {
            name = "Seed"
            mode = SecretMode.DIRECT
            secretInput = "ff00112233445566778899aabbccddee"
        }
        vm.generate()
        val pages = requireNotNull(vm.shards)
        vm.startVerify()
        pages.take(3).forEach { vm.addVerifyText(it.shareBytewords) }
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
        assertEquals(SecretMode.KEK, vm.mode)
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

    // --- Hex parser ---

    @Test
    fun parseHexEdgeCases() {
        assertNull(CreateSecretViewModel.parseHex(""))
        assertNull(CreateSecretViewModel.parseHex("a"))
        assertNull(CreateSecretViewModel.parseHex("zz"))
        assertArrayEquals(byteArrayOf(0x0f, -1), CreateSecretViewModel.parseHex("0fFF"))
    }
}
