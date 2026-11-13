package promptbeast

import kotlin.random.Random

/**
 * Helpers behind Forge's "Surprise me" buttons (port of core/forge/surprise.py). The dice are deliberately not the engine's seeded
 * ones: a surprise should differ every press. What the tests pin down is that it never leaves the rules - only switched-on,
 * unlocked slots are touched, only tags up to the tier in play are used, and minor-coded text never appears.
 *
 * Flavour: "any" (everything up to the tier limit), "spicy" (adult-tier options favoured, plain ones still possible) and
 * "nsfw" (adult-tier options only: slots without any are left alone, and a spice pass pins a few on top). Spicy and nsfw need
 * adult packs on (tier limit >= 1); without them there is nothing to draw from.
 */
object Surprise {
    val FLAVOURS = listOf("any" to "Any", "spicy" to "Spicy", "nsfw" to "NSFW only")
    private val HEAT_RANGE = mapOf("any" to (35..100), "spicy" to (60..100), "nsfw" to (80..100))   // Wild roll, percent
    const val ODD_COUNT = 1
    const val DARE_COUNT = 3
    const val RARE_COUNT = 2
    const val SPICE_COUNT = 3
    const val LUCKY_COUNT = 3
    const val HEAT_STEP = 25
    const val SPICY_ADULT_CHANCE = 0.75
    const val RARE_SHARE = 0.25

    fun heatFloor(flavour: String): Int = (HEAT_RANGE[flavour] ?: HEAT_RANGE.getValue("any")).first
    fun wildHeat(rng: Random, flavour: String = "any"): Int {
        val r = HEAT_RANGE[flavour] ?: HEAT_RANGE.getValue("any")
        return rng.nextInt(r.first, r.last + 1)
    }

    private fun tags(slot: Slot, maxTier: Int, flavour: String): List<Tag> {
        val seen = LinkedHashMap<String, Tag>()
        for (t in slot.tags) {
            if (t.tier > maxTier || PackParser.ageGuardHit(t.text)) continue
            if (flavour == "nsfw" && t.tier < 1) continue
            seen.putIfAbsent(t.text, t)
        }
        return seen.values.toList()
    }

    /** Every distinct tag text of the slot the flavour allows, up to the tier limit (a uniform pool: rare tags count as much as common ones). */
    fun eligible(slot: Slot, maxTier: Int, flavour: String = "any"): List<String> = tags(slot, maxTier, flavour).map { it.text }

    fun freeSlots(lib: TagLibrary, state: ForgeState, flavour: String = "any"): List<String> =
        lib.slots.keys.filter { it !in state.disabled && it !in state.locks && eligible(lib.slots[it]!!, state.maxTier, flavour).isNotEmpty() }.sorted()

    private fun draw(pool: List<Tag>, rng: Random, flavour: String): String {
        var p = pool
        if (flavour == "spicy") {
            val adult = pool.filter { it.tier >= 1 }
            if (adult.isNotEmpty() && rng.nextDouble() < SPICY_ADULT_CHANCE) p = adult
        }
        return p.random(rng).text
    }

    /** `count` random free slots, each with one uniformly random tag (weights ignored, so the odd ones surface). */
    fun oddballs(lib: TagLibrary, state: ForgeState, rng: Random, count: Int, flavour: String = "any"): Map<String, List<String>> {
        val free = freeSlots(lib, state, flavour)
        val out = LinkedHashMap<String, List<String>>()
        for (sid in free.shuffled(rng).take(minOf(count, free.size))) out[sid] = listOf(draw(tags(lib.slots[sid]!!, state.maxTier, flavour), rng, flavour))
        return out
    }

    /** Like [oddballs], but each pin comes from the lightest quarter of the slot's options by weight: the real rarities. */
    fun rare(lib: TagLibrary, state: ForgeState, rng: Random, count: Int, flavour: String = "any"): Map<String, List<String>> {
        val free = freeSlots(lib, state, flavour)
        val out = LinkedHashMap<String, List<String>>()
        for (sid in free.shuffled(rng).take(minOf(count, free.size))) {
            val sorted = tags(lib.slots[sid]!!, state.maxTier, flavour).sortedWith(compareBy<Tag>({ it.weight }, { it.text }))
            out[sid] = listOf(sorted.take(maxOf(1, (sorted.size * RARE_SHARE).toInt())).random(rng).text)
        }
        return out
    }

    /** The nsfw pass: adult-tier options on a few free slots that have any. */
    fun spice(lib: TagLibrary, state: ForgeState, rng: Random): Map<String, List<String>> = oddballs(lib, state, rng, SPICE_COUNT, "nsfw")

    /** About half of the free slots (at least one): these get a new roll, the rest keep what they have. */
    fun remixSlots(lib: TagLibrary, state: ForgeState, rng: Random): List<String> {
        val free = lib.slots.keys.filter { it !in state.disabled && it !in state.locks }.sorted()
        return if (free.isEmpty()) emptyList() else free.shuffled(rng).take(minOf(free.size, maxOf(1, free.size / 2)))
    }

    /** Freeze [count] of the current picks (free slots that have text) so the next rolls build around them. */
    fun luckyLocks(picks: Map<String, List<String>>, state: ForgeState, rng: Random, count: Int = LUCKY_COUNT): Map<String, List<String>> {
        val free = picks.filter { (sid, t) -> t.isNotEmpty() && sid !in state.disabled && sid !in state.locks }.keys.sorted()
        val out = LinkedHashMap<String, List<String>>()
        for (sid in free.shuffled(rng).take(minOf(count, free.size))) out[sid] = picks.getValue(sid)
        return out
    }
}
