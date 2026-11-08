package promptbeast

/** User packs: names, strict decoding, size limits, the age guard, adults filtering, and merging into the Forge library. */
object UserPacksSuite {
    fun run(): GoldenSuite.Report {
        val r = GoldenSuite.Report("userpacks")
        fun b(s: String) = s.toByteArray(Charsets.UTF_8)

        r.check(UserPacks.stemFor("My Pack!.pbpack") == "my-pack" ) { "stem basic ${UserPacks.stemFor("My Pack!.pbpack")}" }
        r.check(UserPacks.stemFor("../../etc/passwd") == "passwd") { "stem traversal ${UserPacks.stemFor("../../etc/passwd")}" }
        r.check(UserPacks.stemFor("C:\\x\\Hats.PBPACK") == "hats") { "stem windows ${UserPacks.stemFor("C:\\x\\Hats.PBPACK")}" }
        r.check(UserPacks.stemFor(null) == "pack" && UserPacks.stemFor("") == "pack" && UserPacks.stemFor("***") == "pack" && UserPacks.stemFor(".pbpack") == "pack") { "stem empty" }
        r.check(UserPacks.stemFor("é.pbpack") == "pack" && UserPacks.stemFor("café.pbpack") == "caf") { "stem unicode ${UserPacks.stemFor("café.pbpack")}" }
        r.check(UserPacks.stemFor("a".repeat(100)).length == 40) { "stem length" }
        r.check(UserPacks.uniqueStem("hats", emptySet()) == "hats" && UserPacks.uniqueStem("hats", setOf("hats")) == "hats-2" &&
            UserPacks.uniqueStem("hats", setOf("hats", "hats-2")) == "hats-3") { "unique stem" }
        r.check(UserPacks.uniqueStem("a".repeat(40), setOf("a".repeat(40))).length <= 40) { "unique stem length" }

        r.check(UserPacks.decode(b("hello é")) == "hello é") { "decode ok" }
        r.check(UserPacks.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41)) == "A") { "decode strips BOM" }
        r.check(UserPacks.decode(byteArrayOf(0x41, 0xFF.toByte(), 0x42)) == null) { "decode rejects invalid bytes" }
        r.check(UserPacks.decode(byteArrayOf(0xC0.toByte(), 0x80.toByte())) == null) { "decode rejects overlong" }

        // the shipped template must itself be a valid, importable pack
        val t = UserPacks.check("template.pbpack", b(UserPacks.TEMPLATE), emptySet())
        r.check(t.ok && t.pack!!.slots.keys == setOf("headwear") && t.pack!!.slots["headwear"]!!.tags.size == 4 && t.pack!!.issues.isEmpty()) { "template ${t.message} ${t.pack?.issues}" }
        r.check(t.stem == "template" && t.message.startsWith("Imported template: 1 slot, 4 tags")) { "template message ${t.message}" }

        // refusals
        r.check(!UserPacks.check("x", ByteArray(UserPacks.MAX_BYTES + 1) { 'a'.code.toByte() }, emptySet()).ok) { "too big accepted" }
        r.check(UserPacks.check("x", ByteArray(UserPacks.MAX_BYTES) { ' '.code.toByte() }, emptySet()).message.contains("No tags")) { "max-size blank is a parse failure, not size" }
        r.check(UserPacks.check("x", byteArrayOf(0x40, 0xFF.toByte()), emptySet()).message.contains("UTF-8")) { "bad utf8 message" }
        r.check(UserPacks.check("x", b(""), emptySet()).message.contains("No tags")) { "empty" }
        r.check(UserPacks.check("x", b("@pack id=a name=a\n@slot s label=S box=main order=1\n"), emptySet()).message.contains("No tags")) { "slot with no tags" }
        r.check(UserPacks.check("x", b("just some words\nmore words\n"), emptySet()).message.contains("No tags")) { "not a pack" }
        r.check(!UserPacks.check("x", b(UserPacks.TEMPLATE), (1..UserPacks.MAX_PACKS).map { "p$it" }.toSet()).ok) { "pack count cap" }
        r.check(UserPacks.check("t", b(UserPacks.TEMPLATE), setOf("t")).stem == "t-2") { "name collision gets a new stem" }

        // the age guard applies to user packs: the tag is dropped and reported, the rest still loads
        val guarded = UserPacks.check("g", b("@pack id=g name=g adults=false\n@slot s label=S box=main order=1\na teen girl\nan adult woman\na schoolgirl outfit\nlittle girl\na tall man\n"), emptySet())
        val gp = guarded.pack!!
        r.check(guarded.ok && gp.slots["s"]!!.tags.map { it.text } == listOf("an adult woman", "a tall man")) { "age guard ${gp.slots["s"]?.tags?.map { it.text }}" }
        r.check(gp.issues.size == 3 && guarded.message.contains("3 notes")) { "age guard notes ${gp.issues}" }
        val allBad = UserPacks.check("g", b("@pack id=g name=g\n@slot s label=S box=main order=1\nloli\nchildlike\n"), emptySet())
        r.check(!allBad.ok) { "all-minor pack accepted" }

        // adult packs are kept out of the library unless asked for
        val adult = b("@pack id=ad name=ad adults=true\n@slot spicy label=S box=main order=1\nnude pose ; tier=nude\n")
        val plain = b(UserPacks.TEMPLATE)
        r.check(UserPacks.check("ad", adult, emptySet()).message.contains("18+")) { "adult note" }
        r.check(UserPacks.load(listOf("ad" to adult, "t" to plain), adults = false).map { it.id } == listOf("mine")) { "adult pack must stay hidden" }
        r.check(UserPacks.load(listOf("ad" to adult, "t" to plain), adults = true).map { it.id } == listOf("ad", "mine")) { "adult pack shown when asked" }

        val three = listOf("a" to plain, "b" to adult, "c" to byteArrayOf(0xFF.toByte()))
        r.check(UserPacks.load(three, true).size == 3 && UserPacks.load(three, false).size == 2) { "load pairs one pack per file when adults=true" }
        r.check(UserPacks.load(three, true).map { it.id } == listOf("mine", "ad", "c")) { "load order ${UserPacks.load(three, true).map { it.id }}" }

        // unreadable files never throw and show up as issues
        val bad = UserPacks.load(listOf("junk" to byteArrayOf(0xFF.toByte()), "big" to ByteArray(UserPacks.MAX_BYTES + 1)), true)
        r.check(bad.size == 2 && bad.all { it.issues.single().contains("unreadable") } && bad[0].issues[0].startsWith("junk.pbpack")) { "unreadable $bad" }

        // merging: a user pack's slot id that matches a built-in one adds tags, a new id adds a slot, issues flow through
        val base = parsePack("@pack id=base name=base\n@slot hair label=Hair box=subject order=3\nred hair\nblack hair\n", "base")
        val mine = UserPacks.load(listOf("mine" to b("@pack id=m name=m\n@slot hair label=Hair box=subject order=3\ngreen hair\n@slot hat label=Hat box=fashion order=50\na fez\nbad line ; w=nope\n")), true)
        val lib = TagLibrary(listOf(base) + mine)
        r.check(lib.slots["hair"]!!.tags.map { it.text } == listOf("red hair", "black hair", "green hair") && "hat" in lib.slots) { "merge ${lib.slots["hair"]?.tags?.map { it.text }}" }
        r.check(base.slots["hair"]!!.tags.size == 2) { "merge mutated the built-in pack" }
        r.check(lib.issues.isNotEmpty()) { "issues flow through" }
        return r
    }
}
