package promptbeast

/**
 * Python-compatible text helpers. Java's `\s`, `\d`, `String.trim()` and friends differ from Python's `re` / `str`
 * (different whitespace set, ASCII-only `\d` by default, UTF-16 lengths), and the port must behave identically, so
 * everything here follows CPython's definitions.
 */
object PyText {
    /** `str.isspace()` / regex `\s` for one code point. */
    fun isSpace(cp: Int): Boolean = when (cp) {
        in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** `\d` / `str.isdecimal()`: Unicode category Nd. */
    fun isDecimal(cp: Int): Boolean = Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()

    /** Regex `\w` (str patterns): alphanumeric (L*, N*) or underscore, as CPython's `Py_UNICODE_ISALNUM`. */
    fun isWord(cp: Int): Boolean = cp == '_'.code || Character.isLetterOrDigit(cp) || isNumeric(cp)

    private fun isNumeric(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** Regex character-class body for Python's `\s` (usable inside `[...]` in a Java pattern). */
    const val WS = "\\t-\\r\\u001c-\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000"

    /** `len(s)` in Python: code points, not UTF-16 units. */
    fun length(s: String): Int = s.codePointCount(0, s.length)

    /** Index in code points of UTF-16 offset [i] (what Python reports in messages). */
    fun cpIndex(s: String, i: Int): Int = s.codePointCount(0, minOf(i, s.length))

    /** `s.strip()`. */
    fun strip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b) {
            val cp = s.codePointAt(a)
            if (!isSpace(cp)) break
            a += Character.charCount(cp)
        }
        while (b > a) {
            val cp = s.codePointBefore(b)
            if (!isSpace(cp)) break
            b -= Character.charCount(cp)
        }
        return s.substring(a, b)
    }

