/*
 * Builds the self-contained ShardQuorum-recover.html from the sources in this
 * directory plus docs/recovery-vectors.json. Inlines everything; the output has
 * no external references. Run: node web/recover/build.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const repoRoot = path.resolve(dir, '..', '..');

const template = fs.readFileSync(path.join(dir, 'template.html'), 'utf8');
const coreJs = fs.readFileSync(path.join(dir, 'core.js'), 'utf8');
const uiJs = fs.readFileSync(path.join(dir, 'ui.js'), 'utf8');
const vectorsText = fs.readFileSync(path.join(repoRoot, 'docs', 'recovery-vectors.json'), 'utf8').trim();

// split/join (not replace) so '$' in the sources is never treated specially.
let html = template
  .split('//__CORE_JS__').join(coreJs)
  .split('//__UI_JS__').join(uiJs)
  .split('__VECTORS_JSON__').join(vectorsText);

['__CORE_JS__', '__UI_JS__', '__VECTORS_JSON__'].forEach(function (marker) {
  if (html.indexOf(marker) !== -1) throw new Error('unsubstituted marker remains: ' + marker);
});

// The artifact must reference nothing off-device.
const offending = html.match(/\b(?:src|href)\s*=|https?:\/\//gi);
if (offending) throw new Error('external reference found in output: ' + offending.join(', '));

const outPath = path.join(repoRoot, 'ShardQuorum-recover.html');
fs.writeFileSync(outPath, html);
console.log('Wrote ' + outPath + ' (' + html.length + ' bytes)');
