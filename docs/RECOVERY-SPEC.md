# ShardQuorum Recovery Specification

Status: DRAFT skeleton. This document will become the self-contained, language-
agnostic specification for recovering a ShardQuorum secret from its shards. It is
written to be followed by a competent human programmer OR handed to a competent
large language model (LLM) that will generate a recovery tool in any language.

The runnable tools (the Android app, the single-file `recover.html`) are
conveniences. This document plus its test vectors is the root artifact: as long
as it survives and can be verified, the secret is recoverable by reimplementing
public math, with no dependence on any shipped binary still running.

Spec version: 1 (covers SSKR single-group shares and `SQKE` envelope version 1).

---

## Table of contents

1. Purpose and scope
2. **Operator safety - read this first** (written in full below)
3. **Using this document with an LLM** (written in full below)
4. Recovery pipeline overview
5. Notation and conventions
6. ByteWords decoding and CRC-32 (BCR-2020-012)
7. UR and CBOR framing (`ur:sskr/`, `ur:sq-env/`)
8. SSKR share structure: the 5-byte header (BCR-2020-011)
9. GF(256) field arithmetic
10. Shamir combination and SLIP-39/SSKR digest verification
11. Direct-mode recovery
12. KEK envelope (`SQKE`) decryption
13. End-to-end recovery procedures
14. Test vectors (the definition of done)
15. Worked example
16. Format identifiers and versioning
17. Glossary
18. References, provenance, and license

---

## 1. Purpose and scope

`[TO BE WRITTEN]` What this recovers and what it does not. In scope: combining a
quorum of SSKR shares and, in encrypted mode, opening the `SQKE` envelope to
return the original secret. Out of scope: creating or re-sealing secrets (never
do generation from this document). States the two modes (Direct, encrypted
envelope) and that recovery needs at least the quorum count of shards, plus the
envelope in encrypted mode.

---

## 2. Operator safety - read this first

This document handles instructions for rebuilding a high-value secret. Read this
entire section before doing anything else. The steps below are not optional
advice; they are the conditions under which recovery is safe.

### 2.1 Verify this document before you trust it

A tampered copy of this specification could instruct an LLM, or mislead a
programmer, into building a tool that corrupts or leaks your secret. Before using
it, verify its authenticity:

1. Compute the SHA-256 of this file using a tool you trust (for example the
   operating system command `sha256sum`).
2. Compare it to the recovery bundle's root value that was stored alongside your
   shards (printed on the shard cards, or kept with them).
3. If they do not match, stop. Do not use this copy. Obtain another copy and
   verify again.

Do not skip this even if the file "looks right." The whole trust chain rests on
this one comparison.

### 2.2 Work offline, on a device you can trust

Disconnect from all networks (Wi-Fi, cellular, Ethernet) before you handle any
shard or run any recovery tool. Offline operation is what makes the rest of this
process safe: even a flawed or malicious recovery tool cannot send your secret
anywhere if there is no network.

Prefer a device that is wiped or ephemeral. After recovery, treat the device as
having seen the secret: clear the clipboard, close the tool, and do not leave the
secret in files, notes, screenshots, or browser history.

### 2.3 The secret and the shards NEVER touch an LLM or any online service

This is the single most important rule when using the LLM approach in Section 3.

- An LLM is used ONLY to turn this public specification into source code.
- The LLM sees ONLY this document. It must never see your shards, your envelope,
  the bundle root value, or the recovered secret.
- Pasting any shard or the recovered secret into a chatbot, search box, website,
  or any networked application is a disclosure to a third party that may log,
  cache, or train on it. Assume anything you paste online is permanently exposed.

Correct order, always: generate the tool from the spec (public), disconnect from
the network, then run the tool with your shards (private).

### 2.4 Prove the tool is correct before trusting it with real shards

Any implementation - LLM-generated, hand-written, or the shipped tools - can be
silently wrong and return believable garbage. Before you feed it real shards:

