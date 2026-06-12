# Reference specification provenance

These are copies of the Blockchain Commons research papers that ShardQuorum implements, kept in-repo so the implementation can be audited against a pinned copy of the spec (and so test vectors have a traceable origin).

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
