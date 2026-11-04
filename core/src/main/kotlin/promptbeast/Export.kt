package promptbeast

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Export to Mack's input-name JSON, plus the gzipped blob Mack's share links use. Raw (un-evaluated) text is exported. */
object Export {
    fun boxText(project: Project, boxId: String): String {
        val parts = project.picks[boxId].orEmpty().map { PyText.strip(it) }.filter { it.isNotEmpty() }.toMutableList()
        val t = PyText.strip(project.boxes[boxId] ?: "")
        if (t.isNotEmpty()) parts += t
        return parts.joinToString(", ")
    }

    fun mackKey(project: Project, includeScratchpad: Boolean = true): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (spec in Boxes.ALL) {
            val t = boxText(project, spec.id)
            if (t.isNotEmpty()) out[spec.mackKey] = t
        }
        if (PyText.strip(project.negative).isNotEmpty()) out[Boxes.NEGATIVE_KEY] = PyText.strip(project.negative)
        if (includeScratchpad && PyText.strip(project.scratchpad).isNotEmpty()) out[Boxes.SCRATCHPAD_KEY] = project.scratchpad
        return out
    }

    /** `json.dumps(d, ensure_ascii=False, indent=2 | separators=(",", ":"))` for a flat string map. */
    fun toJson(d: Map<String, String>, pretty: Boolean = true): String {
        if (d.isEmpty()) return "{}"
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in d) {
            if (!first) sb.append(',')
            first = false
            if (pretty) sb.append("\n  ")
            quote(sb, k).append(if (pretty) ": " else ":")
            quote(sb, v)
        }
        if (pretty) sb.append('\n')
        return sb.append('}').toString()
    }

    internal fun quote(sb: StringBuilder, s: String): StringBuilder {
        sb.append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000c' -> sb.append("\\f")
            ch < ' ' -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"')
    }

    /** gzip(JSON) - the payload Mack uploads for a share link. */
    fun shareBlob(d: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(toJson(d, pretty = false).toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    fun fromShareBlob(blob: ByteArray): Map<String, String> {
        val text = GZIPInputStream(ByteArrayInputStream(blob)).use { String(it.readBytes(), Charsets.UTF_8) }
        val o = Json.parseToJsonElement(text).jsonObject
        return o.mapValuesTo(LinkedHashMap()) { it.value.jsonPrimitive.content }
    }

    /** (label, mackKey, text) per non-empty box, for per-box copy buttons. */
    fun copyBlocks(project: Project): List<Triple<String, String, String>> {
        val d = mackKey(project, includeScratchpad = false)
        val rows = Boxes.ALL.filter { it.mackKey in d }.map { Triple(it.label, it.mackKey, d.getValue(it.mackKey)) }.toMutableList()
        d[Boxes.NEGATIVE_KEY]?.let { rows += Triple("Negative", Boxes.NEGATIVE_KEY, it) }
        return rows
    }
}