1. Run it against every test vector in Section 14.
2. Confirm it reproduces each expected output exactly, including the intermediate
   values where given.
3. Only if every vector passes should you trust it with your own shards.

If any vector fails, the tool is wrong. Do not guess whether "close" is good
enough; with cryptography it is not.

### 2.5 Quorum and exposure reminders

- You need at least the quorum (K) of distinct shards. Fewer reveal nothing, by
  mathematical guarantee, not merely by difficulty.
- In encrypted mode you also need the recovery envelope. Shards alone yield only
  a random key, not the secret; the envelope alone reveals nothing.
- Gather only what you need in one place, and only for as long as you need it. The
  moment a quorum of shards plus the envelope sit together, the secret is
  reconstructible by whoever holds them.

---

## 3. Using this document with an LLM

If no shipped recovery tool will run on the hardware you have, you can have any
competent LLM build one from this document, in any programming language. This
works because the entire specification, including the wordlist and test vectors,
is self-contained: the model needs nothing beyond this file.

Follow Section 2 first. In particular: the model receives only this document; it
never receives your shards or secret.

### 3.1 Procedure

1. On an internet-connected device, open an LLM you consider competent.
2. Paste the prompt in 3.2, followed by the full text of this document.
3. Take the generated source code and move it to an OFFLINE device (for example
   on a USB drive). Do not run it online.
4. On the offline device, run the generated tool against the Section 14 test
   vectors. Confirm every vector passes.
5. Only then, enter your real shards (and envelope, in encrypted mode) into the
   offline tool to recover your secret.

If you have a capable offline LLM, you can do steps 1-2 offline as well, which is
preferable. Either way, the shards are entered only in step 5, only offline.

### 3.2 Canonical prompt

Paste the following, then the entire contents of this specification:

> You are implementing a data-recovery tool from a complete, self-contained
> specification that I will paste after these instructions. Requirements:
>
> 1. Implement the tool in `[LANGUAGE - e.g. Python 3, single file, standard
>    library only]`.
> 2. Implement strictly what the specification defines. Do not invent, guess, or
>    "improve" any step. If something is ambiguous, state the ambiguity rather
>    than guessing.
> 3. The tool must make NO network calls and read NO files other than the inputs
>    I explicitly provide at runtime. Do not add telemetry, logging to remote
>    services, update checks, or any outbound connection.
> 4. Include the specification's test vectors as a built-in self-test that runs
>    first and refuses to proceed unless every vector reproduces its expected
>    output exactly, including any intermediate values the spec lists.
> 5. The tool accepts shard inputs as text (ByteWords or `ur:` strings) and, in
>    encrypted mode, the recovery envelope, then prints the recovered secret. It
>    must not transmit, upload, or persist the secret anywhere.
> 6. Provide the complete source in one block, plus the exact command to run the
>    self-test and then a recovery.
>
> After the code, briefly note any point in the specification you found
> underspecified. Here is the specification:

### 3.3 Why these constraints

- "Standard library only / single file" maximizes the chance the code runs years
  from now and is easy to audit.
- The mandatory self-test against the vectors is the defense against a model that
  hallucinates a subtly wrong algorithm (see Section 2.4).
- The no-network requirement, combined with running offline, is the defense
  against a tool that is wrong or hostile (see Section 2.2 and 2.3): with no
  network and no capable model error surviving the self-test, neither leakage nor
  silent corruption can get through.

---

## 4. Recovery pipeline overview

`[TO BE WRITTEN]` A diagram of the path from printed input to recovered secret:
ByteWords or `ur:` text -> decode + CRC-32 / UR-CBOR unwrap -> 5-byte SSKR header
+ share value -> group/verify by identifier -> GF(256) Shamir combine -> digest
verification -> (Direct: this is the secret) or (encrypted: this is the 256-bit
KEK) -> AES-256-GCM open of the `SQKE` envelope -> secret.

## 5. Notation and conventions

