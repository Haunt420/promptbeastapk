package promptbeast

/**
 * a / an agreement after {a|b} rolls ("a {emerald|oxblood} coat" -> "an emerald coat"). Conservative on purpose:
 * only lower-case alphabetic next words are touched. Hand-written scanner equal to the Python regex
 * `\b(an?|An?)\s+([A-Za-z][A-Za-z'-]*)` (Unicode-aware `\b`, Python `\s`).
 */
object Articles {
    private val VOWEL_CONSONANT_SOUND = listOf("uni", "use", "usu", "uti", "ubi", "eu", "one", "once", "ura")
    private const val SAFE_CONSONANTS = "bcdgjkpqtvwyz"
    private val FUNCTION_WORDS = "and or as at in on of if is it up us are an".split(" ").toSet()

    private fun asciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Python's `\b` immediately before a word character at [p]. */
    private fun boundaryBefore(text: String, p: Int): Boolean = p == 0 || !PyText.isWord(text.codePointBefore(p))

    /** After an article ending at [artEnd]: `\s+` then the word. Returns (wordStart, wordEnd) or null. */
    private fun wordAfter(text: String, artEnd: Int): IntArray? {
        val n = text.length
        var q = artEnd
        while (q < n) {
            val cp = text.codePointAt(q)
            if (!PyText.isSpace(cp)) break
            q += Character.charCount(cp)
        }
        if (q == artEnd || q >= n || !asciiLetter(text[q])) return null
        var r = q + 1
        while (r < n && (asciiLetter(text[r]) || text[r] == '\'' || text[r] == '-')) r++
        return intArrayOf(q, r)
    }

    fun fixArticles(text: String): String {
        val n = text.length
        val out = StringBuilder(n)
        var p = 0
        while (p < n) {
            val c = text[p]
            if ((c == 'a' || c == 'A') && boundaryBefore(text, p)) {
                var artEnd = p + 1
                var w: IntArray? = null
                if (p + 1 < n && text[p + 1] == 'n') { w = wordAfter(text, p + 2); if (w != null) artEnd = p + 2 }
                if (w == null) w = wordAfter(text, p + 1)
                if (w != null) {
                    val art = text.substring(p, artEnd)
                    val word = text.substring(w[0], w[1])
                    out.append(replacement(text.substring(p, w[1]), art, word))
                    p = w[1]
                    continue
                }
            }
            out.append(c)
            p++
        }
        return out.toString()
    }

    private fun replacement(whole: String, art: String, word: String): String {
        if (word.any { it in 'A'..'Z' }) return whole
        if (word in FUNCTION_WORDS) return whole
        val first = word[0]
        val want = when {
            first in "aeiou" -> if (VOWEL_CONSONANT_SOUND.any { word.startsWith(it) }) "a" else "an"
            first in SAFE_CONSONANTS -> "a"
            else -> return whole
        }
        if (want == art.lowercase()) return whole
        val neu = if (art[0].isUpperCase()) want.replaceFirstChar { it.uppercaseChar() } else want
        return "$neu $word"
    }
}
