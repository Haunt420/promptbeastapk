package promptbeast

/**
 * Forge engine: seeded, per-slot-deterministic rolls with trait-based coherence. Each slot rolls from its own RNG
 * (seed + slot id + salt), so re-rolling one slot never reshuffles the others except where coherence forces it.
 * Port of core/forge/engine.py.
 */
const val ADULT_MARK = "adult"
const val ADULT_NEGATIVE = "child, childlike, underage, minor"

class ForgeState(
    var seed: Long = 0,
    val salts: MutableMap<String, Long> = HashMap(),
    val locks: MutableMap<String, List<String>> = HashMap(),   // slot id -> fixed texts (locked or hand-typed)
    val disabled: MutableSet<String> = HashSet(),
    var maxTier: Int = 0,                                      // 0 = base only
    var heat: Double = 0.5,                                    // 0..1, scales weight of adult-tier tags
    var strict: Boolean = true,
)

class SlotResult(val slot: String, val texts: List<String>, val locked: Boolean = false, val tier: Int = 0)

class ForgeResult(val seed: Long) {
    var picks: LinkedHashMap<String, SlotResult> = LinkedHashMap()
    val traits = HashSet<String>()
    val notes = ArrayList<String>()
    val tierUsed: Int get() = picks.values.maxOfOrNull { it.tier } ?: 0
}

object Forge {
    private fun ok(tag: Tag, traits: Set<String>, forbidden: Set<String>, relax: Boolean = false): Boolean {
        if (tag.avoid.any { it in traits } || tag.give.any { it in forbidden }) return false
        if (relax) return true
        return traits.containsAll(tag.need) && (tag.anyOf.isEmpty() || tag.anyOf.any { it in traits })
    }

    private fun weight(tag: Tag, st: ForgeState): Double = tag.weight * (if (tag.tier > 0) st.heat * 4.0 else 1.0)

    private fun eligible(slot: Slot, st: ForgeState): Boolean =
        slot.id !in st.disabled && slot.tags.any { it.tier <= st.maxTier && weight(it, st) > 0 }

    /** How many still-unmet needs of locked picks this tag would satisfy. */
    private fun helps(tag: Tag, wants: List<Set<String>>, traits: Set<String>): Int =
        wants.count { w -> w.none { it in traits } && w.any { it in tag.give } }

    fun forge(lib: TagLibrary, st: ForgeState): ForgeResult {
        val res = ForgeResult(st.seed)
        val traits = HashSet<String>()
        val forbidden = HashSet<String>()
        val slots = lib.ordered().filter { it.id !in st.disabled }
        val picked = HashMap<String, SlotResult>()
        val wants = ArrayList<Set<String>>()      // unmet needs of locked picks: later rolls are steered to satisfy them

        fun take(tag: Tag) { traits += tag.give; forbidden += tag.avoid }

        // 1) locks first: they constrain everything else
        for (s in slots) {
            val texts = (st.locks[s.id] ?: emptyList()).filter { PyText.strip(it).isNotEmpty() }
            if (texts.isEmpty()) continue
            var tier = 0
            for (t in texts) {
                val tag = lib.index[s.id]?.get(t)
                if (tag != null) {
                    take(tag)
                    tier = maxOf(tier, tag.tier)
                    for (n in tag.need) wants += setOf(n)
                    if (tag.anyOf.isNotEmpty()) wants += tag.anyOf
                }
            }
            picked[s.id] = SlotResult(s.id, texts, locked = true, tier = tier)
        }

        // 2) roll the rest in order
        for (s in slots) {
            if (s.id in picked || !eligible(s, st)) continue
            val rng = PyRandom.ofString("${st.seed}|${s.id}|${st.salts[s.id] ?: 0L}")
            if (s.chance < 1.0 && rng.random() >= s.chance) continue
            val k = rng.randint(s.lo.toLong(), s.hi.toLong())
            val chosen = ArrayList<Tag>()
            var iter = 0L
            while (iter < k) {
                iter++
                var pool = s.tags.filter { it.tier <= st.maxTier && weight(it, st) > 0 && it !in chosen && ok(it, traits, forbidden) }
                var relaxed = false
                if (pool.isEmpty() && !st.strict) {
                    pool = s.tags.filter { it.tier <= st.maxTier && weight(it, st) > 0 && it !in chosen && ok(it, traits, forbidden, relax = true) }
                    relaxed = pool.isNotEmpty()
                }
                if (pool.isNotEmpty() && wants.isNotEmpty()) {
                    val score = pool.map { helps(it, wants, traits) }
                    val best = score.max()
                    if (best != 0) pool = pool.filterIndexed { i, _ -> score[i] == best }
                }
                if (pool.isEmpty()) {
                    // slots with chance < 1 are optional: staying empty is normal
                    if (chosen.isEmpty() && s.chance >= 1.0) res.notes += "${s.label}: no option fits the rest of the roll, left empty"
                    break
                }
                val tag = pool[rng.chooseIndex(DoubleArray(pool.size) { weight(pool[it], st) })]
                if (relaxed) res.notes += "${s.label}: '${PyText.prefix(tag.text, 40)}' breaks a rule (loose mode)"
                take(tag)
                chosen += tag
            }
            if (chosen.isNotEmpty()) picked[s.id] = SlotResult(s.id, chosen.map { it.text }, tier = chosen.maxOf { it.tier })
        }

        for (s in slots) picked[s.id]?.let { res.picks[s.id] = it }
        res.traits += traits
        res.notes += audit(lib, res.picks.mapValues { it.value.texts })
        return res
    }

