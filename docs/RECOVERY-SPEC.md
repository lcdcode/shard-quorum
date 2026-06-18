# ShardQuorum Recovery Specification

Status: DRAFT. This document is the self-contained, language-agnostic
specification for recovering a ShardQuorum secret from its shards. It is written
to be followed by a competent human programmer OR handed to a competent large
language model (LLM) that will generate a recovery tool in any language.

The runnable tools (the Android app, the single-file `recover.html`) are
conveniences. This document plus its test vectors is the root artifact: as long
as it survives and can be verified, the secret is recoverable by reimplementing
public math, with no dependence on any shipped binary still running.

Spec version: 1 (covers SSKR single-group shares and `SQKE` envelope version 1).

---

## Table of contents

1. Purpose and scope
2. **Operator safety - read this first**
3. **Using this document with an LLM**
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

This document specifies how to recover a secret that was protected with
ShardQuorum. It covers exactly the recovery path: turning a quorum of transcribed
or scanned shards (and, in encrypted mode, the recovery envelope) back into the
original secret.

In scope:

- Decoding the two transcribable share forms (standard ByteWords and the
  `ur:sskr/` Uniform Resource form) and the `ur:sq-env/` envelope form.
- Reconstructing the SSKR/Shamir secret from a quorum of shares.
- Verifying integrity via the embedded digest share.
- Decrypting the `SQKE` recovery envelope in encrypted mode.

Out of scope, deliberately:

- Creating, splitting, or re-sealing secrets. Never perform generation from this
  document; a recovery tool has no need for it and it widens the attack surface.
- Multi-group SSKR (a threshold of groups). ShardQuorum produces single-group
  splits only. The header layout below describes the general fields, but a
  recovery tool targeting ShardQuorum output may assume a single group.

ShardQuorum has two modes, chosen at creation time:

- **Direct mode**: the secret (an even number of bytes, 16 to 32) is split
  directly. The combined shares ARE the secret. Interoperable with any
  SSKR-compatible tool.
- **Encrypted-envelope (KEK) mode**: a random 256-bit key (the KEK) is split, and
  the secret is stored separately, encrypted under that key in the `SQKE`
  envelope. The combined shares yield the KEK, which then decrypts the envelope.

You need at least the quorum count (K) of distinct shards. In encrypted mode you
also need the envelope. Fewer than K shards reveal nothing, by mathematical
guarantee.

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

A shard, as held by a recipient, is one text string in one of these forms:

- standard ByteWords: space-separated four-letter words (the words printed under
  a shard's QR code), or
- a `ur:sskr/...` string (the content of a shard QR code).

In encrypted mode there is additionally a `ur:sq-env/...` string (the envelope QR
content) that travels with every shard.

The pipeline from those strings to the secret:

```
  shard text (ByteWords or ur:sskr/)
        |  Section 6 (ByteWords decode + CRC-32)
        |  Section 7 (UR / CBOR unwrap)
        v
  serialized SSKR share = 5-byte header + share value
        |  Section 8 (parse header: identifier, threshold, member index)
        v
  group shares by identifier; collect >= threshold distinct member values
        |  Section 9, 10 (GF(256) Shamir combine + digest verification)
        v
  combined output
        |
        +-- Direct mode  -> this IS the secret (Section 11). Done.
        |
        +-- KEK mode     -> this is the 256-bit KEK.
                            envelope text (ur:sq-env/) --Section 6,7--> SQKE bytes
                            Section 12 (AES-256-GCM open with the KEK) -> secret
```

## 5. Notation and conventions

- Bytes are 8-bit values, shown in hexadecimal (for example `0x1b`, or in long
  strings without separators: `00112233`). "Big-endian" means most significant
  byte first.
- `XOR` is bitwise exclusive-or. In GF(256), addition and subtraction are both
  XOR (Section 9).
- `a || b` denotes byte-string concatenation.
- `H[i..j]` denotes bytes `i` (inclusive) to `j` (exclusive) of array `H`,
  zero-indexed.
- "Word index" in Section 6 means the position (0..255) of a word in the ByteWords
  wordlist; that index equals the byte value the word encodes.
- All multi-byte integers in the SSKR header and CBOR framing are big-endian.

## 6. ByteWords decoding and CRC-32 (BCR-2020-012)

ByteWords maps each byte to one of 256 four-letter English words. The 4-byte
CRC-32 of the payload is appended before encoding, so decoding both reconstructs
the bytes and detects transcription errors.

### 6.1 Wordlist

256 words, where the word's position in this list (0-based, reading left to
right, top to bottom) is the byte value it represents:

