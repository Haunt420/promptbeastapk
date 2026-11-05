package promptbeast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `json.loads` as CPython accepts it, for text that comes from an untrusted page: strict RFC 8259 (no comments, no trailing
 * commas, no NaN/Infinity, raw control characters refused inside strings, ASCII-only \u hex), duplicate keys keep the last
 * value, and containers nested deeper than [maxDepth] are refused (the top-level value is depth 1). Returns null for
 * anything invalid. Whole numbers that fit a Long come back as Long primitives; every other number as a Double.
 */
object StrictJson {
    fun parse(text: String, maxDepth: Int): JsonElement? = try {
        Parser(text, maxDepth).run()
    } catch (e: Bad) {
        null
    }

    private class Bad : RuntimeException(null, null, false, false)

    private class Parser(val s: String, val maxDepth: Int) {
        var i = 0

        fun run(): JsonElement {
            ws()
            val v = value(1)
            ws()
            if (i != s.length) throw Bad()
            return v
        }

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun value(depth: Int): JsonElement {
            if (i >= s.length) throw Bad()
            val c = s[i]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> arr(depth)
                c == '"' -> JsonPrimitive(str())
                c == 't' -> lit("true", JsonPrimitive(true))
                c == 'f' -> lit("false", JsonPrimitive(false))
                c == 'n' -> lit("null", JsonNull)
                c == '-' || c in '0'..'9' -> num()
                else -> throw Bad()
            }
        }

        fun lit(word: String, v: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) throw Bad()
            i += word.length
            return v
        }

        fun obj(depth: Int): JsonElement {
            if (depth > maxDepth) throw Bad()
            i++
            val m = LinkedHashMap<String, JsonElement>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(m) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad()
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw Bad()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject(m) }
                    else -> throw Bad()
                }
            }
        }

        fun arr(depth: Int): JsonElement {
            if (depth > maxDepth) throw Bad()
            i++
            val l = ArrayList<JsonElement>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(l) }
            while (true) {
                ws()
                l += value(depth + 1)
                ws()
                if (i >= s.length) throw Bad()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(l) }
                    else -> throw Bad()
                }
            }
        }

        fun hex4(): Int {
            if (i + 4 > s.length) throw Bad()
            var v = 0
            repeat(4) {
                val c = s[i++]
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw Bad()
                }
                v = v * 16 + d
            }
            return v
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw Bad()
                        when (s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(hex4().toChar())
                            else -> throw Bad()
                        }
                    }
                    c < ' ' -> throw Bad()
                    else -> sb.append(c)
                }
            }
        }

        fun digits() { while (i < s.length && s[i] in '0'..'9') i++ }

        fun num(): JsonElement {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw Bad()
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') digits() else throw Bad()
            var whole = true
            if (i + 1 < s.length && s[i] == '.' && s[i + 1] in '0'..'9') { whole = false; i++; digits() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j] in '0'..'9') { whole = false; i = j; digits() }
            }
            val t = s.substring(start, i)
            if (whole) t.toLongOrNull()?.let { return JsonPrimitive(it) }
            return JsonPrimitive(t.toDouble())
        }
    }
}