    /** Coherence check of a set of picks (used for hand-edited slots too). Returns readable notes. */
    fun audit(lib: TagLibrary, picks: Map<String, List<String>>): List<String> {
        val traits = HashSet<String>()
        val forbidden = HashMap<String, String>()
        val tags = ArrayList<Pair<Slot, Tag>>()
        for ((sid, texts) in picks) {
            val s = lib.slots[sid] ?: continue
            for (t in texts) {
                val tag = lib.index[sid]?.get(t) ?: continue
                tags += s to tag
                traits += tag.give
                for (a in tag.avoid) forbidden[a] = tag.text
            }
        }
        val notes = ArrayList<String>()
        for ((s, tag) in tags) {
            val own = tag.give
            val missing = (tag.need - traits).sortedWith(PyText.CODEPOINT_ORDER)
            if (missing.isNotEmpty()) notes += "${s.label}: '${PyText.prefix(tag.text, 40)}' wants ${missing.joinToString(", ")}"
            if (tag.anyOf.isNotEmpty() && tag.anyOf.none { it in traits }) {
                notes += "${s.label}: '${PyText.prefix(tag.text, 40)}' wants one of ${tag.anyOf.sortedWith(PyText.CODEPOINT_ORDER).joinToString(", ")}"
            }
            val clash = tag.avoid.filter { it in (traits - own) }.sortedWith(PyText.CODEPOINT_ORDER)
            if (clash.isNotEmpty()) notes += "${s.label}: '${PyText.prefix(tag.text, 40)}' clashes with ${clash.joinToString(", ")}"
            for (g in own.filter { it in forbidden }.sortedWith(PyText.CODEPOINT_ORDER)) {
                if (forbidden[g] != tag.text) {
                    notes += "${s.label}: '${PyText.prefix(tag.text, 40)}' gives $g, which '${PyText.prefix(forbidden.getValue(g), 32)}' rules out"
                }
            }
        }
        return notes.distinct()
    }

    /** Slot picks -> Compose box text (slot order, joined by each slot's separator then ', '). */
    fun toBoxes(lib: TagLibrary, res: ForgeResult): LinkedHashMap<String, String> {
        val byBox = LinkedHashMap<String, MutableList<String>>()
        for (s in lib.ordered()) {
            val p = res.picks[s.id]
            if (p != null && p.texts.isNotEmpty()) byBox.getOrPut(s.box) { ArrayList() } += p.texts.joinToString(s.sep)
        }
        if (res.tierUsed > 0 && "age" !in res.picks) byBox.getOrPut("subject") { ArrayList() }.add(0, ADULT_MARK)
        return byBox.mapValuesTo(LinkedHashMap()) { it.value.joinToString(", ") }
    }

    fun negativeAdd(res: ForgeResult): String = if (res.tierUsed > 0) ADULT_NEGATIVE else ""

    /** mode: replace | append | fill_empty. */
    fun mergeText(existing: String, new: String, mode: String): String {
        if (new.isEmpty()) return existing
        if (mode == "replace") return new
        if (mode == "fill_empty") return if (PyText.strip(existing).isNotEmpty()) existing else new
        if (PyText.strip(existing).isEmpty()) return new
        return PyText.rstrip(existing).trimEnd(',') + ", " + new
    }

    fun tierLabel(n: Int): String = TIER_NAMES[maxOf(0, minOf(n, TIER_NAMES.size - 1))]
}
