package promptbeast

/**
 * Tag packs: plain-text `.pbpack` files -> slots of weighted tags with coherence traits. Port of core/forge/pack.py
 * (see that file for the format). Loading never throws: bad lines are dropped and reported in [Pack.issues].
 */
val TIER_NAMES = listOf("base", "sensual", "nude", "partner")
private val TIER_BY_NAME = mapOf("" to 0, "base" to 0, "sensual" to 1, "nude" to 2, "partner" to 3)

data class Tag(
    val text: String,
    val weight: Double = 1.0,
    val give: Set<String> = emptySet(),
    val need: Set<String> = emptySet(),
    val anyOf: Set<String> = emptySet(),
    val avoid: Set<String> = emptySet(),
    val tier: Int = 0,
    val pack: String = "",
)

class Slot(
    val id: String,
    var label: String,
    var box: String = "misc",
    var order: Int = 50,
    var chance: Double = 1.0,
    var lo: Int = 1,
    var hi: Int = 1,
    var sep: String = ", ",
    var tier: Int? = null,                 // default tier for tags in this slot (null = pack default)
    val tags: MutableList<Tag> = ArrayList(),
) {
    fun copyWithTags(): Slot = Slot(id, label, box, order, chance, lo, hi, sep, tier, ArrayList(tags))
}

class Pack(
    var id: String = "pack",
    var name: String = "pack",
    var adults: Boolean = false,
    var tier: Int = 0,
    val slots: LinkedHashMap<String, Slot> = LinkedHashMap(),
    val issues: MutableList<String> = ArrayList(),
)

object PackParser {
    /** Nothing minor-coded may ever load, in any pack, built-in or user-made. */
    private val AGE_GUARD_RE = Regex(
        "${PyRe.B}(child(?:ren|like|ish)?|kids?|minors?|under-?age[d]?|teens?|teen-?age[dr]?s?|pre-?teens?|tweens?|" +
            "loli${PyRe.W}*|shota${PyRe.W}*|school-?(?:girl|boy|child)${PyRe.W}*|toddlers?|babies|infants?|juveniles?|jailbait|" +
            "baby-?faced|barely legal|little (?:girl|boy)|young (?:girl|boy)|high[- ]school${PyRe.W}*|middle[- ]school${PyRe.W}*|" +
            "elementary|prepubescent|pubescent)${PyRe.B}", RegexOption.IGNORE_CASE)

    /** True when [text] contains anything minor-coded (case-insensitive like Python's `re.I`, see PyText.asciiFold). */
    fun ageGuardHit(text: String): Boolean = AGE_GUARD_RE.containsMatchIn(PyText.asciiFold(text))

