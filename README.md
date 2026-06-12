# ShardQuorum

A *quorum of shards*...

ShardQuorum is an offline Android app that splits a high-value secret into a number of printable / hand-transcribable 'shards' or shares, a sufficient majority of which reconstruct it, while fewer than this reveal nothing (information-theoretic security via Shamir's Secret Sharing).

Use cases: disaster-proof backup of a password-manager master passphrase, wallet seeds, LUKS keys, or another app's backup PIN; estate planning (e.g. 2-of-3 among family and a lawyer). Distribute shares physically (home, fireproof box, family, bank box); 3 out of 5 of which, for example, reunite the secret.

## Methodology

- GF(256) field arithmetic (`:sskr`)
- Shamir split/combine with SLIP-39/SSKR digest scheme (`:sskr`)
- SSKR share serialization (BCR-2020-011 metadata envelope) + single-group facade
- Bytewords codec (BCR-2020-012) + CRC-32, byte-exact vs official spec vectors
- UR / CBOR wrapping for QR (tag #6.40309 / untagged `ur:sskr/`)
- KEK envelope (shard a random 256-bit key; store secret encrypted under it)
- Android `:app` module (Compose UI, CameraX QR, PrintManager, FLAG_SECURE)

## Design decisions

- **Format: SSKR** (Blockchain Commons, BCR-2020-011), not SLIP-39. Byte-exact round-trip of arbitrary 32-byte secrets, no wallet-seed layer to implement, bytewords encoding ideal for hand transcription, UR-format QR. SLIP-39 left as possible v2 interop (both are GF(256) Shamir).
- **'Clean-room' written in Kotlin**, not a C port. SSKR's crypto core is SLIP-39's GF(256) Shamir math (bc-shamir is itself a C port of it); the algorithm is public and verifiable from first principles, so clean Kotlin is safer to audit and keeps licensing unambiguous, with no JNI surface.
- **KEK architecture**: generate a random 256-bit key, shard *that*, store the real secret encrypted under it. Shares stay valid when the secret changes; share size is fixed.
- **Zero network**: no `INTERNET` permission, ever; printing goes through the Android system spooler. A Gradle `verifyNoNetwork` guard will fail the build if any network permission appears in the merged manifest (as in Mood Cairns).
- **Built on-device in Termux** (Kotlin, SDK 34, JDK 21), F-Droid target.

## Integrity (two independent layers)

1. **Embedded digest share.** The Shamir layer reserves x = 255 for the secret and x = 254 for a digest share carrying a 4-byte HMAC-SHA256 over the secret (the SLIP-39 scheme SSKR inherits). Reconstruction recomputes and verifies it, so assembling the wrong/insufficient/corrupt shares is caught *cryptographically*.
2. **Transcription CRC.** Bytewords appends a CRC-32, catching typos before the shares are even combined. A verify-after-transcription flow compensates for CRC-32 being weaker than SLIP-39's error-locating RS1024.

## Limitations

At split/reconstruct time the phone sees the whole secret. Shamir protects secrets at rest and in distribution, not in use.

During sharing, another app may save it and be backed up somewhere, defeating the distribited nature of this technique. Similarly, a printer used to print all of the shards might phone home, ot a network printer exposes the shares to other devices on the network. The best method is a COMBINATION of different methods, one for each shard.

## Module layout

- `:sskr` — pure-JVM Kotlin crypto core (no Android dependency), JUnit-tested.
- `:app` — Android application, depends on `:sskr`.

## License

GPL-3.0-only — see [LICENSE](LICENSE).

ShardQuorum is a clean-room implementation of the public Blockchain Commons SSKR (BCR-2020-011) and Bytewords (BCR-2020-012) specifications. The bytewords wordlist, vendored specs under `docs/reference/`, and reference test vectors are derived from Blockchain Commons material under the BSD-2-Clause Plus Patent License (compatible with GPLv3); see [NOTICE](NOTICE) for full attribution.
