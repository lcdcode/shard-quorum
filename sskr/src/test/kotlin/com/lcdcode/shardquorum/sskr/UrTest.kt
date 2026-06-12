package com.lcdcode.shardquorum.sskr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class UrTest {

   private fun hex(s: String): ByteArray =
      ByteArray(s.length / 2) {
         ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
      }

   // The spec's "third share" example: 5-byte header + 16-byte value = 21 bytes.
   private val specShare = hex("4bbf1101025abd490ee65b6084859854ee67736e75")

   // --- Spec vectors (BCR-2020-011) ---

   @Test
   fun taggedStandardBytewordsMatchesSpecVector() {
      val expected = "tuna next keep gyro gear runs body acid also heat " +
         "ruby gala beta visa help horn liar limp monk gush " +
         "waxy into junk jolt keep lion leaf ruby purr"
      assertEquals(expected, Ur.toStandardBytewords(specShare))
   }

   @Test
   fun urStringMatchesSpecVector() {
      assertEquals(
         "ur:sskr/gogrrsbyadaohtrygabavahphnlrlpmkghwyiojkjtkpmdkncfjp",
         Ur.toUr(specShare),
      )
   }

   @Test
   fun decodesSpecStandardBytewords() {
      val text = "tuna next keep gyro gear runs body acid also heat " +
         "ruby gala beta visa help horn liar limp monk gush " +
         "waxy into junk jolt keep lion leaf ruby purr"
      assertArrayEquals(specShare, Ur.fromStandardBytewords(text))
   }

   @Test
   fun decodesSpecUrString() {
      assertArrayEquals(
         specShare,
         Ur.fromUr("ur:sskr/gogrrsbyadaohtrygabavahphnlrlpmkghwyiojkjtkpmdkncfjp"),
      )
   }

   // --- Round trips, including the multi-byte CBOR length branch ---

   @Test
   fun roundTripsSixteenByteSecretShares() {
      val secret = ByteArray(16) { it.toByte() }
      for (share in Sskr.generate(2, 3, secret, SecureRandom())) {
         assertArrayEquals(share, Ur.fromStandardBytewords(Ur.toStandardBytewords(share)))
         assertArrayEquals(share, Ur.fromUr(Ur.toUr(share)))
      }
   }

   @Test
   fun roundTripsThirtyTwoByteSecretShares() {
      // 32-byte secret -> 37-byte share -> CBOR header 0x58 0x25, not 0x5x.
      val secret = ByteArray(32) { (it * 7).toByte() }
      for (share in Sskr.generate(3, 5, secret, SecureRandom())) {
         val ur = Ur.toUr(share)
         assertEquals("ur:sskr/", ur.substring(0, 8))
         assertArrayEquals(share, Ur.fromUr(ur))
         assertArrayEquals(share, Ur.fromStandardBytewords(Ur.toStandardBytewords(share)))
      }
   }

   @Test
   fun urDecodeIsCaseInsensitiveOnPrefixAndBody() {
      val ur = Ur.toUr(specShare)
      assertArrayEquals(specShare, Ur.fromUr(ur.uppercase()))
   }

   // --- Envelope UR (ur:sq-env/, ShardQuorum-proprietary) ---

   @Test
   fun envelopeUrRoundTrips() {
      val kek = KekEnvelope.generateKek(SecureRandom())
      val envelope = KekEnvelope.seal(kek, "some secret".toByteArray(), SecureRandom())
      val ur = Ur.toEnvelopeUr(envelope)
      assertEquals("ur:sq-env/", ur.substring(0, 10))
      assertArrayEquals(envelope, Ur.fromEnvelopeUr(ur))
      assertArrayEquals(envelope, Ur.fromEnvelopeUr(ur.uppercase()))
   }

   @Test
   fun envelopeAndShareUrTypesDoNotCrossDecode() {
      val kek = KekEnvelope.generateKek(SecureRandom())
      val envelope = KekEnvelope.seal(kek, "some secret".toByteArray(), SecureRandom())
      assertThrows(IllegalArgumentException::class.java) {
         Ur.fromUr(Ur.toEnvelopeUr(envelope))
      }
      assertThrows(IllegalArgumentException::class.java) {
         Ur.fromEnvelopeUr(Ur.toUr(specShare))
      }
   }

   // --- Malformed input rejection ---

   @Test
   fun rejectsWrongUrType() {
      assertThrows(IllegalArgumentException::class.java) {
         Ur.fromUr("ur:seed/gogrrsbyadaohtrygabavahphnlrlpmkghwyiojkjtkpmdkncfjp")
      }
   }

   @Test
   fun rejectsMissingUrPrefix() {
      assertThrows(IllegalArgumentException::class.java) {
         Ur.fromUr("gogrrsbyadaohtrygabavahphnlrlpmkghwyiojkjtkpmdkncfjp")
      }
   }

   @Test
   fun rejectsUntaggedInputWhereTagExpected() {
      // Valid untagged CBOR rendered as standard bytewords lacks the SSKR tag.
      val untaggedAsStandard = Bytewords.encode(
         byteArrayOf(0x55) + specShare,
         Bytewords.Style.STANDARD,
      )
      assertThrows(IllegalArgumentException::class.java) {
         Ur.fromStandardBytewords(untaggedAsStandard)
      }
   }

   @Test
   fun rejectsLengthMismatch() {
      // Header claims 21 bytes but payload carries 20.
      val truncated = byteArrayOf(0x55) + specShare.copyOfRange(0, 20)
      val text = "ur:sskr/" + Bytewords.encode(truncated, Bytewords.Style.MINIMAL)
      assertThrows(IllegalArgumentException::class.java) { Ur.fromUr(text) }
   }

   @Test
   fun rejectsTrailingGarbage() {
      val padded = byteArrayOf(0x55) + specShare + byteArrayOf(0x00)
      val text = "ur:sskr/" + Bytewords.encode(padded, Bytewords.Style.MINIMAL)
      assertThrows(IllegalArgumentException::class.java) { Ur.fromUr(text) }
   }
}
