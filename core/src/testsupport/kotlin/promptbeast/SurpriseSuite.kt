package promptbeast

import kotlin.random.Random

/** Forge's Surprise me helpers stay inside the rules: free slots only, tier limit, flavours, real tags, sensible counts. */
object SurpriseSuite {
    fun run(): GoldenSuite.Report {
        val r = GoldenSuite.Report("surprise")
        val base = parsePack("@pack id=b name=b\n@slot hair label=Hair\nred hair ; w=5\nblack hair ; w=5\nteal hair ; w=0.1\nblonde hair ; w=5\n@slot hat label=Hat\na fez\n@slot eyes label=Eyes\nblue eyes\n@slot empty label=None\n", "b")
        val adult = parsePack("@pack id=a name=a adults=true tier=sensual\n@slot hair label=Hair\nwet hair ; tier=nude\n@slot acts label=Acts\nkissing\nsomething explicit ; tier=explicit\n", "a")
        val lib = TagLibrary(listOf(base, adult))
        val tierOf = { sid: String, t: String -> lib.slots[sid]!!.tags.first { it.text == t }.tier }

        val heats = HashSet<Int>()
        for (seed in 0 until 60) {
            val rng = Random(seed)
            for ((f, lo) in listOf("any" to 35, "spicy" to 60, "nsfw" to 80)) {
                val h = Surprise.wildHeat(Random(seed), f)
                r.check(h in lo..100 && Surprise.heatFloor(f) == lo) { "heat range $f $h" }
            }
            heats += Surprise.wildHeat(rng)

            // base only (tier 0): locked / off / empty slots are left alone, counts are capped by what is free
            val st = ForgeState(maxTier = 0, locks = hashMapOf("hat" to listOf("a top hat")), disabled = hashSetOf("eyes"))
            for (n in listOf(1, 3, 9)) {
                val picks = Surprise.oddballs(lib, st, Random(seed), n)
                r.check(picks.keys == setOf("hair")) { "only free slots: ${picks.keys}" }
                r.check(picks.values.single().single().let { it in lib.slots["hair"]!!.tags.map { t -> t.text } && tierOf("hair", it) == 0 }) { "tier limit $picks" }
            }
            val three = Surprise.oddballs(lib, ForgeState(maxTier = 4), Random(seed), Surprise.DARE_COUNT)
            r.check(three.size == 3) { "dare picks three: ${three.keys}" }
            r.check(Surprise.oddballs(lib, ForgeState(maxTier = 4), Random(seed), 0).isEmpty()) { "count 0" }

            // nsfw: adult-tier options only, slots with none are left alone
            val open = ForgeState(maxTier = 4)
            r.check(Surprise.freeSlots(lib, open, "nsfw") == listOf("acts", "hair")) { "nsfw free ${Surprise.freeSlots(lib, open, "nsfw")}" }
            for ((sid, t) in Surprise.oddballs(lib, open, Random(seed), 9, "nsfw")) r.check(tierOf(sid, t[0]) >= 1) { "nsfw pick $sid $t" }
            r.check(Surprise.spice(lib, open, Random(seed)).size == 2) { "spice limited by adult slots" }
            r.check(Surprise.eligible(lib.slots["acts"]!!, 1) == listOf("kissing")) { "tier limit on adult tags" }

            // rare: the lightest quarter of hair (4 base tags at tier 0 -> 1 tag) is always the 0.1 one
            r.check(Surprise.rare(lib, ForgeState(maxTier = 0, locks = hashMapOf("hat" to listOf("x"), "eyes" to listOf("y"))), Random(seed), 1) == mapOf("hair" to listOf("teal hair"))) { "rare" }

            // remix / lucky
            val st2 = ForgeState(maxTier = 4, locks = hashMapOf("hat" to listOf("a fez")), disabled = hashSetOf("acts", "eyes", "empty"))
            r.check(Surprise.remixSlots(lib, st2, Random(seed)) == listOf("hair")) { "remix ${Surprise.remixSlots(lib, st2, Random(seed))}" }
            r.check(Surprise.luckyLocks(mapOf("hair" to listOf("red hair"), "hat" to listOf("a fez"), "acts" to listOf("kissing"), "empty" to emptyList()), st2, Random(seed)) ==
                mapOf("hair" to listOf("red hair"))) { "lucky" }
        }
        r.check(heats.size > 10) { "heat varies ${heats.size}" }
        var adultSeen = 0; var plainSeen = 0
        for (s in 0 until 80) {
            val t = Surprise.oddballs(lib, ForgeState(maxTier = 4), Random(s), 9, "spicy")["hair"]!![0]
            if (tierOf("hair", t) >= 1) adultSeen++ else plainSeen++
        }
        r.check(adultSeen > 0 && plainSeen > 0 && adultSeen > plainSeen) { "spicy leans adult: $adultSeen/$plainSeen" }
        return r
    }
}
