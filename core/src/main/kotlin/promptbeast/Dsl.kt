package promptbeast

/**
 * Perchance-style text expansion: {a|b|c}, {a^3|b}, {1-6}, {a-z}, [VAR], \ escapes.
 * Port of promptbeast/core/dsl.py; the same seed gives the same text (verified by golden vectors).
 */
object Dsl {
    private const val D = "\\p{Nd}"
    private const val S = PyText.WS
    // UNIX_LINES via flag (not inline): only "\n" ends a line, like Python's `.` and `$`
    private val UNIX = RegexOption.UNIX_LINES
    val VAR_LINE = Regex("^[A-Z0-9_]+? *=.+\$", UNIX)
    private val WEIGHT = Regex("\\^($D+(?:\\.$D+)?)[$S]*\$", UNIX)
    private val INT_RANGE = Regex("^[$S]*(-?$D{1,18})[$S]*-[$S]*($D{1,18})[$S]*\$", UNIX)
    private val LET_RANGE = Regex("^[$S]*(?:([a-z])[$S]*-[$S]*([a-z])|([A-Z])[$S]*-[$S]*([A-Z]))[$S]*\$", UNIX)
    internal const val ESCAPABLE = "{}[]|\\^"

    /** Scratchpad text -> {NAME: raw value}. Later lines override earlier ones. */
    fun parseVariables(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if (VAR_LINE.find(line) != null) {
                val eq = line.indexOf('=')
                out[PyText.strip(line.substring(0, eq))] = PyText.strip(line.substring(eq + 1))
            }
        }
        return out
    }

    internal fun weightMatch(o: String): MatchResult? = WEIGHT.find(o)
    internal fun stripWeight(o: String): String = WEIGHT.find(o)?.let { o.removeRange(it.range) } ?: o
    internal fun intRange(o: String): MatchResult? = INT_RANGE.find(o)
    internal fun letRange(o: String): MatchResult? = LET_RANGE.find(o)
}

class EvalResult(val text: String, val unresolved: Set<String>, val errors: List<String>)