```
able acid also apex aqua arch atom aunt away axis back bald barn belt beta bias
blue body brag brew bulb buzz calm cash cats chef city claw code cola cook cost
crux curl cusp cyan dark data days deli dice diet door down draw drop drum dull
duty each easy echo edge epic even exam exit eyes fact fair fern figs film fish
fizz flap flew flux foxy free frog fuel fund gala game gear gems gift girl glow
good gray grim guru gush gyro half hang hard hawk heat help high hill holy hope
horn huts iced idea idle inch inky into iris iron item jade jazz join jolt jowl
judo jugs jump junk jury keep keno kept keys kick kiln king kite kiwi knob lamb
lava lazy leaf legs liar limp lion list logo loud love luau luck lung main many
math maze memo menu meow mild mint miss monk nail navy need news next noon note
numb obey oboe omit onyx open oval owls paid part peck play plus poem pool pose
puff puma purr quad quiz race ramp real redo rich road rock roof ruby ruin runs
rust safe saga scar sets silk skew slot soap solo song stub surf swan taco task
taxi tent tied time tiny toil tomb toys trip tuna twin ugly undo unit urge user
vast very veto vial vibe view visa void vows wall wand warm wasp wave waxy webs
what when whiz wolf work yank yawn yell yoga yurt zaps zero zest zinc zone zoom
```

### 6.2 Styles

- **Standard**: full words separated by single spaces. Decoding is
  case-insensitive; split on whitespace.
- **Minimal**: for each byte, only the word's first and last letter, concatenated
  with no separators (two characters per byte). The first+last letter pair is
  unique across the 256 words, so it identifies the word. This is the style used
  inside `ur:` strings (Section 7).

A third style, URI (full words separated by hyphens), exists in the format but is
not needed for recovery.

### 6.3 Decoding

1. Convert the input to a byte sequence:
   - Standard: lowercase, split on whitespace (so a transcription wrapped across
     lines decodes fine), map each word to its index via the wordlist. Unknown
     word -> error.
   - Minimal: lowercase; the length must be even; for each two-character pair, map
     the (first letter, last letter) to the unique word, then to its index.
2. The result must be at least 4 bytes. Split off the last 4 bytes as the provided
   checksum; the remainder is the payload.
3. Compute CRC-32 over the payload (Section 6.4). If its 4 big-endian bytes do not
   equal the provided checksum, the input is corrupt -> error.
4. Return the payload (without the checksum).

### 6.4 CRC-32

The checksum is CRC-32 as defined by ISO 3309 / ITU-T V.42, the same CRC-32 used
by zlib, PNG, and Ethernet (reversed polynomial `0xEDB88320`, initial value all
ones, final XOR all ones). It is computed over the payload bytes only (not the
checksum) and emitted as 4 bytes big-endian (most significant byte first). Any
standard-library CRC-32 produces this value.

## 7. UR and CBOR framing (`ur:sskr/`, `ur:sq-env/`)

The transcribable forms wrap the raw bytes in minimal CBOR, then ByteWords. Two
CBOR shapes are used, and three prefixes.

### 7.1 The two CBOR shapes

- **Untagged byte string**: a CBOR byte string holding the payload. Used for `ur:`
  strings.
- **Tagged byte string**: the same byte string wrapped in CBOR tag `#6.40309`
  (the IANA-registered SSKR tag). Used for standard-ByteWords shares.

CBOR encoding rules needed here (a tiny subset):

- A byte string of length `n` begins with an initial byte of major type 2:
  - `n <= 23`: one byte, `0x40 | n`.
  - `n <= 255`: `0x58`, then `n` as one byte.
  - `n <= 65535`: `0x59`, then `n` as two bytes big-endian.
  Then the `n` payload bytes follow.
