/*
 * Verifies the built, self-contained ShardQuorum-recover.html end to end:
 *  - the embedded vectors match docs/recovery-vectors.json byte for byte;
 *  - the INLINED core script runs in a browser-like context (no Node 'module');
 *  - every embedded vector recovers to its expected secret through that core.
 * Run: node web/recover/verify-built.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '..', '..');
const html = fs.readFileSync(path.join(repoRoot, 'ShardQuorum-recover.html'), 'utf8');
const docsVectors = fs.readFileSync(path.join(repoRoot, 'docs', 'recovery-vectors.json'), 'utf8').trim();

let failed = 0;
function check(name, cond, detail) {
  if (cond) console.log('ok   - ' + name);
  else { failed++; console.error('FAIL - ' + name + (detail ? ': ' + detail : '')); }
}

// No external references / leftover markers.
check('no external references', !/\b(?:src|href)\s*=|https?:\/\//i.test(html));
check('no leftover markers', !/__CORE_JS__|__UI_JS__|__VECTORS_JSON__/.test(html));

// Embedded vectors match the committed file.
const vecMatch = html.match(/<script id="sq-vectors"[^>]*>([\s\S]*?)<\/script>/);
check('embedded vectors block present', !!vecMatch);
const embedded = vecMatch[1].trim();
check('embedded vectors match docs/recovery-vectors.json', embedded === docsVectors);

// Extract the core script: the bare <script> block defining ShardQuorumCore.
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/gi)].map(function (m) { return m[1]; });
const coreSrc = scripts.find(function (s) { return s.indexOf('ShardQuorumCore') !== -1; });
check('inlined core script present', !!coreSrc);

// Run it in a browser-like sandbox (no 'module', so it attaches to globalThis).
const sandbox = {};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(coreSrc, sandbox);
const SQ = sandbox.ShardQuorumCore;
check('core attached to global (browser path)', !!SQ && typeof SQ.recover === 'function');

// Drive every embedded vector through the inlined core.
const vectors = JSON.parse(embedded);
vectors.vectors.forEach(function (vec) {
  try {
    const inputs = vec.shares.slice(0, vec.threshold).map(function (s) { return s.ur; });
    if (vec.mode === 'kek') inputs.push(vec.envelope.ur);
    const res = SQ.recover(inputs);
    check('built core recovers ' + vec.name, SQ.toHex(res.secret) === vec.secretHex, SQ.toHex(res.secret));
  } catch (e) {
    check('built core recovers ' + vec.name, false, e.message);
  }
});

console.log('\n' + (failed ? failed + ' checks FAILED' : 'all checks passed'));
process.exit(failed ? 1 : 0);