/** One Evaluator per roll: it owns the RNG stream, the sticky cache and the budgets. */
class Evaluator(
    private val variables: Map<String, String> = emptyMap(),
    private val rng: PyRandom = PyRandom.ofInt(System.nanoTime()),
    private val sticky: Boolean = false,
    private val maxDepth: Int = 16,
    private val maxSteps: Int = 20_000,
    private val maxLen: Int = 100_000,
) {
    val unresolved = LinkedHashSet<String>()
    val errors = ArrayList<String>()
    private var steps = 0
    private val cache = HashMap<String, String>()
    private val stack = ArrayList<String>()

    fun eval(text: String): String {
        var out = ev(text, 0)
        if (PyText.length(out) > maxLen) {
            err("output truncated at $maxLen chars")
            out = out.substring(0, out.offsetByCodePoints(0, maxLen))
        }
        return out
    }

    fun result(text: String): EvalResult {
        val t = eval(text)
        return EvalResult(t, unresolved.toSet(), errors.toList())
    }

    private fun err(msg: String) {
        if (msg !in errors) errors += msg
    }

    private fun nextSpecial(s: String, from: Int): Int {
        for (k in from until s.length) {
            val c = s[k]
            if (c == '\\' || c == '{' || c == '[') return k
        }
        return -1
    }

    private fun isVarChar(c: Char) = c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun ev(s: String, depth: Int): String {
        if (depth > maxDepth) {
            err("nesting deeper than $maxDepth")
            return s
        }
        val out = StringBuilder()
        var i = 0
        val n = s.length
        while (i < n) {
            val j = nextSpecial(s, i)
            if (j < 0) {
                out.append(s, i, n)
                break
            }
            if (j > i) out.append(s, i, j)
            val c = s[j]
            i = j
            when (c) {
                '\\' -> if (j + 1 < n && Dsl.ESCAPABLE.indexOf(s[j + 1]) >= 0) {
                    out.append(s[j + 1]); i = j + 2
                } else {
                    out.append('\\'); i = j + 1
                }
                '{' -> {
                    val k = matchBrace(s, j)
                    if (k < 0) {
                        err("unclosed '{' at ${PyText.cpIndex(s, j)}")
                        out.append('{'); i = j + 1
                    } else {
                        out.append(choose(s.substring(j + 1, k), depth)); i = k + 1
                    }
                }
                else -> { // '['
                    var e = j + 1
                    while (e < n && isVarChar(s[e])) e++
                    if (e > j + 1 && e < n && s[e] == ']') {
                        out.append(variable(s.substring(j + 1, e), depth)); i = e + 1
                    } else {
                        out.append('['); i = j + 1
                    }
                }
            }
            if (steps > maxSteps) {
                err("expansion limit ($maxSteps steps) hit; rest left unexpanded")
                out.append(s, i, n)
                break
            }
        }
        return out.toString()
    }

    private fun matchBrace(s: String, i: Int): Int {
        var depth = 0
        var j = i
        val n = s.length
        while (j < n) {
            val c = s[j]
            if (c == '\\') { j += 2; continue }
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return j
            }
            j++
        }
        return -1
    }

    private fun splitOptions(inner: String): List<String> {
        val opts = ArrayList<String>()
        var depth = 0
        var start = 0
        var j = 0
        val n = inner.length
        while (j < n) {
            val c = inner[j]
            if (c == '\\') { j += 2; continue }
            if (c == '{') depth++
            else if (c == '}') depth--
            else if (c == '|' && depth == 0) {
                opts += inner.substring(start, j)
                start = j + 1
            }
            j++
        }
        opts += inner.substring(start)
        return opts
    }

    private fun choose(inner: String, depth: Int): String {
        steps++
        val opts = splitOptions(inner)
        if (opts.size == 1) {
            Dsl.intRange(opts[0])?.let { m ->
                val a = PyText.asciiDigits(m.groupValues[1]).toLong()
                val b = PyText.asciiDigits(m.groupValues[2]).toLong()
                val lo = if (a <= b) a else b
                val hi = if (a <= b) b else a
                return rng.randint(lo, hi).toString()
            }
            Dsl.letRange(opts[0])?.let { m ->
                val g = m.groupValues
                val (a, b) = if (g[1].isNotEmpty()) g[1][0] to g[2][0] else g[3][0] to g[4][0]
                val lo = minOf(a.code, b.code)
                val hi = maxOf(a.code, b.code)
                return rng.randint(lo.toLong(), hi.toLong()).toInt().toChar().toString()
            }
            return ev(Dsl.stripWeight(opts[0]), depth + 1)
        }
        val weights = DoubleArray(opts.size)
        val cleaned = ArrayList<String>(opts.size)
        for ((idx, o) in opts.withIndex()) {
            val wm = Dsl.weightMatch(o)
            weights[idx] = if (wm != null) PyText.asciiDigits(wm.groupValues[1]).toDouble() else 1.0
            cleaned += if (wm != null) o.substring(0, wm.range.first) else o
        }
        var total = 0.0
        for (w in weights) total += w
        if (total.isNaN() || total.isInfinite() || total <= 0.0) weights.fill(1.0)
        return ev(cleaned[rng.chooseIndex(weights)], depth + 1)
    }

    private fun variable(name: String, depth: Int): String {
        steps++
        val raw = variables[name]
        if (raw == null) {
            unresolved += name
            return "[$name]"
        }
        if (sticky) cache[name]?.let { return it }
        if (name in stack) {
            err("variable cycle: ${(stack + name).joinToString(" -> ")}")
            return "[$name]"
        }
        stack += name
        val v = try { ev(raw, depth + 1) } finally { stack.removeAt(stack.size - 1) }
        if (sticky) cache[name] = v
        return v
    }
}

/** `evaluate(text, variables, seed, sticky)` from the Python core. */
fun evaluate(text: String, variables: Map<String, String> = emptyMap(), seed: Long = 0, sticky: Boolean = false): String =
    Evaluator(variables, PyRandom.ofInt(seed), sticky).eval(text)
