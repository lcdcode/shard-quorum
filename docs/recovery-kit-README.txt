ShardQuorum recovery kit: '{secret_name}' - Shard {index} of {count}
==================================================================================

This package holds one ShardQuorum shard together with everything needed to
rebuild the protected secret once enough shards are brought together. It is
designed to outlive the app: as long as this kit survives, the secret is
recoverable, even if the ShardQuorum app no longer runs on any device.

The ShardQuorum app is the best way to recover the secret named above, however
if it is not available anymore, this kit gives you more options. Just follow
the 2 steps below

FILES
  shard-{index}-of-{count}.png          This kit's shard and the recovery envelope, as QR
                            codes on one sheet.
  shard-{index}-of-{count}.txt          The same shard as words you can type by hand, plus
                            the QR contents and the recovery envelope as text.
  ShardQuorum-recover.html  Offline recovery tool. Open in any web browser.
  RECOVERY-SPEC.md          Complete recovery specification (human- or 
                            LLM-readable).
  recovery-vectors.json     Known-answer vectors - tests to use to validate a
                            recovery tool built using the specification.
  README.txt                This file.

This kit contains only ONE shard. Recovery needs a quorum: the minimum number
of different shards, gathered from the people or places they were given to. The
shard files above (shard-*.png / shard-*.txt) are this kit's single contribution.

STEP 1: PREFERABLY WORK OFFLINE
  Disconnect from all networks before handling shards. The recovery tool sends
  nothing, but an offline device is the safest choice. NEVER paste shards or the
  recovered secret into any website, chatbot, or online service. Don't give them
  to anyone you do not trust.

STEP 2: RECOVER
  Open ShardQuorum-recover.html in a web browser. It self-tests on load. Paste
  at least a quorum of your shards (one per line, from the shard-*.txt files of
  the different kits), add the recovery envelope (it is on the last line of every
  kit's shard-*.txt file), and press Recover.

IF THE TOOL WILL NOT RUN (years from now, in a future browser):
  RECOVERY-SPEC.md is a complete, self-contained specification. Give it to a
  competent programmer, or to a large language model, to build a fresh recovery
  tool in any coding language (see Section 3 of the spec for the exact procedure
  and a ready-made prompt). Verify any such tool against recovery-vectors.json
  before trusting it with real shards. The secret was never locked to one program,
  only to public math plus the documented envelope format.
