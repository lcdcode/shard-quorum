/*
 * ShardQuorum recovery UI. Wires the DOM to ShardQuorumCore. No network.
 */
(function () {
  'use strict';
  var SQ = window.ShardQuorumCore;
  function $(id) { return document.getElementById(id); }
  function show(el, text, cls) { el.textContent = text; el.className = 'out' + (cls ? ' ' + cls : ''); }

  function tryUtf8(bytes) {
    try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
    catch (e) { return null; }
  }

  function shardLines(text) {
    return text.split(/\r?\n/).map(function (s) { return s.trim(); }).filter(function (s) { return s.length; });
  }

  function onRecover() {
    var out = $('output');
    try {
      var inputs = shardLines($('shards').value);
      var env = $('envelope').value.trim();
      if (env) inputs.push(env);

      // recover() validates internally (cross-split shards, duplicate
      // envelopes, per-line parse errors) and throws the full list at once.
      var res = SQ.recover(inputs);
      var hex = SQ.toHex(res.secret);
      var text = tryUtf8(res.secret);
      var lines = [res.mode === 'kek' ? 'Recovered secret:'
        : 'Recovered (no envelope; combined shards only):', ''];
      if (text !== null) lines.push('Text:  ' + text);
      lines.push('Hex:   ' + hex);
      if (res.ambiguous) {
        lines.push('');
        lines.push('Note: this 32-byte result is probably an encrypted secret’s key, not ' +
          'the secret itself. Add the recovery envelope stored with your shards and ' +
          'recover again.');
      }
      show(out, lines.join('\n'), 'ok');
    } catch (e) {
      show(out, 'Recovery failed: ' + e.message, 'err');
    }
  }

  function onWipe() {
    $('shards').value = '';
    $('envelope').value = '';
    show($('output'), '', '');
  }

  function runSelfTest() {
    var el = $('selftest-out');
    try {
      var vectors = JSON.parse($('sq-vectors').textContent);
      var pass = 0, fail = 0, msgs = [];
      vectors.vectors.forEach(function (vec) {
        try {
          var inputs = vec.shares.slice(0, vec.threshold).map(function (s) { return s.ur; });
          if (vec.mode === 'kek') inputs.push(vec.envelope.ur);
          var res = SQ.recover(inputs);
          if (SQ.toHex(res.secret) === vec.secretHex) pass++;
          else { fail++; msgs.push('FAIL ' + vec.name); }
        } catch (e) { fail++; msgs.push('FAIL ' + vec.name + ': ' + e.message); }
      });
      var ok = fail === 0;
      show(el, (ok ? 'PASS' : 'FAIL') + ': ' + pass + ' built-in vectors recovered, ' + fail +
        ' failed.' + (msgs.length ? '\n' + msgs.join('\n') : ''), ok ? 'ok' : 'err');
    } catch (e) {
      show(el, 'Self-test error: ' + e.message, 'err');
    }
  }

  function updateNetworkBanner() {
    var b = $('net-banner');
    if (navigator.onLine) {
      b.textContent = 'This device appears to be ONLINE. For a real recovery, disconnect from ' +
        'all networks first. This tool never sends data anywhere, but an offline device is safest.';
      b.className = 'banner warn';
    } else {
      b.textContent = 'Offline. Good. This tool runs entirely on this device and sends nothing.';
      b.className = 'banner ok';
    }
  }

  $('recover-btn').addEventListener('click', onRecover);
  $('wipe-btn').addEventListener('click', onWipe);
  $('selftest-btn').addEventListener('click', runSelfTest);
  window.addEventListener('online', updateNetworkBanner);
  window.addEventListener('offline', updateNetworkBanner);

  updateNetworkBanner();
  runSelfTest(); // prove the tool to the user on load
})();
