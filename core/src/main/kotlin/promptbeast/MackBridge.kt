package promptbeast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What one frame of the Mack page said back, reduced to the fields the app reads (each already type-checked). */
class MackReply(val event: String, val id: Long?, val filled: List<String>, val values: Map<String, String>, val clicked: String?)

/** Mack's inputs pulled back into Compose terms: box id -> text, plus negative / scratchpad when Mack had them. */
class MackParts(val boxes: LinkedHashMap<String, String>, val negative: String?, val scratchpad: String?)

/**
 * Bridge between Compose and Mack's McGenerator page: port of core/mackbridge.py. Pure strings in, pure strings out - no
 * WebView here, so it is verified against the Python reference like everything else. FILLER_JS goes into every frame of the
 * page; to act, the app runs [messageJs] in the top frame, which postMessage()s every nested frame, and each frame's filler
 * answers with a `PB:{json}` console line that [parseReply] reads. Console lines come from an untrusted page: they are
 * size-capped, depth-capped and parsed strictly.
 */
object MackBridge {
    const val URL = "https://perchance.org/mack-mcgenerator"
    const val PREFIX = "PB:"
    const val MAX_CONSOLE = 2_000_000
    const val MAX_DEPTH = 64

    val KEYS: List<String> = Boxes.ALL.map { it.mackKey } + listOf(Boxes.NEGATIVE_KEY, Boxes.SCRATCHPAD_KEY)
    val FILLER_JS: String = FILLER_JS_TEXT

    private val ACTIONS = setOf("fill", "read", "click")

    /** Every known Mack input -> its text from Compose. Empty boxes map to "" so Mack mirrors Compose exactly. */
    fun fillValues(project: Project, includeScratchpad: Boolean = true): LinkedHashMap<String, String> {
        val have = Export.mackKey(project, includeScratchpad)
        val out = LinkedHashMap<String, String>()
        for (k in KEYS) if (includeScratchpad || k != Boxes.SCRATCHPAD_KEY) out[k] = have[k] ?: ""
        return out
    }

    /** Script for the TOP frame: post one message to the window and every nested frame. [payload] keeps its order. */
    fun messageJs(action: String, reqId: Long, payload: List<Pair<String, JsonElement>> = emptyList()): String {
        require(action in ACTIONS) { "unknown action $action" }
        val msg = buildJsonObject {
            put("promptbeast", 1)
            put("action", action)
            put("id", reqId)
            for ((k, v) in payload) put(k, v)
        }
        return "(function(msg){var seen=[],n=0;function walk(w){if(seen.indexOf(w)>=0)return;seen.push(w);" +
            "try{w.postMessage(msg,'*');n++;}catch(e){}var len=0;try{len=w.frames.length;}catch(e){}" +
            "for(var i=0;i<len;i++){try{walk(w.frames[i]);}catch(e){}}}walk(window);return n;})(" +
            PyJson.dumps(msg, ensureAscii = true) + ")"
    }

    fun fillJs(reqId: Long, values: Map<String, String>): String =
        messageJs("fill", reqId, listOf("values" to JsonObject(values.mapValues { JsonPrimitive(it.value) })))

    fun readJs(reqId: Long, keys: List<String>): String =
        messageJs("read", reqId, listOf("keys" to JsonArray(keys.map { JsonPrimitive(it) })))

    fun clickJs(reqId: Long): String = messageJs("click", reqId)

    /** A console line from the page -> the reply object, or null for anything that isn't ours. */
    fun parseConsole(message: String): JsonObject? {
        if (!message.startsWith(PREFIX) || message.length > 2 * MAX_CONSOLE || PyText.length(message) > MAX_CONSOLE) return null
        val d = StrictJson.parse(message.substring(PREFIX.length), MAX_DEPTH) as? JsonObject ?: return null
        val ev = d["event"] as? JsonPrimitive ?: return null
        return if (ev.isString) d else null
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun parseReply(message: String): MackReply? {
        val d = parseConsole(message) ?: return null
        val idEl = d["id"] as? JsonPrimitive
        val id = if (idEl != null && !idEl.isString) idEl.content.toLongOrNull() else null
        val filled = (d["filled"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        val values = LinkedHashMap<String, String>()
        (d["values"] as? JsonObject)?.forEach { (k, v) -> v.str()?.let { values[k] = it } }
        val clicked = d["clicked"].str()?.takeIf { it.isNotEmpty() }
        return MackReply(d["event"].str()!!, id, filled, values, clicked)
    }

    /** Pulled Mack inputs -> Compose parts. Unknown keys and non-strings are ignored. */
    fun valuesToProjectParts(values: Map<String, JsonElement>): MackParts {
        val boxes = LinkedHashMap<String, String>()
        for (spec in Boxes.ALL) values[spec.mackKey].str()?.let { boxes[spec.id] = it }
        return MackParts(boxes, values[Boxes.NEGATIVE_KEY].str(), values[Boxes.SCRATCHPAD_KEY].str())
    }
}
