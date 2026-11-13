package promptbeast

import kotlin.random.Random

/**
 * Helpers behind Forge's "Surprise me" buttons (port of core/forge/surprise.py). The dice are deliberately not the engine's seeded
 * ones: a surprise should differ every press. What the tests pin down is that it never leaves the rules - only switched-on,
 * unlocked slots are touched, only tags up to the tier in play are used, and minor-coded text never appears.
 */
object Surprise {
    const val HEAT_LO = 35          // Wild roll: how hot the dice may run (percent)
    const val HEAT_HI = 100
    const val ODD_COUNT = 1         // Odd one out
    const val DARE_COUNT = 3        // Dare me

    fun wildHeat(rng: Random): Int = rng.nextInt(HEAT_LO, HEAT_HI + 1)

    /** Every distinct tag text of the slot up to the tier limit (a uniform pool: rare tags count as much as common ones). */
    fun eligible(slot: Slot, maxTier: Int): List<String> =
        slot.tags.filter { it.tier <= maxTier && !PackParser.ageGuardHit(it.text) }.map { it.text }.distinct()

    /** `count` random free slots, each with one uniformly random tag (weights ignored, so the odd ones surface). */
    fun oddballs(lib: TagLibrary, state: ForgeState, rng: Random, count: Int): Map<String, List<String>> {
        val free = lib.slots.keys.filter { it !in state.disabled && it !in state.locks && eligible(lib.slots[it]!!, state.maxTier).isNotEmpty() }.sorted()
        val out = LinkedHashMap<String, List<String>>()
        for (sid in free.shuffled(rng).take(minOf(count, free.size))) out[sid] = listOf(eligible(lib.slots[sid]!!, state.maxTier).random(rng))
        return out
    }
}
