/*
 * Assembles the ShardQuorum recovery bundle: the durable set of artifacts that
 * lets a future holder of a quorum of shards recover the secret, verify the
 * tools are authentic, and reimplement from scratch if nothing runs.
 *
 * Output: build/recovery-bundle/ plus a ROOT value to store WITH the shards.
 *
 * Integrity model (see README.txt in the bundle):
 *   MANIFEST.txt lists "sha256  filename" for every bundle file (coreutils
 *   format, so `sha256sum -c MANIFEST.txt` verifies them). The ROOT is
 *   sha256(MANIFEST.txt): one short value, stored with the shards, from which
 *   the whole bundle can be verified even when fetched from untrusted storage.
 *
 * Deterministic: ROOT is reproducible from the repo (the optional APK is the
 * only non-reproducible input and is excluded unless explicitly provided).
 *
 * Run: node tools/build-bundle.js [path-to-apk]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const SQ = require('../web/recover/core.js');

const repoRoot = path.resolve(__dirname, '..');
const outDir = path.join(repoRoot, 'build', 'recovery-bundle');

function sha256Hex(buf) { return crypto.createHash('sha256').update(buf).digest('hex'); }

// Standard ByteWords encoding of arbitrary bytes (payload + CRC-32), reusing the
// single wordlist/CRC in core.js so there is no extra copy to keep in sync.
function bytewordsStandard(bytes) {
  const crc = SQ.crc32(bytes);
  const full = new Uint8Array(bytes.length + 4);
  full.set(bytes, 0);
  full[bytes.length] = crc >>> 24;
  full[bytes.length + 1] = (crc >>> 16) & 0xff;
  full[bytes.length + 2] = (crc >>> 8) & 0xff;
  full[bytes.length + 3] = crc & 0xff;
  const words = SQ.wordList();
  return Array.from(full).map(function (b) { return words[b]; }).join(' ');
}

const README = [
  'ShardQuorum recovery bundle',
  '===========================',
  '',
  'This bundle lets anyone holding a quorum (the minimum number) of ShardQuorum',
  'shards recover the protected secret. It is designed to outlive the app: as long',
  'as this bundle survives and can be verified, the secret is recoverable.',
  '',
  'FILES',
  '  ShardQuorum-recover.html  Offline recovery tool. Open in any web browser.',
  '  RECOVERY-SPEC.md          Complete recovery specification (human- or LLM-readable).',
  '  recovery-vectors.json     Known-answer test vectors (the definition of correct).',
  '  MANIFEST.txt              SHA-256 of each file above.',
  '  README.txt                This file.',
  '',
  'STEP 1: VERIFY THIS BUNDLE IS AUTHENTIC',
  '  You should have kept a short ROOT value together with your shards (a 64-',
  '  character hex string, or its ByteWords form). Verify before trusting anything',
  '  here:',
  '',
  '    a) Check the manifest is genuine:',
  '         sha256sum MANIFEST.txt',
  '       Compare the result to your stored ROOT. If it does not match, STOP and',
  '       obtain another copy of the bundle.',
  '',
  '    b) Check every file matches the manifest:',
  '         sha256sum -c MANIFEST.txt',
  '       Every line must say OK.',
  '',
  'STEP 2: WORK OFFLINE',
  '  Disconnect from all networks before handling shards. This tool sends nothing,',
  '  but an offline device is the safe choice. Never paste shards or the recovered',
  '  secret into any website, chatbot, or online service.',
  '',
  'STEP 3: RECOVER',
  '  Open ShardQuorum-recover.html in a web browser. It self-tests on load. Paste',
  '  at least a quorum of your shards (one per line), add the recovery envelope',
  '  stored with your shards, and press Recover.',
  '',
  'IF THE TOOL WILL NOT RUN (years from now, a future browser):',
  '  RECOVERY-SPEC.md is a complete, self-contained specification. Give it to a',
  '  competent programmer, or to a large language model, to build a fresh recovery',
  '  tool in any language (see Section 3 of the spec for the exact procedure and a',
  '  ready-made prompt). Verify any such tool against recovery-vectors.json before',
  '  trusting it with real shards. The secret was never locked to one program, only',
  '  to public math plus the documented envelope format.',
  ''
].join('\n');

function main() {
  const recoverHtml = path.join(repoRoot, 'ShardQuorum-recover.html');
  if (!fs.existsSync(recoverHtml)) {
    throw new Error('ShardQuorum-recover.html not found; run `node web/recover/build.js` first');
  }

  // name-in-bundle -> source path. APK is optional and, being non-reproducible,
  // changes the ROOT when included.
  const sources = [
    ['ShardQuorum-recover.html', recoverHtml],
    ['RECOVERY-SPEC.md', path.join(repoRoot, 'docs', 'RECOVERY-SPEC.md')],
    ['recovery-vectors.json', path.join(repoRoot, 'docs', 'recovery-vectors.json')]
  ];
  const apkArg = process.argv[2];
  if (apkArg) sources.push(['ShardQuorum.apk', path.resolve(apkArg)]);

  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });

  // README is itself a bundle file (and is covered by the manifest).
  fs.writeFileSync(path.join(outDir, 'README.txt'), README);

  const files = [['README.txt', path.join(outDir, 'README.txt')]];
  sources.forEach(function (entry) {
    const dest = path.join(outDir, entry[0]);
    fs.copyFileSync(entry[1], dest);
    files.push([entry[0], dest]);
  });

  // MANIFEST: coreutils "sha256  name" lines, sorted by name for determinism.
  const manifestLines = files
    .map(function (f) { return sha256Hex(fs.readFileSync(f[1])) + '  ' + f[0]; })
    .sort();
  const manifest = manifestLines.join('\n') + '\n';
  fs.writeFileSync(path.join(outDir, 'MANIFEST.txt'), manifest);

  // ROOT = sha256(MANIFEST.txt). Stored WITH the shards, not inside the bundle.
  const rootHex = sha256Hex(Buffer.from(manifest));
  const rootWords = bytewordsStandard(SQ.fromHex(rootHex));
  const rootText = [
    'ShardQuorum recovery bundle ROOT',
    '================================',
    '',
    'Store this value WITH your shards (it is not part of the verified bundle).',
    'It lets a future user confirm the bundle (and thus the recovery tool) is',
    'authentic: sha256sum MANIFEST.txt must equal the hex value below.',
    '',
    'ROOT (hex):',
    rootHex,
    '',
    'ROOT (ByteWords, same value, with a built-in checksum):',
    rootWords,
    ''
  ].join('\n');
  fs.writeFileSync(path.join(repoRoot, 'build', 'recovery-bundle-ROOT.txt'), rootText);

  console.log('Bundle:    ' + outDir);
  console.log('Files:     ' + files.map(function (f) { return f[0]; }).sort().join(', '));
  console.log('ROOT hex:  ' + rootHex);
  console.log('ROOT words: ' + rootWords);
  console.log('ROOT file: ' + path.join(repoRoot, 'build', 'recovery-bundle-ROOT.txt'));
  if (!apkArg) console.log('(APK not included; ROOT is reproducible from the repo.)');
}

main();
