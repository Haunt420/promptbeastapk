package app.promptbeast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Saved sign-in for Perchance inside the Mack pane. Two steps so the password only ever goes to a frame that has a visible
 * password field and is served from perchance.org: (1) a probe with no secret; frames with a password field answer with their
 * origin; (2) the fill message is posted with exactly that origin as the target, so no other frame can receive it.
 * Messages carry `promptbeast: 2`; Mack's own filler (which only reads `promptbeast: 1`) never sees them.
 */
object MackLogin {
    private val ORIGIN = Regex("^https://([a-z0-9-]+\\.)*perchance\\.org$")

    fun originOk(o: String): Boolean = o.length <= 120 && ORIGIN.matches(o)

    /** Injected into every perchance.org frame next to the Mack filler. */
    val JS: String = """
(function () {
  if (window.__pbLogin) return;
  window.__pbLogin = true;
  function visible(el) { var r = el.getBoundingClientRect(); return !!(r.width || r.height); }
  function pwField() {
    var l = document.querySelectorAll('input[type=password]');
    for (var i = 0; i < l.length; i++) if (visible(l[i]) && !l[i].disabled) return l[i];
    return null;
  }
  function userField(p) {
    var all = (p.form || document).querySelectorAll('input'), best = null;
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e === p) break;
      if (/^(text|email|tel)${'$'}/.test((e.getAttribute('type') || 'text').toLowerCase()) && visible(e) && !e.disabled) best = e;
    }
    return best;
  }
  function setVal(el, v) {
    var d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    if (d && d.set) d.set.call(el, v); else el.value = v;
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
  }
  function say(o) { try { console.log('PB:' + JSON.stringify(o)); } catch (e) {} }
  window.addEventListener('message', function (ev) {
    var m = ev.data;
    if (!m || typeof m !== 'object' || m.promptbeast !== 2) return;
    var p = pwField();
    if (!p) return;
    var out = {event: 'login', id: m.id};
    if (m.action === 'probe') {
      out.values = {origin: location.origin};
      say(out);
    } else if (m.action === 'fill' && typeof m.user === 'string' && typeof m.pass === 'string') {
      var u = userField(p), done = [];
      if (u) { setVal(u, m.user); done.push('user'); }
      setVal(p, m.pass);
      done.push('password');
      out.filled = done;
      say(out);
    }
  }, false);
})();
"""

    private fun relay(msg: String, origins: List<String>): String =
        "(function(msg,origins){var seen=[];function walk(w){if(seen.indexOf(w)>=0)return;seen.push(w);" +
            "for(var j=0;j<origins.length;j++){try{w.postMessage(msg,origins[j]);}catch(e){}}" +
            "var len=0;try{len=w.frames.length;}catch(e){}" +
            "for(var i=0;i<len;i++){try{walk(w.frames[i]);}catch(e){}}}walk(window);})(" +
            msg + "," + JsonArray(origins.map { JsonPrimitive(it) }) + ")"

    fun probeJs(reqId: Long): String {
        val msg = buildJsonObject { put("promptbeast", 2); put("action", "probe"); put("id", reqId) }
        return relay(msg.toString(), listOf("*"))
    }

    fun fillJs(reqId: Long, origins: List<String>, user: String, pass: String): String {
        require(origins.isNotEmpty() && origins.all { originOk(it) }) { "origin not allowed" }
        val msg = buildJsonObject {
            put("promptbeast", 2); put("action", "fill"); put("id", reqId); put("user", user); put("pass", pass)
        }
        return relay(msg.toString(), origins)
    }
}