- A tag with value `t` begins with major type 6:
  - `t <= 23`: `0xC0 | t`.
  - `t <= 255`: `0xD8`, then `t` as one byte.
  - `t <= 65535`: `0xD9`, then `t` as two bytes big-endian.
  Tag `40309` is `0xD9 0x9D 0x75`.

So a tagged share of a 21-byte serialized share begins `D9 9D 75 55 ...`, where
`0x55` is `0x40 | 21`.

### 7.2 The three prefixes

- `ur:sskr/` + minimal ByteWords of the **untagged** byte string of a serialized
  SSKR share. This is a shard QR's content.
- standard ByteWords of the **tagged** byte string of a serialized SSKR share.
  This is the words printed under a shard QR.
- `ur:sq-env/` + minimal ByteWords of the **untagged** byte string of the `SQKE`
  envelope bytes. ShardQuorum-proprietary; the distinct prefix prevents a generic
  SSKR scanner from misreading an envelope as a share.

### 7.3 Decoding to raw bytes

1. Trim whitespace. Determine the form:
   - starts with `ur:sskr/` (case-insensitive): minimal-decode the part after the
     prefix (Section 6), then read an untagged CBOR byte string -> serialized
     share.
   - starts with `ur:sq-env/`: minimal-decode the remainder, read an untagged CBOR
     byte string -> envelope bytes.
   - otherwise: treat as standard ByteWords, decode (Section 6), then read a CBOR
     tag (must equal `40309`) followed by a byte string -> serialized share.
2. When reading a CBOR byte string, the declared length must exactly match the
   remaining bytes; reject trailing or missing data.

## 8. SSKR share structure: the 5-byte header (BCR-2020-011)

A serialized SSKR share is a 5-byte (40-bit) metadata header followed by the raw
GF(256) share value. Bit layout, big-endian within each byte:

```
  byte 0, 1 : identifier            16 bits   common to every share of one split
  byte 2    : (groupThreshold - 1)   4 bits  (high nibble)
              (groupCount - 1)       4 bits  (low nibble)
  byte 3    : groupIndex             4 bits  (high nibble)
              (memberThreshold - 1)  4 bits  (low nibble)
  byte 4    : reserved (= 0)         4 bits  (high nibble)
              memberIndex            4 bits  (low nibble)
  byte 5..  : share value (the Shamir share bytes)
```

Thresholds and counts are stored as (value - 1), so a stored nibble of `n` means
`n + 1`. Indices are stored directly.

Parsing:

- `identifier = (byte0 << 8) | byte1`
- `groupThreshold = (byte2 >> 4) + 1`
- `groupCount = (byte2 & 0x0f) + 1`
- `groupIndex = byte3 >> 4`
- `memberThreshold = (byte3 & 0x0f) + 1`
- `memberIndex = byte4 & 0x0f`
- The high nibble of byte 4 is reserved and must be zero; reject otherwise.
- `value = bytes[5..]`

For ShardQuorum output, every share has `groupThreshold = groupCount = 1` and
`groupIndex = 0` (single group). `memberThreshold` is the quorum K. All shares of
one secret share the same `identifier`; reject a mix of identifiers.

## 9. GF(256) field arithmetic

Shamir's scheme operates in the finite field GF(2^8), exactly as in SLIP-39 and
Blockchain Commons bc-shamir, so results are byte-for-byte compatible.

- Reducing polynomial: `0x11b`.
- Generator for the log/exp tables: `3` (the element `x + 1`).
- Addition and subtraction are both bitwise XOR.

### 9.1 Build the log and exp tables

```
exp = array of 255 ints
log = array of 256 ints
poly = 1
for i in 0 .. 254:
    exp[i] = poly
    log[poly] = i
    poly = (poly << 1) XOR poly        # multiply running value by generator 3
    if (poly AND 0x100) != 0:
        poly = poly XOR 0x11b          # reduce modulo the field polynomial
# log[0] is undefined and never used; every caller guards the zero case.
```

