package promptbeast

/** Built-in + user packs merged by slot id (first pack's slot settings win; tags accumulate). Never throws. */
class TagLibrary(val packs: List<Pack>) {
    val slots = LinkedHashMap<String, Slot>()
    val issues = ArrayList<String>()
    val index: Map<String, Map<String, Tag>>

    init {
        for (p in packs) issues += p.issues
        for (p in packs) {
            for ((sid, s) in p.slots) {
                val have = slots[sid]
                if (have != null) have.tags += s.tags else slots[sid] = s.copyWithTags()
            }
        }
        val given = HashSet<String>()
        for (s in slots.values) for (t in s.tags) given += t.give
        for (s in slots.values) {
            for (tag in s.tags) {
                for (t in ((tag.need + tag.anyOf) - given).sortedWith(PyText.CODEPOINT_ORDER)) {
                    issues += "${s.id}: '${PyText.prefix(tag.text, 32)}' needs trait '$t' that nothing gives"
                }
            }
        }
        index = slots.mapValuesTo(LinkedHashMap()) { (_, s) ->
            val m = LinkedHashMap<String, Tag>()
            for (t in s.tags) m[t.text] = t        // later duplicates win, like the Python dict
            m
        }
    }

    fun ordered(): List<Slot> = slots.values.sortedWith(compareBy<Slot> { it.order }.thenBy(PyText.CODEPOINT_ORDER) { it.id })

    val hasAdult: Boolean get() = packs.any { it.adults }

    fun maxTier(): Int = slots.values.flatMap { it.tags }.maxOfOrNull { it.tier } ?: 0

    companion object {
        val BUILTIN = listOf("base", "adult")

        /** Text of a built-in pack shipped inside this module (null when absent). */
        fun builtinText(name: String): String? =
            TagLibrary::class.java.getResourceAsStream("/packs/$name.pbpack")?.use { String(it.readBytes(), Charsets.UTF_8) }

        /** Built-ins (adult one only when [adults]) plus optional extra (user) packs given as (name, text). */
        fun load(adults: Boolean = false, user: List<Pair<String, String>> = emptyList()): TagLibrary {
            val packs = ArrayList<Pack>()
            for (name in BUILTIN) {
                val text = builtinText(name) ?: continue
                val p = parsePack(text, name)
                if (p.adults && !adults) continue
                packs += p
            }
            for ((name, text) in user) {
                val p = parsePack(text, name)
                if (p.adults && !adults) continue
                packs += p
            }
            return TagLibrary(packs)
        }
    }
}
