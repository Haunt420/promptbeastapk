package promptbeast

/**
 * Injected into every frame of the Mack page. Byte-identical to core/mackbridge.py's FILLER_JS in the desktop repo
 * (the mackbridge golden suite compares a SHA-256), so both apps talk to Mack the same way.
 * (The one `$` in it is followed by `/`, so Kotlin's raw string leaves it alone.)
 */
internal const val FILLER_JS_TEXT = """
(function () {
  if (window.__pbMack) return;
  window.__pbMack = true;
  var OK = /^[A-Za-z0-9_-]{1,40}$/;
  function editable(el) {
    if (!el) return null;
    var t = el.tagName;
    if (t === 'TEXTAREA' || t === 'INPUT' || el.isContentEditable) return el;
    return el.querySelector ? el.querySelector('textarea, input[type=text], input:not([type]), [contenteditable=true]') : null;
  }
  function find(k) {
    var sels = ['textarea[name="' + k + '"]', 'input[name="' + k + '"]', '[data-name="' + k + '"]',
                '[data-key="' + k + '"]', '[id="' + k + '"]'];
    for (var i = 0; i < sels.length; i++) {
      var el = null;
      try { el = editable(document.querySelector(sels[i])); } catch (e) {}
      if (el) return el;
    }
    return null;
  }
  function setVal(el, v) {
    var t = el.tagName;
    if (t === 'TEXTAREA' || t === 'INPUT') {
      var proto = t === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) d.set.call(el, v); else el.value = v;
    } else {
      el.textContent = v;
    }
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
  }
  function getVal(el) {
    return (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') ? el.value : el.textContent;
  }
  function visible(el) {
    var r = el.getBoundingClientRect();
    return !!(r.width || r.height);
  }
  function say(o) { try { console.log('PB:' + JSON.stringify(o)); } catch (e) {} }

  window.addEventListener('message', function (ev) {
    var m = ev.data;
    if (!m || typeof m !== 'object' || m.promptbeast !== 1) return;
    var out = {event: m.action, id: m.id};
    if (m.action === 'fill' && m.values && typeof m.values === 'object') {
      var filled = [];
      Object.keys(m.values).forEach(function (k) {
        if (!OK.test(k)) return;
        var el = find(k);
        if (el) { setVal(el, String(m.values[k])); filled.push(k); }
      });
      if (filled.length) { out.filled = filled; say(out); }
    } else if (m.action === 'read' && Array.isArray(m.keys)) {
      var vals = {};
      m.keys.forEach(function (k) {
        if (!OK.test(k)) return;
        var el = find(k);
        if (el) vals[k] = getVal(el);
      });
      if (Object.keys(vals).length) { out.values = vals; say(out); }
    } else if (m.action === 'click') {
      var cands = document.querySelectorAll('button, input[type=button], input[type=submit], a[role=button]');
      for (var i = 0; i < cands.length; i++) {
        var txt = (cands[i].innerText || cands[i].value || '').trim();
        if (/^generate\b/i.test(txt) && visible(cands[i])) {
          cands[i].click();
          out.clicked = txt;
          say(out);
          break;
        }
      }
    }
  }, false);
})();
"""
