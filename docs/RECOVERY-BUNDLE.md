# ShardQuorum recovery bundle

The recovery bundle is the layered set of artifacts that keeps a secret
recoverable beyond the lifetime of the Android app. It exists because an APK may
not run on a future device, whereas a self-contained HTML file, a plain-text
specification, and known-answer vectors will.

## Artifacts

- `ShardQuorum-recover.html` - self-contained, offline, pure-JS recovery tool.
  Built from `web/recover/` via `node web/recover/build.js`.
- `docs/RECOVERY-SPEC.md` - the complete, language-agnostic recovery spec. A
  competent programmer or LLM can build a fresh tool from it (Section 3).
- `docs/recovery-vectors.json` - known-answer vectors; the definition of correct.
- The assembled bundle (`build/recovery-bundle/`), produced by
  `node tools/build-bundle.js`: a `README.txt` plus the three artifacts above and
  a `MANIFEST.txt` listing each file's SHA-256.

## The ROOT, and the one manual step

`MANIFEST.txt` lists the SHA-256 of every bundle file. The bundle ROOT is
`sha256(MANIFEST.txt)`: a single short value (emitted to
`build/recovery-bundle-ROOT.txt` in hex and ByteWords) from which the entire
bundle can be verified, even when fetched from untrusted storage.

The ROOT is per-release, not per-secret: it is a hash of the tool, spec, and
vectors, identical for everyone on a given version and unrelated to the secret.

There is exactly one manual step, which the app does NOT perform for you:

> When you create shards, store the current release's ROOT together with them
> (write it on the shard cards, or keep a copy alongside). Later it lets you prove
> the recovery bundle you retrieve is authentic before you trust it.

Without the ROOT you can still recover (the spec and tool are public), but you
lose the ability to detect a tampered copy of the bundle. Storing it is therefore
recommended, not required.

This linkage is deliberately manual (see the design discussion: the alternative,
printing the ROOT from inside the app, couples the app version to the bundle
version and is left as a possible future feature).

## Building and releasing

```
node web/recover/build.js          # build the recovery tool
node web/recover/test.js           # parity-test it against the vectors
node web/recover/verify-built.js   # verify the built artifact
node tools/build-bundle.js         # assemble bundle + compute ROOT
```

At release time:

1. Build and verify as above.
2. Publish `build/recovery-bundle/` as a release asset.
3. Record the ROOT (from `build/recovery-bundle-ROOT.txt`) in the release notes.
4. Archive each released bundle, keyed by its ROOT. A ROOT stored with someone's
   shards pins them to that exact bundle version, so that version must remain
   retrievable.

The ROOT is reproducible from the repository; the optional APK is the only
non-deterministic input and is excluded unless passed to `build-bundle.js`.

## See also

- `docs/RECOVERY-SPEC.md` - the specification and the LLM-build procedure.
- `web/recover/README.md` - the recovery tool sources and workflow.
