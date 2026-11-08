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
  function plain(s) {
    return String(s || '').replace(/[^A-Za-z0-9]+/g, ' ').replace(/^ +| +$/g, '').toLowerCase();
  }
  function everything(root, out, depth) {
    var els = root.querySelectorAll('*');
    for (var i = 0; i < els.length && out.length < 6000; i++) {
      out.push(els[i]);
      if (els[i].shadowRoot && depth < 4) everything(els[i].shadowRoot, out, depth + 1);
    }
    return out;
  }
  function labelOf(el) {
    var t = el.innerText;
    if (t === undefined) t = el.textContent;
    if (!t && el.tagName === 'INPUT') t = el.value;
    if (!t) t = el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('alt');
    return String(t || '').replace(/\s+/g, ' ').replace(/^ +| +$/g, '');
  }
  function clickable(el) {
    var t = el.tagName;
    if (t === 'BUTTON' || t === 'A' || t === 'SUMMARY') return true;
    if (t === 'INPUT') return /^(button|submit|image)$/i.test(el.type || '');
    if (el.getAttribute('role') === 'button' || el.hasAttribute('onclick') || typeof el.onclick === 'function') return true;
    try { return getComputedStyle(el).cursor === 'pointer'; } catch (e) { return false; }
  }
  function usable(el) {
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
    try {
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.pointerEvents === 'none') return false;
    } catch (e) {}
    return true;
  }
  function score(el, label) {
    var n = plain(label);
    if (n.length > 40) return 0;
    if (n === 'generate') return 100;
    if (/^generate( |$)/.test(n)) return 90;
    if (/(^| )generate( |$)/.test(n) || /^generat/.test(n)) return 70;
    var h = plain((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '') + ' ' + (el.getAttribute('name') || ''));
    return /generate/.test(h) ? 60 : 0;
  }
  function pickGenerate() {
    var els = everything(document, [], 0), best = null, top = 0, seen = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.tagName !== 'INPUT' && (el.textContent || '').length > 80) continue;
      if (!clickable(el) || !visible(el)) continue;
      var label = labelOf(el);
      var tag = label || (el.id ? '#' + el.id : '') ||
        (typeof el.className === 'string' && el.className ? '.' + el.className.split(' ')[0] : '') || el.tagName.toLowerCase();
      var off = !usable(el);
      var s = tag.slice(0, 30) + (off ? ' (off)' : '');
      if (seen.length < 12 && seen.indexOf(s) < 0) seen.push(s);
      if (off) continue;
      var sc = score(el, label);
      if (sc > top) { top = sc; best = {el: el, label: label || tag}; }
    }
    return {best: best, top: top, seen: seen};
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
      var pick = pickGenerate();
      if (pick.best && pick.top >= 60) {
        try { pick.best.el.scrollIntoView({block: 'center'}); } catch (e) {}
        pick.best.el.click();
        out.clicked = pick.best.label.slice(0, 40) || 'generate';
        say(out);
      } else if (pick.seen.length) {
        out.values = {seen: location.hostname + ': ' + pick.seen.join(' | ')};
        say(out);
      }
    }
  }, false);
})();
"""
