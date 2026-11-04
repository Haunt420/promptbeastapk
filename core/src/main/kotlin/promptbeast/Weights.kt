package promptbeast

/**
 * A1111-style emphasis -> plain language for prose models: (word:1.4) -> "very word", ((word)) -> "very word",
 * (((word))) -> "extremely word", (word:0.7) -> "slightly word". Escaped parens are never touched.
 */
object Weights {
    private const val NUM = "(-?\\p{Nd}+(?:\\.\\p{Nd}+)?)"
    private const val BODY = "([^()\\n\\\\]+?)"
    private val WEIGHTED = Regex("(?<!\\\\)\\($BODY:$NUM\\)")
    private val TRIPLE = Regex("(?<!\\\\)\\(\\(\\($BODY\\)\\)\\)")
    private val DOUBLE = Regex("(?<!\\\\)\\(\\($BODY\\)\\)")

    private fun wordFor(w: Double): String = when {
        w >= 1.5 -> "extremely "
        w >= 1.15 -> "very "
        w >= 0.85 -> ""
        w >= 0.5 -> "slightly "
        else -> "barely "
    }

    fun hasWeights(text: String): Boolean = WEIGHTED.containsMatchIn(text) || DOUBLE.containsMatchIn(text)

    /** (converted text, number of weight groups converted). emphasis=false just removes the markup (for negatives). */
    fun stripWeights(text: String, emphasis: Boolean = true): Pair<String, Int> {
        var count = 0
        var t = WEIGHTED.replace(text) { m ->
            count++
            (if (emphasis) wordFor(PyText.asciiDigits(m.groupValues[2]).toDouble()) else "") + PyText.strip(m.groupValues[1])
        }
        t = TRIPLE.replace(t) { m ->
            count++
            (if (emphasis) "extremely " else "") + PyText.strip(m.groupValues[1])
        }
        t = DOUBLE.replace(t) { m ->
            count++
            (if (emphasis) "very " else "") + PyText.strip(m.groupValues[1])
        }
        return t to count
    }
}
