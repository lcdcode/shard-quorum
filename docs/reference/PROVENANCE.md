# Reference specification provenance

These are copies of the Blockchain Commons research papers that ShardQuorum implements, kept in-repo so the implementation can be audited against a pinned copy of the spec (and so test vectors have a traceable origin).

## Vendored code: Nayuki QR Code generator (`:qrcodegen` module)

The `qrcodegen/` module vendors Project Nayuki's QR Code generator library
(Java), copied VERBATIM - original package name `io.nayuki.qrcodegen`, no
modifications - so it can be diffed against upstream at any time. Vendored
instead of consumed from Maven to remove the supply-chain dependency for
correctness-critical output code.

- Upstream: https://github.com/nayuki/QR-Code-generator
- Project page: https://www.nayuki.io/page/qr-code-generator-library
- Pinned release: tag `v1.8.0`, commit `720f62bddb7226106071d4728c292cb1df519ceb`
- Fetched: 2026-06-12 from
  `https://raw.githubusercontent.com/nayuki/QR-Code-generator/v1.8.0/java/src/main/java/io/nayuki/qrcodegen/`
- License: MIT (full text embedded in each source file header; upstream ships
  no separate LICENSE file)
- Deliberately EXCLUDED: `QrSegmentAdvanced.java` and its `Memoizer.java`
  helper (kanji mode and optimal segment switching are unused; smaller audit
  surface)

SHA-256 of the vendored files as fetched:

| File | SHA-256 |
|------|---------|
| `QrCode.java` | `ab2d9f05bef0c2bedfaa888256f09987b2dbef5a4f432c540b9e549801c030ec` |
| `QrSegment.java` | `70f10e518d3a8f1a1862e598a249abaac036a9c6fae4e5892c4b6d24995bb8d7` |
| `BitBuffer.java` | `d5496452b435423beead30aa356e10a7c0ba4790b7da37ee4a1b80b4df136447` |
| `DataTooLongException.java` | `7661186dde4b27334fd94f4950f58522eaab256274388152e5cb8e35ef463e1a` |

| File | Source | Fetched |
|------|--------|---------|
| `bcr-2020-012-bytewords.md` | https://raw.githubusercontent.com/BlockchainCommons/Research/master/papers/bcr-2020-012-bytewords.md | 2026-06-12 |
| `bcr-2020-011-sskr.md` | https://raw.githubusercontent.com/BlockchainCommons/Research/master/papers/bcr-2020-011-sskr.md | 2026-06-12 |

Blockchain Commons research papers are published under BSD-2-Clause-Plus-Patent.

## Test vectors extracted from these specs

- **Bytewords (standard CBOR seed)** — body `d99d6ca20150c7098580125e2ab0981253468b2dbc5202c11947da`,
  CRC32 `c904f40b`, encodes to `tuna next jazz oboe ...`.
- **Bytewords (brutal)** — payload `c7098580125e2ab0981253468b2dbc52`, CRC32 `feac0dea`,
  minimal `staslplabghydrpfmkbggufgludprfgmzepsbtwd`.
- **SSKR share** — `4bbf1101025abd490ee65b6084859854ee67736e75` decodes to
  identifier `0x4bbf`, groupThreshold 2, groupCount 2, groupIndex 0,
  memberThreshold 2, memberIndex 2. CBOR-tagged (#6.40309) standard bytewords:
  `tuna next keep gyro gear runs body acid also heat ruby gala ... ruby purr`.