`[TO BE WRITTEN]` Byte/bit ordering, hex notation, big-endian within header
bytes, array indexing, how pseudocode is written.

## 6. ByteWords decoding and CRC-32 (BCR-2020-012)

`[TO BE WRITTEN]` Standard (space-separated) and minimal styles, the 256-word
list (inlined here), mapping words to bytes, and the trailing CRC-32 check that
catches transcription errors. Source: `Bytewords.kt`, `docs/reference/
bcr-2020-012-bytewords.md`.

## 7. UR and CBOR framing (`ur:sskr/`, `ur:sq-env/`)

`[TO BE WRITTEN]` The minimal CBOR shapes used: an untagged byte string (UR
bodies) and a byte string under CBOR tag #6.40309 (standard ByteWords shares).
The `ur:sskr/` prefix for shares and the ShardQuorum-proprietary `ur:sq-env/`
prefix for the envelope. Source: `Ur.kt`.

## 8. SSKR share structure: the 5-byte header (BCR-2020-011)

`[TO BE WRITTEN]` The 40-bit header: 16-bit identifier, then group-threshold-1,
group-count-1, group-index, member-threshold-1, reserved nibble (0), member-index
nibbles. Single-group case only (group threshold = group count = 1). Source:
`SskrShare.kt`.

## 9. GF(256) field arithmetic

`[TO BE WRITTEN]` The field, the reduction polynomial, log/exp tables or the
generator, and add/multiply/inverse. Source: `Gf256.kt`,
`docs/reference/bcr-2020-011-sskr.md`.

## 10. Shamir combination and SLIP-39/SSKR digest verification

`[TO BE WRITTEN]` Lagrange interpolation over GF(256); the reserved indices
x=255 (secret) and x=254 (digest share); recomputing and checking the 4-byte
HMAC-SHA256 digest so that wrong, insufficient, or corrupt shares are rejected
cryptographically. Source: `Shamir.kt`.

## 11. Direct-mode recovery

`[TO BE WRITTEN]` When the shards encode the secret directly: combine, verify
digest, output is the secret (16-32 bytes). Source: `Sskr.kt`.

## 12. KEK envelope (`SQKE`) decryption

`[TO BE WRITTEN]` Wire format: magic `"SQKE"` (4 bytes), version byte `0x01`,
12-byte nonce, then AES-256-GCM ciphertext including the 16-byte tag. The
magic+version header is the GCM associated data (AAD). The 256-bit KEK comes from
combining the shards (Section 10). Authentication failure means wrong KEK/shards
or tampering. Source: `KekEnvelope.kt`.

## 13. End-to-end recovery procedures

`[TO BE WRITTEN]` Step-by-step for both modes, from collected inputs to secret,
including how to detect which mode you are in (a 32-byte combine result with an
envelope present implies encrypted mode).

## 14. Test vectors (the definition of done)

`[TO BE WRITTEN]` Inline vectors for both modes with intermediate values
(decoded share bytes, combined KEK, final secret) so a wrong step can be
localized. An implementation is correct if and only if it reproduces every vector
here exactly. Reuse the existing spec vectors plus ShardQuorum-specific `SQKE`
envelope vectors.

## 15. Worked example

`[TO BE WRITTEN]` One complete recovery walked through by hand, end to end.

## 16. Format identifiers and versioning

`[TO BE WRITTEN]` Spec version, `SQKE` version `0x01`, UR types `sskr` and
`sq-env`, CBOR tag 40309. How future format changes are signaled.

## 17. Glossary

`[TO BE WRITTEN]` Shard/share, quorum (K), count (N), KEK, envelope, ByteWords,
UR, SSKR, digest share, AAD.

## 18. References, provenance, and license

`[TO BE WRITTEN]` BCR-2020-011 (SSKR), BCR-2020-012 (ByteWords), SLIP-39, and the
vendored copies under `docs/reference/`. License inheritance (GPL-3.0-only;
Blockchain Commons material under BSD-2-Clause-Plus-Patent). See `NOTICE`.
