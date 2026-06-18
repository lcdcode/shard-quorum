# ShardQuorum web recovery tool

Sources for the self-contained `ShardQuorum-recover.html` (written to the repo
root). The HTML is a pure-JS, offline, network-free implementation of the
recovery path specified in `docs/RECOVERY-SPEC.md`. It is one implementation of
that spec; the spec plus `docs/recovery-vectors.json` remain the root artifact.

## Files

- `core.js` - DOM-free recovery logic (ByteWords/CRC-32, UR/CBOR, SSKR header,
  GF(256) Shamir + HMAC digest, AES-256-GCM `SQKE` envelope). All crypto is
  hand-implemented from public standards; no Web Crypto, so the artifact depends
  only on a plain JS engine. Loads in Node and the browser.
- `ui.js` - DOM wiring and the in-page self-test.
- `template.html` - shell with `//__CORE_JS__`, `//__UI_JS__`, `__VECTORS_JSON__`
  placeholders.
- `build.js` - inlines the sources and the committed vectors into the single
  self-contained file; fails if any marker or external reference remains.
- `test.js` - parity test of `core.js` against `docs/recovery-vectors.json`.
- `verify-built.js` - verifies the built HTML end to end (embedded vectors match
  the committed file; the inlined core runs in a browser-like context and
  recovers every vector).

## Workflow

```
node web/recover/test.js          # prove core.js parity against the vectors
node web/recover/build.js         # (re)build ShardQuorum-recover.html
node web/recover/verify-built.js  # verify the built artifact
```

Regenerate after changing any source here, or after the vectors change
(`./gradlew :sskr:test -PupdateVectors`).

## Scope and status

Recovery only; there is deliberately no generation path. The core logic is
validated against the shared vectors. The browser DOM layer (`ui.js`) is thin but
is not exercised by the Node tests; open the built file in a browser to confirm
the interface.
