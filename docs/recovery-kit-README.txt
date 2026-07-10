ShardQuorum recovery kit
========================

This package holds one ShardQuorum shard together with everything needed to
rebuild the protected secret once enough shards are brought together. It is
designed to outlive the app: as long as this kit survives, the secret is
recoverable, even if the ShardQuorum app no longer runs on any device.

FILES
  shard-*.png               This kit's shard and the recovery envelope, as QR
                            codes on one sheet.
  shard-*.txt               The same shard as words you can type by hand, plus
                            the QR contents and the recovery envelope as text.
  ShardQuorum-recover.html  Offline recovery tool. Open in any web browser.
  RECOVERY-SPEC.md          Complete recovery specification (human- or LLM-readable).
  recovery-vectors.json     Known-answer test vectors (the definition of correct).
  README.txt                This file.

This kit contains only ONE shard. Recovery needs a quorum: the minimum number
of different shards, gathered from the people or places they were given to. The
shard files above (shard-*.png / shard-*.txt) are this kit's single contribution.

STEP 1: WORK OFFLINE
  Disconnect from all networks before handling shards. The recovery tool sends
  nothing, but an offline device is the safe choice. Never paste shards or the
  recovered secret into any website, chatbot, or online service.

STEP 2: RECOVER
  Open ShardQuorum-recover.html in a web browser. It self-tests on load. Paste
  at least a quorum of your shards (one per line, from the shard-*.txt files of
  the different kits), add the recovery envelope (it is in every kit's
  shard-*.txt and on every QR sheet), and press Recover.

IF THE TOOL WILL NOT RUN (years from now, a future browser):
  RECOVERY-SPEC.md is a complete, self-contained specification. Give it to a
  competent programmer, or to a large language model, to build a fresh recovery
  tool in any language (see Section 3 of the spec for the exact procedure and a
  ready-made prompt). Verify any such tool against recovery-vectors.json before
  trusting it with real shards. The secret was never locked to one program, only
  to public math plus the documented envelope format.
