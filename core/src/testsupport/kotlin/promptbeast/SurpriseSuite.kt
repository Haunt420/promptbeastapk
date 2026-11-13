package promptbeast

import kotlin.random.Random

/** Forge's Surprise me helpers stay inside the rules: free slots only, tier limit, real tags, sensible counts. */
object SurpriseSuite {
    fun run(): GoldenSuite.Report {
        val r = GoldenSuite.Report("surprise")
        val pack = parsePack(
            "@pack id=t name=t adults=true\n@slot hair label=Hair box=subject order=3\nred hair\nblack hair\nhot pink hair ; tier=partner\n" +
                "@slot hat label=Hat box=fashion order=5\na fez\na cap\n@slot eyes label=Eyes box=subject order=4\nblue eyes\n@slot empty label=None box=misc order=9\n",
            "t",
        )
        val lib = TagLibrary(listOf(pack))
        val texts = lib.slots.mapValues { e -> e.value.tags.map { it.text }.toSet() }
        var heats = HashSet<Int>()
        for (seed in 0 until 60) {
            val rng = Random(seed)
            val h = Surprise.wildHeat(rng)
            heats.add(h)
            r.check(h in Surprise.HEAT_LO..Surprise.HEAT_HI) { "heat range $h" }

            val st = ForgeState(maxTier = 1, locks = hashMapOf("hat" to listOf("a top hat")), disabled = hashSetOf("eyes"))
            for (n in listOf(1, 3, 9)) {
                val picks = Surprise.oddballs(lib, st, Random(seed), n)
                r.check(picks.keys.all { it == "hair" }) { "only free slots: ${picks.keys}" }       // hat locked, eyes off, empty has no tags
                r.check(picks.size == 1) { "count capped by free slots ${picks.size} for $n" }
                for ((sid, t) in picks) {
                    r.check(t.size == 1 && t[0] in texts[sid]!! && t[0] != "hot pink hair") { "tag in slot and under tier limit: $t" }
                }
            }
            val open = ForgeState(maxTier = 3)
            val three = Surprise.oddballs(lib, open, Random(seed), Surprise.DARE_COUNT)
            r.check(three.size == 3 && three.keys.toSet() == setOf("hair", "hat", "eyes")) { "dare picks three: ${three.keys}" }
            r.check(Surprise.oddballs(lib, open, Random(seed), 0).isEmpty()) { "count 0" }
        }
        r.check(heats.size > 10) { "heat varies ${heats.size}" }
        val seen = HashSet<String>()
        repeat(40) { seen += Surprise.oddballs(lib, ForgeState(maxTier = 3), Random(it), 1).values.single().single() }
        r.check("hot pink hair" in seen && "a fez" in seen) { "uniform pool reaches every tag: $seen" }
        return r
    }
}