### 9.2 Multiply, divide

```
add(a, b) = a XOR b
mul(a, b) = 0                          if a == 0 or b == 0
          = exp[(log[a] + log[b]) mod 255]    otherwise
div(a, b):  require b != 0
          = 0                          if a == 0
          = exp[(log[a] - log[b] + 255) mod 255]   otherwise
```

The choice of generator only affects the internal table layout; multiply and
divide results depend solely on the reducing polynomial, so any primitive
generator yields the same field operations.

### 9.3 Lagrange interpolation

Given a set of sample points, each an (x, value-vector) pair where x is a field
element (0..255) and value-vectors are equal-length byte arrays, evaluate the
unique interpolating polynomial at coordinate `x`, operating on each byte position
independently:

```
interpolate(points, x):
    if some point has that exact x: return a copy of its value
    require all value-vectors have equal length L
    require all x-coordinates are distinct
    result = L zero bytes
    for i over points (xi, yi):
        numerator = 1
        denominator = 1
        for j over points, j != i:
            xj = points[j].x
            numerator   = mul(numerator,   x XOR xj)
            denominator = mul(denominator, xi XOR xj)
        coefficient = div(numerator, denominator)
        for k in 0 .. L-1:
            result[k] = result[k] XOR mul(coefficient, yi[k])
    return result
```

## 10. Shamir combination and SLIP-39/SSKR digest verification

The secret is not stored at a single share coordinate. The scheme reserves two
x-coordinates:

- `x = 255` (`SECRET_INDEX`): the secret.
- `x = 254` (`DIGEST_INDEX`): a digest share, formed as a 4-byte HMAC-SHA256
  digest followed by random padding.

Member shares sit at `x = memberIndex` (0..15).

### 10.1 Combine