    private val KV = Regex("(${PyRe.W}+)=(\"[^\"]*\"|[^${PyRe.S}]+)")
    private val INT_RE = Regex("[+-]?[0-9]{1,9}")
    private val FLOAT_RE = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,4})?")
    private val COUNT_RE = Regex("([0-9]{1,9})(?:-([0-9]{1,9}))?")
    private val NOT_ID = Regex("[^a-z0-9_-]")
    private val NOT_SLOT = Regex("[^a-z0-9_]")
    const val MAX_WEIGHT = 1e6

    private class BadNumber : RuntimeException()

    private fun toInt(v: String): Int {
        val t = PyText.strip(v)
        if (!INT_RE.matches(t)) throw BadNumber()
        return t.toInt()
    }

    private fun toFloat(v: String): Double {
        val t = PyText.strip(v)
        if (!FLOAT_RE.matches(t)) throw BadNumber()
        val x = t.toDouble()
        if (x.isNaN() || x.isInfinite()) throw BadNumber()
        return x
    }

    private fun count(v: String): Pair<Int, Int> {
        val m = COUNT_RE.matchEntire(PyText.strip(v)) ?: throw BadNumber()
        val lo = m.groupValues[1].toInt()
        val hi = m.groupValues[2].ifEmpty { null }?.toInt() ?: lo
        return maxOf(0, minOf(lo, hi)) to maxOf(lo, hi)
    }

    private fun kv(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in KV.findAll(s)) out[m.groupValues[1]] = m.groupValues[2].trim('"')
        return out
    }

    private fun set(s: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (x in s.split(',')) {
            val t = PyText.strip(x)
            if (t.isNotEmpty()) out += PyText.lower(t)
        }
        return out
    }

    private fun titleOf(sid: String): String {
        val sb = StringBuilder()
        var prevLetter = false
        for (c in sid.replace('_', ' ')) {
            if (c.isLetter()) { sb.append(if (prevLetter) c.lowercaseChar() else c.uppercaseChar()); prevLetter = true }
            else { sb.append(c); prevLetter = false }
        }
        return sb.toString()
    }

    fun parse(text: String, source: String = "pack"): Pack {
        val p = Pack(id = source, name = source)
        var cur: Slot? = null
        for ((idx, raw) in PyText.splitLines(text).withIndex()) {
            val n = idx + 1
            val line = PyText.strip(raw)
            if (line.isEmpty() || line.startsWith("#")) continue
            val where = "$source:$n"
            if (line.startsWith("@pack")) {
                val m = kv(line.substring(5))
                p.id = NOT_ID.replace(PyText.lower(m["id"] ?: p.id), "").ifEmpty { p.id }
                p.name = m["name"] ?: p.name
                p.adults = PyText.lower(m["adults"] ?: "false") in listOf("true", "yes", "1")
                p.tier = TIER_BY_NAME[PyText.lower(m["tier"] ?: if (p.adults) "sensual" else "")] ?: if (p.adults) 1 else 0
                continue
            }
            if (line.startsWith("@slot")) {
                val body = PyText.strip(line.substring(5))
                val sp = body.indexOf(' ')
                val head = if (sp < 0) body else body.substring(0, sp)
                val rest = if (sp < 0) "" else body.substring(sp + 1)
                val sid = NOT_SLOT.replace(PyText.lower(head), "")
                if (sid.isEmpty()) {
                    p.issues += "$where: @slot needs an id"
                    cur = null
                    continue
                }
                val m = kv(rest)
                var box = m["box"] ?: "misc"
                if (box !in Boxes.IDS) {
                    p.issues += "$where: unknown box '$box', using misc"
                    box = "misc"
                }
                val s = Slot(id = sid, label = m["label"] ?: titleOf(sid), box = box)
                try {
                    s.order = toInt(m["order"] ?: "50")
                    s.chance = minOf(1.0, maxOf(0.0, toFloat(m["chance"] ?: "1")))
                    val (lo, hi) = count(m["count"] ?: "1")
                    s.lo = lo; s.hi = hi
                } catch (e: BadNumber) {
                    p.issues += "$where: bad number in @slot $sid"
                }
                m["sep"]?.let { s.sep = it }
                m["tier"]?.let { t ->
                    s.tier = TIER_BY_NAME[PyText.lower(t)]
                    if (s.tier == null) p.issues += "$where: unknown tier '$t'"
                }
                p.slots[sid] = s
                cur = s
                continue
            }
            if (line.startsWith("@")) {
                p.issues += "$where: unknown directive ${PyText.splitWhitespace(line)[0]}"
                continue
            }
            val slot = cur
            if (slot == null) {
                p.issues += "$where: tag outside any @slot"
                continue
            }
            val parts = line.split(';').map { PyText.strip(it) }
            val txt = parts[0]
            if (txt.isEmpty()) {
                p.issues += "$where: empty tag text"
                continue
            }
            if (ageGuardHit(txt)) {
                p.issues += "$where: dropped (age guard): ${PyText.prefix(txt, 40)}"
                continue
            }
            var weight = 1.0
            var give: Set<String> = emptySet()
            var need: Set<String> = emptySet()
            var anyOf: Set<String> = emptySet()
            var avoid: Set<String> = emptySet()
            var ok = true
            var tier = slot.tier ?: p.tier
            for (mod in parts.drop(1)) {
                val eq = mod.indexOf('=')
                val k = if (eq < 0) mod else PyText.lower(PyText.strip(mod.substring(0, eq)))
                val v = if (eq < 0) "" else PyText.strip(mod.substring(eq + 1))
                if (eq < 0) {
                    p.issues += "$where: modifier '$mod' needs key=value"
                    ok = false
                } else when (k) {
                    "w" -> try {
                        weight = minOf(MAX_WEIGHT, maxOf(0.0, toFloat(v)))
                    } catch (e: BadNumber) {
                        p.issues += "$where: bad weight '$v'"
                        ok = false
                    }
                    "give" -> give = set(v)
                    "need" -> need = set(v)
                    "not" -> avoid = set(v)
                    "any" -> anyOf = set(v)
                    "tier" -> {
                        val t = TIER_BY_NAME[PyText.lower(v)]
                        if (t == null) {
                            p.issues += "$where: unknown tier '$v'"
                            ok = false
                        } else tier = t
                    }
                    else -> {
                        p.issues += "$where: unknown modifier '$k'"
                        ok = false
                    }
                }
            }
            if (!ok) continue
            if (tier > 0 && !p.adults) {
                p.issues += "$where: dropped (tier tag in a pack without adults=true): ${PyText.prefix(txt, 40)}"
                continue
            }
            slot.tags += Tag(text = txt, weight = weight, give = give, need = need, anyOf = anyOf, avoid = avoid, tier = tier, pack = p.id)
        }
        for (sid in p.slots.filterValues { it.tags.isEmpty() }.keys.toList()) {
            p.issues += "slot '$sid' has no tags"
            p.slots.remove(sid)
        }
        return p
    }
}

fun parsePack(text: String, source: String = "pack"): Pack = PackParser.parse(text, source)
