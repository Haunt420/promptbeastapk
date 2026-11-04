package promptbeast

/** severity: error | warn | info. start/end are CODE POINT offsets into the linted text (-1 when not positional). */
data class Issue(val severity: String, val code: String, val message: String, val start: Int = -1, val end: Int = -1)

/** Prose-aware lint, per ModelProfile. Advisory, except unbalanced DSL, which changes the output. */
object Lint {
    // Python's \b / \w (Unicode): word char = letter | number | underscore.
    private const val W = PyRe.W
    private const val B = PyRe.B
    private const val S = PyRe.S

    // Case-insensitive patterns run over PyText.asciiFold(text) with the plain IGNORE_CASE flag (see asciiFold).
    private val CI = RegexOption.IGNORE_CASE
    private val QUALITY = Regex(
        "$B(masterpiece|best quality|high quality|ultra[- ]detailed|highly detailed|8k|4k|uhd|hdr|" +
            "trending on artstation|award[- ]winning)$B", CI)
    private val NEGATION = Regex("$B(no|without|not|never|don'?t|avoid)[$S]+(?:a |an |any |the )?[a-z]{3,}", CI)
    private val LORA = Regex("<lora:[^>]+>", CI)
    private val VAR_REF = Regex("\\[([^\\[\\]\\n]{1,40})\\]")
    private val VAR_NAME = Regex("^[A-Z0-9_]+$")

    private fun cp(text: String, off: Int) = PyText.cpIndex(text, off)

    private fun balance(text: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val stack = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == '{') stack += i
            else if (c == '}') {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else issues += Issue("error", "E001", "stray '}' with no matching '{'", cp(text, i), cp(text, i) + 1)
            }
            i++
        }
        for (pos in stack) issues += Issue("error", "E001", "unclosed '{' (will print literally)", cp(text, pos), cp(text, pos) + 1)
        return issues
    }

    fun lint(text: String, profile: String, variables: Map<String, String>? = null, scratchpad: String = "",
             isNegative: Boolean = false): List<Issue> =
        lint(text, Profiles.get(profile), variables, scratchpad, isNegative)

    fun lint(text: String, prof: ModelProfile, variables: Map<String, String>? = null, scratchpad: String = "",
             isNegative: Boolean = false): List<Issue> {
        val vars = LinkedHashMap<String, String>(variables ?: emptyMap())
        vars.putAll(Dsl.parseVariables(scratchpad))
        val out = ArrayList<Issue>()

        if (PyText.strip(text).isEmpty()) return listOf(Issue("info", "I000", "empty"))
        out += balance(text)

        for (m in VAR_REF.findAll(text)) {
            val name = m.groupValues[1]
            if (VAR_NAME.matches(name) && name !in vars) {
                out += Issue("warn", "W007", "[$name] is not defined in the scratchpad (stays literal)",
                    cp(text, m.range.first), cp(text, m.range.last + 1))
            }
        }
        val folded = PyText.asciiFold(text)
        for (m in LORA.findAll(folded)) {
            out += Issue("warn", "W005", "LoRA tags do nothing here (no LoRA stack on the generator)",
                cp(text, m.range.first), cp(text, m.range.last + 1))
        }
        if (!prof.readsWeights && Weights.hasWeights(text)) {
            out += Issue("warn", "W001", "${prof.name} ignores (word:1.2)/((word)) weights; they get converted " +
                "to plain-language emphasis on assemble")
        }
        if (isNegative && prof.negative == "none") {
            out += Issue("warn", "W008", "${prof.name} ignores negative prompts")
        } else if (isNegative && prof.negative == "weak") {
            out += Issue("info", "I008", "negatives are weak on ${prof.name}; describe what you want instead")
        }
        if (prof.prose && !isNegative) {
            for (m in QUALITY.findAll(folded)) {
                out += Issue("info", "I002", "'${text.substring(m.range.first, m.range.last + 1)}' is a tag-era quality word; prose models mostly ignore it",
                    cp(text, m.range.first), cp(text, m.range.last + 1))
            }
            val frags = text.split(',', '\n').map { PyText.strip(it) }.filter { it.isNotEmpty() }
            if (frags.size >= 8) {
                val short = frags.count { PyText.splitWhitespace(it).size <= 3 }
                if (short.toDouble() / frags.size >= 0.75) {
                    out += Issue("warn", "W003", "reads as a tag list (${frags.size} short fragments); " +
                        "${prof.name} follows connected sentences better")
                }
            }
            if (prof.negative != "full") {
                for (m in NEGATION.findAll(folded)) {
                    out += Issue("info", "I004", "'${text.substring(m.range.first, m.range.last + 1)}' can pull the thing IN on ${prof.name}; " +
                        "say what should be there instead", cp(text, m.range.first), cp(text, m.range.last + 1))
                }
            }
        }
        return out
    }
}
