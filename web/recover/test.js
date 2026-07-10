/*
 * Parity test: drives web/recover/core.js against docs/recovery-vectors.json,
 * the same fixture the Kotlin implementation is checked against. Proves the pure
 * JS recovery is byte-for-byte compatible. Run: node web/recover/test.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const SQ = require('./core.js');

const repoRoot = path.resolve(__dirname, '..', '..');
const vectors = JSON.parse(fs.readFileSync(path.join(repoRoot, 'docs', 'recovery-vectors.json'), 'utf8'));

let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; } else { failed++; console.error('FAIL: ' + name + (detail ? ' - ' + detail : '')); }
}

vectors.vectors.forEach(function (vec) {
  // 1. Each transcribable form decodes to the serialized share bytes.
  vec.shares.forEach(function (sh) {
    try {
      const fromUr = SQ.parseInput(sh.ur).bytes;
      const fromWords = SQ.parseInput(sh.standardBytewords).bytes;
      check(vec.name + ' [' + sh.memberIndex + '] ur decode', SQ.toHex(fromUr) === sh.serializedHex, SQ.toHex(fromUr));
      check(vec.name + ' [' + sh.memberIndex + '] words decode', SQ.toHex(fromWords) === sh.serializedHex, SQ.toHex(fromWords));
    } catch (e) {
      check(vec.name + ' [' + sh.memberIndex + '] decode threw', false, e.message);
    }
  });

  // 2. Recover from exactly a quorum (the first K shares), via the UR forms.
  const quorumUr = vec.shares.slice(0, vec.threshold).map(function (s) { return s.ur; });
  try {
    const inputs = quorumUr.slice();
    if (vec.mode === 'kek') inputs.push(vec.envelope.ur);
    const res = SQ.recover(inputs);
    check(vec.name + ' recovers secret', SQ.toHex(res.secret) === vec.secretHex, SQ.toHex(res.secret));

    // 3. For KEK vectors, the intermediate combine must equal the KEK.
    if (vec.mode === 'kek') {
      const kek = SQ.combineShares(quorumUr.map(function (u) { return SQ.parseInput(u).bytes; }));
      check(vec.name + ' combines to KEK', SQ.toHex(kek) === vec.combinedKekHex, SQ.toHex(kek));
    }
  } catch (e) {
    check(vec.name + ' recovery threw', false, e.message);
  }

  // 4. Recover from a different quorum subset (the LAST K shares) to confirm
  //    any quorum works, and standard ByteWords path too.
  const lastWords = vec.shares.slice(vec.shares.length - vec.threshold).map(function (s) { return s.standardBytewords; });
  try {
    const inputs = lastWords.slice();
    if (vec.mode === 'kek') inputs.push(vec.envelope.ur);
    const res = SQ.recover(inputs);
    check(vec.name + ' recovers from alternate quorum (words)', SQ.toHex(res.secret) === vec.secretHex, SQ.toHex(res.secret));
  } catch (e) {
    check(vec.name + ' alternate-quorum recovery threw', false, e.message);
  }
});

// 5. Tamper detection: a corrupted envelope must fail authentication.
const kekVec = vectors.vectors.find(function (v) { return v.mode === 'kek'; });
if (kekVec) {
  const env = SQ.fromHex(kekVec.envelope.rawHex);
  env[env.length - 1] ^= 0x01;
  // Re-encode tampered envelope is awkward; call openEnvelope directly with the KEK.
  const kek = SQ.fromHex(kekVec.combinedKekHex);
  let threw = false;
  try { SQ.openEnvelope(kek, env); } catch (e) { threw = true; }
  check('tampered envelope rejected', threw, 'expected authentication failure');
}

// 6. Wordlist parity with the spec/impl (sanity).
check('wordlist length 256', SQ.wordList().length === 256);

// 7. Input validation: clean quorum, cross-split shards, duplicate envelopes,
//    unparseable lines, and the errors recover() surfaces from them.
(function () {
  const vecA = vectors.vectors[0];
  const vecB = vectors.vectors.find(function (v) {
    return v.shares[0].serializedHex.slice(0, 4) !== vecA.shares[0].serializedHex.slice(0, 4);
  });
  const quorumA = vecA.shares.slice(0, vecA.threshold).map(function (s) { return s.ur; });

  let v = SQ.validateInputs(quorumA);
  check('validateInputs accepts a clean quorum',
    v.errors.length === 0 && v.shares.length === vecA.threshold);

  check('validateInputs reports empty input',
    SQ.validateInputs([]).errors.some(function (m) { return m.indexOf('No valid shards') !== -1; }));

  if (vecB) {
    v = SQ.validateInputs(quorumA.concat([vecB.shares[0].ur]));
    check('validateInputs flags cross-split shards',
      v.errors.some(function (m) { return m.indexOf('DIFFERENT secret') !== -1; }));
  } else {
    check('cross-split fixture available (need two vectors with distinct identifiers)', false);
  }

  if (kekVec) {
    v = SQ.validateInputs(quorumA.concat([kekVec.envelope.ur, kekVec.envelope.ur]));
    check('validateInputs flags duplicate envelopes',
      v.errors.some(function (m) { return m.indexOf('More than one envelope') !== -1; }));
  }

  v = SQ.validateInputs(['tuna next keep gibberish words that decode to nothing']);
  check('validateInputs keeps the specific parse error for bad lines',
    v.errors.some(function (m) { return m.indexOf('Not a recognized shard or envelope') !== -1; }));

  let threw = null;
  try { SQ.recover(['tuna next keep gibberish words that decode to nothing']); }
  catch (e) { threw = e.message; }
  check('recover surfaces the specific parse error, not a generic one',
    threw !== null && threw.indexOf('Not a recognized shard or envelope') !== -1);

  threw = null;
  try { SQ.recover(vecB ? quorumA.concat([vecB.shares[0].ur]) : quorumA.concat([])); }
  catch (e) { threw = e.message; }
  if (vecB) {
    check('recover surfaces the friendly cross-split error',
      threw !== null && threw.indexOf('DIFFERENT secret') !== -1);
  }
})();

console.log('\n' + passed + ' checks passed, ' + failed + ' failed.');
process.exit(failed ? 1 : 0);