    /** Map every Unicode decimal digit to its ASCII digit so `toLong()` / `toDouble()` accept what Python's `int()` / `float()` do. */
    fun asciiDigits(s: String): String {
        var plain = true
        for (ch in s) if (ch.code > 0x7f) { plain = false; break }
        if (plain) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp > 0x7f && isDecimal(cp)) sb.append(('0'.code + Character.digit(cp, 10)).toChar()) else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** `s.split()` (no argument): runs of Python whitespace separate tokens, no empty tokens. */
    fun splitWhitespace(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var start = -1
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            if (isSpace(cp)) {
                if (start >= 0) { out += s.substring(start, i); start = -1 }
            } else if (start < 0) start = i
            i += len
        }
        if (start >= 0) out += s.substring(start)
        return out
    }

    /**
     * Non-letter code points with Other_Lowercase / Other_Uppercase (and no case-ignorable category): ª º, Roman numerals,
     * circled and squared letters. Spelled out because Android's Character.isLowerCase/isUpperCase (ICU) only know Ll/Lu,
     * the JDK's also know these, and the answer must not depend on which one runs. (Lm / Mn ones are case-ignorable and
     * never asked: isCased is only consulted after isCaseIgnorable.)
     */
    private val OTHER_CASED = intArrayOf(
        0xAA, 0xAA, 0xBA, 0xBA, 0x2160, 0x217F, 0x24B6, 0x24E9, 0x1F130, 0x1F149, 0x1F150, 0x1F169,
        0x1F170, 0x1F189)

    private fun inOtherCased(cp: Int): Boolean {
        var i = 0
        while (i < OTHER_CASED.size) {
            if (cp >= OTHER_CASED[i] && cp <= OTHER_CASED[i + 1]) return true
            i += 2
        }
        return false
    }

    /** Unicode `Cased` (Lowercase | Uppercase | Titlecase letter, plus the Other_* ones above). */
    fun isCased(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.LOWERCASE_LETTER.toInt(), Character.UPPERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt() -> true
        else -> inOtherCased(cp)
    }

    private val WORD_BREAK_IGNORABLE = intArrayOf(
        0x27, 0x2E, 0x3A, 0xB7, 0x387, 0x55F, 0x5F4, 0x2018, 0x2019, 0x2024, 0x2027, 0xFE13, 0xFE52, 0xFE55, 0xFF07, 0xFF0E, 0xFF1A)

    /** Unicode `Case_Ignorable`: Mn, Me, Cf, Lm, Sk, plus the Word_Break MidLetter / MidNumLet / Single_Quote characters. */
    fun isCaseIgnorable(cp: Int): Boolean = if (cp == 0x1734) false else when (Character.getType(cp)) { // U+1734 is Mn up to Unicode 13 (JDK 17), Mc since 14
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.MODIFIER_SYMBOL.toInt() -> true
        else -> cp in WORD_BREAK_IGNORABLE
    }

    private fun finalSigma(s: String, at: Int): Boolean {
        var j = at
        var before = false
        while (j > 0) {
            val c = s.codePointBefore(j)
            j -= Character.charCount(c)
            if (!isCaseIgnorable(c)) { before = isCased(c); break }
        }
        if (!before) return false
        j = at + 1
        while (j < s.length) {
            val c = s.codePointAt(j)
            if (!isCaseIgnorable(c)) return !isCased(c)
            j += Character.charCount(c)
        }
        return true
    }

    /** `s.lower()`: full Unicode lowercase as CPython does it (U+0130 -> i + U+0307, contextual final sigma). */
    fun lower(s: String): String {
        var ascii = true
        for (c in s) if (c.code > 0x7f) { ascii = false; break }
        if (ascii) return s.lowercase()
        val sb = StringBuilder(s.length + 2)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (cp) {
                0x130 -> sb.append("i\u0307")
                0x3A3 -> sb.append(if (finalSigma(s, i)) '\u03c2' else '\u03c3')
                else -> sb.appendCodePoint(Character.toLowerCase(cp))
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * Prepares text for an ASCII-letter, case-insensitive regex that must behave like Python's `re.I`: the only
     * non-ASCII characters Python equates with ASCII letters are U+0130/U+0131 (i), U+017F (s) and U+212A (k). Folding
     * them first makes the result independent of how a regex engine (JDK vs Android's ICU) does Unicode case folding.
     * Same length in UTF-16 units, so match offsets stay valid.
     */
    fun asciiFold(s: String): String {
        var needs = false
        for (c in s) if (c == '\u0130' || c == '\u0131' || c == '\u017f' || c == '\u212a') { needs = true; break }
        if (!needs) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(when (c) { '\u0130', '\u0131' -> 'i'; '\u017f' -> 's'; '\u212a' -> 'k'; else -> c })
        return sb.toString()
    }

    /** `s[:n]` in Python: the first n CODE POINTS. */
    fun prefix(s: String, n: Int): String =
        if (s.length <= n || length(s) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    /** `s.rstrip()`. */
    fun rstrip(s: String): String {
        var b = s.length
        while (b > 0) {
            val cp = s.codePointBefore(b)
            if (!isSpace(cp)) break
            b -= Character.charCount(cp)
        }
        return s.substring(0, b)
    }

    /** `s.splitlines()`: splits on \n \r \r\n \v \f \x1c-\x1e \x85 \u2028 \u2029, no trailing empty element. */
    fun splitLines(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            val brk = c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c == '\u001c' || c == '\u001d' ||
                c == '\u001e' || c == '\u0085' || c == '\u2028' || c == '\u2029'
            if (brk) {
                out += s.substring(start, i)
                if (c == '\r' && i + 1 < n && s[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < n) out += s.substring(start)
        return out
    }

    /** Python's default string ordering: by code point (Kotlin's compares UTF-16 units, which differs above U+FFFF). */
    val CODEPOINT_ORDER: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        var r = 0
        while (r == 0 && i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            r = x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        if (r != 0) r else (a.length - i).compareTo(b.length - j)
    }
}

/** Python-flavoured regex building blocks (Java's own `\w`, `\b`, `\s` differ from Python's). */
object PyRe {
    const val W = "[\\p{L}\\p{N}_]"
    const val B = "(?:(?<=$W)(?!$W)|(?<!$W)(?=$W))"
    const val S = PyText.WS
}