Inputs: the quorum threshold K (from any share's `memberThreshold`), and a map of
`memberIndex -> share value` for at least K distinct members.

1. Choose exactly K of the supplied shares as the defining set (any K; the rest
   are surplus and checked in step 4). Each contributes the point
   `(memberIndex, value)`.
2. `secret = interpolate(definingPoints, 255)`.
3. `digestShare = interpolate(definingPoints, 254)`.
4. For each surplus share `(x, value)` beyond the defining K: require
   `interpolate(definingPoints, x) == value`. A mismatch means the shares mix
   different splits or one is corrupt; reject.
5. Verify the digest (Section 10.2). If it fails, the shares are wrong,
   insufficient, or corrupt; reject.
6. Return `secret`.

### 10.2 Digest verification

The recovered `digestShare` is laid out as:

```
  digestShare[0..4]  = recoveredDigest   (4 bytes)
  digestShare[4..]   = randomPart        (same length as secret minus 4)
```

Compute `expectedDigest = HMAC-SHA256(key = randomPart, message = secret)`,
truncated to its first 4 bytes. The shares are valid if and only if
`recoveredDigest == expectedDigest`. Use a constant-time comparison. The
false-accept probability of a wrong set is about 2^-32.

This digest check is the cryptographic integrity guarantee. It is independent of,
and stronger than, the ByteWords CRC-32, which only catches transcription typos.

## 11. Direct-mode recovery

In Direct mode the combined output of Section 10 is the secret itself: an even
number of bytes between 16 and 32. There is no envelope. Recovery is complete once
`combine` returns and the digest verifies.

## 12. KEK envelope (`SQKE`) decryption

In encrypted mode the combined output of Section 10 is a 256-bit (32-byte) key,
the KEK. The secret is in the envelope, which must be decrypted with that KEK.

### 12.1 Envelope wire format

```
  offset 0   : magic     4 bytes   ASCII "SQKE" = 53 51 4B 45
  offset 4   : version   1 byte    0x01
  offset 5   : nonce     12 bytes  random per seal (the AES-GCM IV)
  offset 17  : ciphertext n bytes  AES-256-GCM output, INCLUDING the 16-byte tag
```

The header (the first 5 bytes, magic || version) is the GCM associated data
(AAD): it is authenticated but not encrypted.

### 12.2 Decrypt

1. Require at least `5 + 12 + 16 + 1` bytes. Verify the magic equals `"SQKE"` and
   the version equals `0x01`; reject otherwise.
2. `nonce = bytes[5..17]`; `ciphertext = bytes[17..]` (this includes the trailing
   16-byte authentication tag, per the standard GCM convention).
3. Decrypt with AES-256-GCM: key = the 32-byte KEK, IV/nonce = `nonce`,
   additional authenticated data = the 5-byte header, tag length = 128 bits.
4. If authentication fails, the KEK is wrong (wrong or insufficient shares) or the
   envelope was tampered with or corrupted; reject. Otherwise the plaintext is the
   secret.

Any standard AES-256-GCM implementation (for example the platform crypto library,
or a browser's Web Crypto `AES-GCM`) interoperates, since `SQKE` is just a header
plus standard GCM. Note GCM expects the tag appended to the ciphertext; some
libraries take the tag as a separate argument, in which case split off the last 16
bytes as the tag.

## 13. End-to-end recovery procedures

### 13.1 Parse inputs

For each input string, apply Section 7.3. Standard ByteWords and `ur:sskr/` yield
a serialized share; parse its header (Section 8). `ur:sq-env/` yields the envelope
bytes.

### 13.2 Group and check

- All shares must share one `identifier` and be single-group (`groupCount = 1`).
  Reject mixes.
- The quorum K is the shares' common `memberThreshold`.
- Deduplicate by `memberIndex`. You need at least K distinct member indices.

### 13.3 Combine and finish

1. Build the map `memberIndex -> value` and run Section 10 to get the combined
   output.
2. Decide the mode:
   - If a `ur:sq-env/` envelope was provided, this is KEK mode: the combined
     output is the 32-byte KEK; decrypt the envelope (Section 12) to get the
     secret.
   - Otherwise this is Direct mode: the combined output is the secret
     (Section 11).
3. Caveat on ambiguity: a combined output of exactly 32 bytes with no envelope
   present may be either a 32-byte Direct secret or a KEK whose envelope is
   missing. A recovery tool should surface this case ("this may be an encrypted
   secret's key; if you used a recovery envelope, add it") rather than silently
   presenting the KEK as the secret.

## 14. Test vectors (the definition of done)

The canonical vectors are in `docs/recovery-vectors.json`, generated from the
reference implementation and guarded against drift by an automated test. An
implementation is correct if and only if it reproduces every value there.

The file contains, for each vector:

- `name`, `mode` (`direct` or `kek`), `threshold` (K), `shareCount` (N).
- `secretHex` (and `secretUtf8` for text secrets): the expected recovered secret.
- For `kek` vectors: `combinedKekHex` (the intermediate KEK from combining the
  shares) and `envelope` (`rawHex` and the `ur:sq-env/` form).
- `shares[]`, each with: `memberIndex`, `serializedHex` (the bytes after
  ByteWords/UR decoding, i.e. header + value), the parsed `header` fields, the
  `ur:sskr/` form, and the `standardBytewords` form.

The intermediate values let an implementation localize a failure: if
`standardBytewords` does not decode to `serializedHex`, the bug is in Section 6/7;
if the combine does not reach `secretHex` (Direct) or `combinedKekHex` (KEK), it
is in Section 9/10; if the envelope will not open, it is in Section 12.

Minimum self-test: for every vector, take any K shares, recover, and check the
result equals `secretHex`. For KEK vectors, also check the combine equals
`combinedKekHex` before opening the envelope.

## 15. Worked example

From the `direct-2of3-16byte` vector (`docs/recovery-vectors.json`). It is a
2-of-3 Direct split; any two shares recover it. We use members 0 and 1.

Expected secret (hex): `00112233445566778899aabbccddeeff`.

Member 0, as standard ByteWords:

```
tuna next keep gyro logo webs able acid able code tiny view rust twin void zinc
real puma aunt foxy jade echo toil acid taxi wasp vial down numb
```

Member 1, as the QR form:

```
ur:sskr/golowsaeadadhgkgjpgydndnhnjyfrynlfjkeyecuysomymhdwst
```

Step 1, decode to bytes (Section 6, 7).

- Member 0 is standard ByteWords: decode the words to bytes, verify the trailing
  CRC-32, then read CBOR tag `40309` and a byte string. Result (serialized share):
  `88ef0001001cd4e5c0dae7fdb7b107446b33d501d0`.
- Member 1 is `ur:sskr/`: minimal-decode the body, verify CRC-32, read an untagged
  CBOR byte string. Result:
  `88ef000101577b72512b2b60743bf682733235dbc9`.

Step 2, parse headers (Section 8). Both share `identifier = 0x88ef`,
`groupThreshold = groupCount = 1`, `groupIndex = 0`, `memberThreshold = 2`. So
K = 2.

- Member 0: `memberIndex = 0`, value `1cd4e5c0dae7fdb7b107446b33d501d0`.
- Member 1: `memberIndex = 1`, value `577b72512b2b60743bf682733235dbc9`.

Step 3, combine (Section 9, 10). With K = 2 the points are
`(0, value0)` and `(1, value1)`. Interpolate at `x = 255` for the secret and at
`x = 254` for the digest share, per byte, using GF(256) arithmetic.

Step 4, verify the digest (Section 10.2): split the digest share into the 4-byte
recovered digest and the random remainder, recompute
`HMAC-SHA256(key = remainder, message = secret)[0..4]`, and confirm equality.

Result: the secret `00112233445566778899aabbccddeeff`. Because this is Direct mode
(no envelope), that is the final secret. A correct implementation reaches exactly
this value; if it does not, compare each intermediate above against the vector to
find the failing stage.

## 16. Format identifiers and versioning

- This specification: version 1.
- `SQKE` envelope: version byte `0x01`. A future version changes this byte; a
  recovery tool should reject versions it does not implement rather than guess.
- UR types: `sskr` (shares, interoperable, IANA CBOR tag 40309) and `sq-env`
  (ShardQuorum-proprietary envelope, no IANA tag).
- ShardQuorum produces single-group SSKR only (`groupThreshold = groupCount = 1`).

Format changes are signaled by these identifiers, and the canonical vectors
(Section 14) are regenerated alongside any change, so a tool that passes the
current vectors matches the current format.

## 17. Glossary

- **Shard / share**: one distributed piece. Any K of N reconstruct the secret.
- **Quorum (K)**: the minimum number of shards needed (the `memberThreshold`).
- **Count (N)**: how many shards exist.
- **Secret**: the protected value. In Direct mode it is split directly; in KEK
  mode it lives in the envelope.
- **KEK**: key-encrypting key. A random 256-bit key that is split via SSKR and
  used to decrypt the envelope.
- **Envelope (`SQKE`)**: the AES-256-GCM ciphertext of the secret under the KEK.
- **Digest share**: the share at x = 254 carrying a 4-byte HMAC-SHA256 over the
  secret, used to verify a correct reconstruction.
- **ByteWords**: the four-letter-word byte encoding (BCR-2020-012).
- **UR**: Uniform Resource, the `ur:type/...` text form (here for QR content).
- **SSKR**: Sharded Secret Key Reconstruction (BCR-2020-011).
- **AAD**: additional authenticated data; bytes a GCM cipher authenticates but
  does not encrypt (here, the envelope header).

## 18. References, provenance, and license

- SSKR: Blockchain Commons BCR-2020-011 (vendored at
  `docs/reference/bcr-2020-011-sskr.md`).
- ByteWords: Blockchain Commons BCR-2020-012 (vendored at
  `docs/reference/bcr-2020-012-bytewords.md`).
- Shamir / GF(256) digest scheme: as used by SLIP-39.
- The `SQKE` envelope and the `ur:sq-env/` type are ShardQuorum-specific and are
  fully specified in Section 12; they are not part of any external standard.

ShardQuorum is a clean-room implementation of the public BCR specifications.
License: GPL-3.0-only; Blockchain Commons material is under the
BSD-2-Clause-Plus-Patent license. See `NOTICE` and `docs/reference/PROVENANCE.md`.
