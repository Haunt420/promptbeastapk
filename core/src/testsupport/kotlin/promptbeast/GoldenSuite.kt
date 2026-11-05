package promptbeast

import kotlinx.serialization.json.*
import java.io.File
import java.math.BigInteger

/** Framework-free golden-vector checks, shared by the Gradle JUnit test and the local runner. Each returns failures. */
object GoldenSuite {
    val dir: File = File(System.getProperty("pb.golden") ?: "core/src/test/resources/golden")

    /** Where vector bytes come from: files by default, the APK's assets on a device (set by the app). */
    @Volatile var source: (String) -> ByteArray = { name -> File(dir, "$name.json.gz").readBytes() }

    /** Gzip or plain JSON bytes (the Android build strips ".gz" from asset names and may unpack them). */
    fun load(name: String): JsonObject {
        val raw = source(name)
        val gz = raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
        val bytes = if (gz) java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
        return Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    }

    /** Failure text with every non-ASCII char as \\uXXXX (terminals and JVM stdout encodings mangle them otherwise). */
    private fun ascii(s: String) = buildString { for (c in s) if (c.code in 32..126 || c == '\n') append(c) else append("\\u%04x".format(c.code)) }

    class Report(val name: String) {
        var checked = 0
        var failed = 0
        val failures = mutableListOf<String>()
        private val perKind = LinkedHashMap<String, Int>()
        /** Counts every failure; keeps the first few messages of each kind (a message's first word) so one noisy kind can't hide another. */
        fun check(ok: Boolean, what: () -> String) {
            checked++
            if (ok) return
            failed++
            val msg = ascii(what())
            val kind = msg.substringBefore(' ')
            val n = (perKind[kind] ?: 0) + 1
            perKind[kind] = n
            if (n <= 6 && failures.size < 60) failures += msg
        }
        override fun toString() = "$name: $checked checks, $failed failures" +
            (if (perKind.isEmpty()) "" else perKind.entries.joinToString(", ", " (", ")") { "${it.key}:${it.value}" })
    }

    private fun bitsToDouble(s: String): Double = Double.fromBits(s.toLong())

    fun rng(): Report {
        val rep = Report("rng")
        val cases = load("rng")["cases"]!!.jsonArray
        for ((ci, c) in cases.withIndex()) {
            val o = c.jsonObject
            val sd = o["seed"]!!.jsonObject
            val r = if ("int" in sd) PyRandom.ofBig(BigInteger(sd["int"]!!.jsonPrimitive.content))
                    else PyRandom.ofString(sd["str"]!!.jsonPrimitive.content)
            val ops = o["ops"]!!.jsonArray
            val out = o["out"]!!.jsonArray
            for (i in ops.indices) {
                val op = ops[i].jsonObject
                val want = out[i].jsonPrimitive.content
                val got = when (op["op"]!!.jsonPrimitive.content) {
                    "random" -> r.random().toRawBits().toString()
                    "bits" -> r.getrandbits(op["k"]!!.jsonPrimitive.int).toString()
                    "randbelow" -> r.randbelow(op["n"]!!.jsonPrimitive.content.toLong()).toString()
                    "randint" -> r.randint(op["a"]!!.jsonPrimitive.content.toLong(), op["b"]!!.jsonPrimitive.content.toLong()).toString()
                    "choices" -> r.chooseIndex(DoubleArray(op["w"]!!.jsonArray.size) { bitsToDouble(op["w"]!!.jsonArray[it].jsonPrimitive.content) }).toString()
                    else -> "?"
                }
                rep.check(got == want) { "rng case $ci seed=$sd op#$i ${op} want=$want got=$got" }
            }
        }
        return rep
    }

    private fun JsonElement.ranges(): List<IntRange> = jsonArray.map { it.jsonArray[0].jsonPrimitive.int..it.jsonArray[1].jsonPrimitive.int }

    /** Python's Unicode tables vs the JVM's: whitespace, decimal digits, word chars, digit values over ALL code points. */
    fun charclass(): Report {
        val rep = Report("charclass")
        val o = load("charclass")
        val space = o["space"]!!.ranges(); val digit = o["digit"]!!.ranges(); val word = o["word"]!!.ranges()
        val values = o["digit_values"]!!.jsonObject
        fun inAny(rs: List<IntRange>, cp: Int) = rs.any { cp in it }
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            // Python (Unicode ${o["unidata"]}) may know newer characters than this JVM; those can't be matched, only tolerated.
            if (!Character.isDefined(cp)) continue
            rep.check(PyText.isSpace(cp) == inAny(space, cp)) { "isSpace U+%04X want=%s".format(cp, inAny(space, cp)) }
            rep.check(PyText.isDecimal(cp) == inAny(digit, cp)) { "isDecimal U+%04X want=%s".format(cp, inAny(digit, cp)) }
            rep.check(PyText.isWord(cp) == inAny(word, cp)) { "isWord U+%04X want=%s".format(cp, inAny(word, cp)) }
        }
        for ((k, v) in values) {
            if (!Character.isDefined(k.toInt())) continue // newer than this runtime's Unicode (JDK 17 = 13, Android 8 = 10)
            rep.check(Character.digit(k.toInt(), 10) == v.jsonPrimitive.int) { "digit value U+%04X".format(k.toInt()) }
        }
        val cased = o["cased"]!!.ranges(); val ign = o["case_ignorable"]!!.ranges(); val lowers = o["lower"]!!.jsonObject
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF || !Character.isDefined(cp)) continue
            rep.check(PyText.isCaseIgnorable(cp) == inAny(ign, cp)) { "isCaseIgnorable U+%04X want=%s".format(cp, inAny(ign, cp)) }
            if (!inAny(ign, cp)) rep.check(PyText.isCased(cp) == inAny(cased, cp)) { "isCased U+%04X want=%s".format(cp, inAny(cased, cp)) }
            val str = String(Character.toChars(cp))
            val want = if (cp == 0x3A3) "\u03c3" else lowers[cp.toString()]?.jsonPrimitive?.content ?: str
            rep.check(PyText.lower(str) == want) { "lower U+%04X got=%s want=%s".format(cp, PyText.lower(str), want) }
        }
        return rep
    }

    /** `{"t": text}` or `{"h": sha256, "n": code points}` (long outputs). */
    fun encMatches(got: String, enc: JsonObject): Boolean {
        if ("t" in enc) return got == enc["t"]!!.jsonPrimitive.content
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(got.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return sha == enc["h"]!!.jsonPrimitive.content && PyText.length(got) == enc["n"]!!.jsonPrimitive.int
    }

    private fun strMap(o: JsonObject) = o.mapValues { it.value.jsonPrimitive.content }

    fun dsl(): Report {
        val rep = Report("dsl")
        val o = load("dsl")
        for ((ci, c) in o["cases"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val text = co["text"]!!.jsonPrimitive.content
            val ev = Evaluator(strMap(co["vars"]!!.jsonObject), PyRandom.ofInt(co["seed"]!!.jsonPrimitive.long),
                co["sticky"]!!.jsonPrimitive.boolean)
            val r = ev.result(text)
            val wantU = co["unresolved"]!!.jsonArray.map { it.jsonPrimitive.content }
            val wantE = co["errors"]!!.jsonArray.map { it.jsonPrimitive.content }
            rep.check(encMatches(r.text, co["out"]!!.jsonObject)) { "dsl#$ci text=${text.take(80)} seed=${co["seed"]} got=${r.text.take(80)} want=${co["out"].toString().take(120)}" }
            rep.check(r.unresolved.sorted() == wantU) { "dsl#$ci unresolved got=${r.unresolved.sorted()} want=$wantU" }
            rep.check(r.errors == wantE) { "dsl#$ci errors got=${r.errors} want=$wantE (text=${text.take(60)})" }
        }
        for ((i, c) in o["parse_variables"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val got = Dsl.parseVariables(co["text"]!!.jsonPrimitive.content)
            val want = strMap(co["vars"]!!.jsonObject)
            rep.check(got == want) { "parse_variables#$i text=${co["text"]} got=$got want=$want" }
        }
        return rep
    }

    fun weights(): Report {
        val rep = Report("weights")
        for ((i, c) in load("weights")["cases"]!!.jsonArray.withIndex()) {
            val o = c.jsonObject
            val t = o["text"]!!.jsonPrimitive.content
            val (out, n) = Weights.stripWeights(t, o["emphasis"]!!.jsonPrimitive.boolean)
            rep.check(out == o["out"]!!.jsonPrimitive.content && n == o["n"]!!.jsonPrimitive.int) { "weights#$i text=$t got=$out/$n want=${o["out"]}/${o["n"]}" }
            rep.check(Weights.hasWeights(t) == o["has"]!!.jsonPrimitive.boolean) { "weights#$i has_weights text=$t" }
        }
        return rep
    }

    fun articles(): Report {
        val rep = Report("articles")
        for ((i, c) in load("articles")["cases"]!!.jsonArray.withIndex()) {
            val o = c.jsonObject
            val t = o["text"]!!.jsonPrimitive.content
            val got = Articles.fixArticles(t)
            rep.check(got == o["out"]!!.jsonPrimitive.content) { "articles#$i text=${t.replace("\n", "\\n")} got=$got want=${o["out"]}" }
        }
        return rep
    }

    fun profiles(): Report {
        val rep = Report("profiles")
        val o = load("profiles")
        for ((k, v) in o["profiles"]!!.jsonObject) {
            val a = v.jsonArray
            val p = Profiles.ALL[k]
            rep.check(p != null && p.id == a[0].jsonPrimitive.content && p.name == a[1].jsonPrimitive.content &&
                p.prose == a[2].jsonPrimitive.boolean && p.readsWeights == a[3].jsonPrimitive.boolean &&
                p.negative == a[4].jsonPrimitive.content && p.note == a[5].jsonPrimitive.content) { "profile $k differs" }
        }
        for ((k, v) in o["lookups"]!!.jsonObject) rep.check(Profiles.get(k).id == v.jsonPrimitive.content) { "lookup '$k'" }
        return rep
    }

    fun compose(): Report {
        val rep = Report("compose")
        val o = load("compose")
        for ((i, c) in o["cases"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val pr = Project.fromJson(co["project"]!!.jsonObject)
            rep.check(pr.toJson() == co["project"]!!.jsonObject) { "compose#$i project round-trip differs" }
            val a = Composer.assemble(pr, co["seed"]!!.jsonPrimitive.long)
            rep.check(encMatches(a.prompt, co["prompt"]!!.jsonObject)) { "compose#$i seed=${co["seed"]} prompt got=${a.prompt.take(100)} want=${co["prompt"].toString().take(140)}" }
            rep.check(encMatches(a.negative, co["negative"]!!.jsonObject)) { "compose#$i negative got=${a.negative.take(100)} want=${co["negative"].toString().take(140)}" }
            rep.check(a.parts == strMap(co["parts"]!!.jsonObject)) { "compose#$i parts got=${a.parts} want=${co["parts"]}" }
            rep.check(a.unresolved == co["unresolved"]!!.jsonArray.map { it.jsonPrimitive.content }) { "compose#$i unresolved" }
            rep.check(a.errors == co["errors"]!!.jsonArray.map { it.jsonPrimitive.content }) { "compose#$i errors got=${a.errors} want=${co["errors"]}" }
            rep.check(a.weightsConverted == co["converted"]!!.jsonPrimitive.int) { "compose#$i converted got=${a.weightsConverted} want=${co["converted"]}" }
        }
        for ((i, c) in o["rolls"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val rs = Composer.roll(Project.fromJson(co["project"]!!.jsonObject), co["n"]!!.jsonPrimitive.int, co["seed"]!!.jsonPrimitive.long)
            rep.check(rs.map { it.prompt } == co["prompts"]!!.jsonArray.map { it.jsonPrimitive.content }) { "roll#$i prompts" }
            rep.check(rs.map { it.seed } == co["seeds"]!!.jsonArray.map { it.jsonPrimitive.long }) { "roll#$i seeds" }
        }
        return rep
    }

    fun lint(): Report {
        val rep = Report("lint")
        for ((i, c) in load("lint")["cases"]!!.jsonArray.withIndex()) {
            val o = c.jsonObject
            val t = o["text"]!!.jsonPrimitive.content
            val vars = (o["vars"] as? JsonObject)?.let { strMap(it) }
            val got = Lint.lint(t, o["profile"]!!.jsonPrimitive.content, vars, o["scratch"]!!.jsonPrimitive.content, o["neg"]!!.jsonPrimitive.boolean)
                .map { listOf(it.severity, it.code, it.message, it.start.toString(), it.end.toString()) }
            val want = o["issues"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.content } }
            rep.check(got == want) { "lint#$i text=${t.take(90).replace("\n", "\\n")} profile=${o["profile"]} neg=${o["neg"]}\n   got=$got\n   want=$want" }
        }
        return rep
    }

    fun export(): Report {
        val rep = Report("export")
        val o = load("export")
        for ((i, c) in o["cases"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val pr = Project.fromJson(co["project"]!!.jsonObject)
            val inc = co["scratch"]!!.jsonPrimitive.boolean
            val mk = Export.mackKey(pr, inc)
            val want = co["mack"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
            rep.check(mk.toList() == want) { "export#$i mack_key got=$mk want=$want" }
            rep.check(Export.toJson(mk) == co["json_pretty"]!!.jsonPrimitive.content) { "export#$i pretty json" }
            rep.check(Export.toJson(mk, pretty = false) == co["json_compact"]!!.jsonPrimitive.content) { "export#$i compact json" }
            val pyBlob = java.util.Base64.getDecoder().decode(co["blob"]!!.jsonPrimitive.content)
            rep.check(Export.fromShareBlob(pyBlob).toList() == want) { "export#$i python blob decode" }
            rep.check(Export.fromShareBlob(Export.shareBlob(mk)).toList() == want) { "export#$i kotlin blob round-trip" }
            (co["blocks"] as? JsonArray)?.let { b ->
                val w = b.map { r -> r.jsonArray.map { it.jsonPrimitive.content } }
                rep.check(Export.copyBlocks(pr).map { listOf(it.first, it.second, it.third) } == w) { "export#$i copy_blocks" }
            }
        }
        for ((i, c) in o["from_dict"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val got = Project.fromJson(co["in"]!!.jsonObject).toJson()
            rep.check(got == co["out"]!!.jsonObject) { "from_dict#$i in=${co["in"]} got=$got want=${co["out"]}" }
        }
        return rep
    }

    // ---- forge ---------------------------------------------------------------------------------------------------
    private fun sortedJson(xs: Collection<String>) = JsonArray(xs.sortedWith(PyText.CODEPOINT_ORDER).map { JsonPrimitive(it) })

    fun dumpSlot(s: Slot): JsonObject = buildJsonObject {
        put("id", s.id); put("label", s.label); put("box", s.box); put("order", s.order)
        put("chance", s.chance.toRawBits().toString()); put("lo", s.lo); put("hi", s.hi); put("sep", s.sep)
        put("tier", s.tier?.let { JsonPrimitive(it) } ?: JsonNull)
        putJsonArray("tags") {
            for (t in s.tags) add(buildJsonObject {
                put("text", t.text); put("w", t.weight.toRawBits().toString()); put("give", sortedJson(t.give))
                put("need", sortedJson(t.need)); put("any", sortedJson(t.anyOf)); put("not", sortedJson(t.avoid))
                put("tier", t.tier); put("pack", t.pack)
            })
        }
    }

    fun dumpPack(p: Pack): JsonObject = buildJsonObject {
        put("id", p.id); put("name", p.name); put("adults", p.adults); put("tier", p.tier)
        putJsonArray("issues") { p.issues.forEach { add(it) } }
        putJsonArray("slots") { p.slots.values.forEach { add(dumpSlot(it)) } }
    }

    fun dumpLib(l: TagLibrary): JsonObject = buildJsonObject {
        putJsonArray("issues") { l.issues.forEach { add(it) } }
        putJsonArray("slots") { l.slots.values.forEach { add(dumpSlot(it)) } }
        putJsonArray("ordered") { l.ordered().forEach { add(it.id) } }
        put("has_adult", l.hasAdult); put("max_tier", l.maxTier())
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun libFromSpec(spec: JsonObject): TagLibrary {
        val user = spec["user"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
        val adults = spec["adults"]!!.jsonPrimitive.boolean
        return if (spec["builtin"]!!.jsonPrimitive.boolean) TagLibrary.load(adults, user)
        else TagLibrary(user.map { parsePack(it.second, it.first) }.filter { !it.adults || adults })
    }

    private fun stateFrom(o: JsonObject) = ForgeState(
        seed = o["seed"]!!.jsonPrimitive.long,
        salts = o["salts"]!!.jsonObject.mapValuesTo(HashMap()) { it.value.jsonPrimitive.long },
        locks = o["locks"]!!.jsonObject.mapValuesTo(HashMap()) { e -> e.value.jsonArray.map { it.jsonPrimitive.content } },
        disabled = o["disabled"]!!.jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content },
        maxTier = o["max_tier"]!!.jsonPrimitive.int, heat = o["heat"]!!.jsonPrimitive.double, strict = o["strict"]!!.jsonPrimitive.boolean)

    fun forge(): Report {
        val rep = Report("forge")
        val o = load("forge")
        for ((i, c) in o["parse"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val got = dumpPack(parsePack(co["text"]!!.jsonPrimitive.content, co["source"]!!.jsonPrimitive.content))
            rep.check(got == co["dump"]) { "parse#$i text=${co["text"]!!.jsonPrimitive.content.take(200)}\n  got=$got\n  want=${co["dump"]}" }
        }
        for ((i, c) in o["age_guard"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val t = co["text"]!!.jsonPrimitive.content
            rep.check(PackParser.ageGuardHit(t) == co["hit"]!!.jsonPrimitive.boolean) { "age_guard#$i '$t' want=${co["hit"]}" }
        }
        val b = o["builtin"]!!.jsonObject
        for ((name, sha) in b["file_sha"]!!.jsonObject) {
            val text = TagLibrary.builtinText(name)
            rep.check(text != null && sha256(text.toByteArray(Charsets.UTF_8)) == sha.jsonPrimitive.content) {
                "built-in pack '$name' differs from the desktop's (run tools/sync-packs.sh)" }
            if (text != null) rep.check(dumpPack(parsePack(text, name)) == b["packs"]!!.jsonObject[name]!!.jsonObject["dump"]) { "builtin pack $name parse" }
        }
        for ((i, c) in o["libs"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val lib = libFromSpec(co["spec"]!!.jsonObject)
            rep.check(dumpLib(lib) == co["dump"]) { "lib#$i dump differs (issues got=${lib.issues.take(3)})" }
            for ((j, sc) in co["states"]!!.jsonArray.withIndex()) {
                val so = sc.jsonObject
                val r = Forge.forge(lib, stateFrom(so["state"]!!.jsonObject))
                val w = so["result"]!!.jsonObject
                val gotPicks = r.picks.values.map { listOf(it.slot, it.texts.toString(), it.locked.toString(), it.tier.toString()) }
                val wantPicks = w["picks"]!!.jsonArray.map { p -> val a = p.jsonArray
                    listOf(a[0].jsonPrimitive.content, a[1].jsonArray.map { it.jsonPrimitive.content }.toString(), a[2].jsonPrimitive.content, a[3].jsonPrimitive.content) }
                rep.check(gotPicks == wantPicks) { "lib#$i state#$j picks\n  got=$gotPicks\n  want=$wantPicks" }
                rep.check(r.traits.sortedWith(PyText.CODEPOINT_ORDER) == w["traits"]!!.jsonArray.map { it.jsonPrimitive.content }) { "lib#$i state#$j traits" }
                rep.check(r.notes == w["notes"]!!.jsonArray.map { it.jsonPrimitive.content }) { "lib#$i state#$j notes got=${r.notes} want=${w["notes"]}" }
                rep.check(r.tierUsed == w["tier_used"]!!.jsonPrimitive.int) { "lib#$i state#$j tier_used" }
                rep.check(Forge.toBoxes(lib, r).toList() == w["boxes"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }) { "lib#$i state#$j boxes" }
                rep.check(Forge.negativeAdd(r) == w["neg"]!!.jsonPrimitive.content) { "lib#$i state#$j negative" }
                val ah = so["audit_hand"]!!.jsonObject
                val hand = LinkedHashMap<String, List<String>>()
                for (kv in ah["picks"]!!.jsonArray) hand[kv.jsonArray[0].jsonPrimitive.content] = kv.jsonArray[1].jsonArray.map { it.jsonPrimitive.content }
                rep.check(Forge.audit(lib, hand) == ah["notes"]!!.jsonArray.map { it.jsonPrimitive.content }) { "lib#$i state#$j audit" }
            }
        }
        for ((i, c) in o["merge_text"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            rep.check(Forge.mergeText(co["e"]!!.jsonPrimitive.content, co["n"]!!.jsonPrimitive.content, co["m"]!!.jsonPrimitive.content) == co["out"]!!.jsonPrimitive.content) { "merge_text#$i $co" }
        }
        for (c in o["tier_label"]!!.jsonArray) {
            val co = c.jsonObject
            rep.check(Forge.tierLabel(co["n"]!!.jsonPrimitive.int) == co["out"]!!.jsonPrimitive.content) { "tier_label $co" }
        }
        return rep
    }

    // ---- library ---------------------------------------------------------------------------------------------------
    private fun snapLine(e: Entry, withCreated: Boolean = true): String = listOf(
        e.id.toString(), e.kind, if (e.fav) "1" else "0", if (e.archived != null) "1" else "0", if (withCreated) e.created.toRawBits().toString() else "",
        e.tags.joinToString(" "), e.title, e.note, e.prompt, e.negative, e.seed?.toString() ?: "None", e.project["profile"]?.jsonPrimitive?.content ?: "",
        PyJson.dumps(e.project, sortKeys = true)).joinToString("\u001f")

    private fun snapshot(store: MemoryStore, withCreated: Boolean = true): String = store.allById().joinToString("\u001e") { snapLine(it, withCreated) }

    private fun tagsArg(e: JsonElement): List<String> = when (e) {
        is JsonArray -> e.map { it.jsonPrimitive.content }
        else -> LibraryRules.normalizeTags(e.jsonPrimitive.content)
    }

    fun library(): Report {
        val rep = Report("library")
        val o = load("library")
        for ((i, c) in o["tags"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val inp = co["in"]!!
            val got = if (inp is JsonArray) LibraryRules.normalizeTags(inp.map { it.jsonPrimitive.content }) else LibraryRules.normalizeTags(inp.jsonPrimitive.content)
            rep.check(got == co["out"]!!.jsonArray.map { it.jsonPrimitive.content }) { "tags#$i in=$inp got=$got want=${co["out"]}" }
        }
        for ((i, c) in o["sig"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val pr = Project.fromJson(co["project"]!!.jsonObject)
            val pj = LibraryRules.projectJson(pr)
            rep.check(pj == co["pj"]!!.jsonPrimitive.content) { "sig#$i project json\n got=$pj\n want=${co["pj"]}" }
            val seed = co["seed"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.long }
            rep.check(LibraryRules.sig(pj, co["prompt"]!!.jsonPrimitive.content, co["neg"]!!.jsonPrimitive.content, seed) == co["sig"]!!.jsonPrimitive.content) { "sig#$i sig" }
            rep.check(LibraryRules.rawText(pr) == co["raw"]!!.jsonPrimitive.content) { "sig#$i raw_text" }
        }
        val pool = o["pool"]!!.jsonArray.map { Project.fromJson(it.jsonObject) }
        fun sha(s: String) = sha256(s.toByteArray(Charsets.UTF_8))
        for ((si, sc) in o["scripts"]!!.jsonArray.withIndex()) {
            val so = sc.jsonObject
            var tick = 5000.0
            val store = MemoryStore()
            val lib = Library(store) { tick += 1.0; tick }
            val ids = ArrayList<Long>()
            val ops = so["ops"]!!.jsonArray
            for ((oi, opEl) in ops.withIndex()) {
                val op = opEl.jsonObject
                val kind = op["op"]!!.jsonPrimitive.content
                fun ref() = ids[op["ref"]!!.jsonPrimitive.int]
                val want = so["rets"]!!.jsonArray[oi]
                var ret: String? = null
                when (kind) {
                    "add" -> {
                        val seed = op["seed"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.long }
                        val id = lib.add(pool[op["p"]!!.jsonPrimitive.int], op["prompt"]!!.jsonPrimitive.content, op["neg"]!!.jsonPrimitive.content, seed,
                            title = op["title"]!!.jsonPrimitive.content, kind = op["kind"]!!.jsonPrimitive.content, tags = tagsArg(op["tags"]!!),
                            note = op["note"]!!.jsonPrimitive.content, now = op["now"]!!.jsonPrimitive.double)
                        ids += id; ret = id.toString()
                    }
                    "fav" -> lib.setFav(ref(), op["on"]!!.jsonPrimitive.boolean)
                    "title" -> lib.setTitle(ref(), op["v"]!!.jsonPrimitive.content)
                    "note" -> lib.setNote(ref(), op["v"]!!.jsonPrimitive.content)
                    "tags" -> lib.setTags(ref(), tagsArg(op["v"]!!))
                    "archive" -> lib.archive(ref())
                    "restore" -> lib.restore(ref())
                    "purge" -> ret = if (lib.purge(ref())) "True" else "False"
                    "purge_archived" -> ret = lib.purgeArchived().toString()
                    "prune" -> ret = lib.pruneHistory(op["keep"]!!.jsonPrimitive.int).toString()
                }
                val wantRet = if (want is JsonNull) null else want.jsonPrimitive.content.let { if (it == "true") "True" else if (it == "false") "False" else it }
                rep.check(ret == wantRet) { "library script#$si op#$oi $op ret=$ret want=$wantRet" }
                rep.check(sha(snapshot(store)) == so["shas"]!!.jsonArray[oi].jsonPrimitive.content) { "library script#$si op#$oi $op state differs" }
                if (rep.failures.size >= 5) break
            }
            for ((qi, qEl) in so["queries"]!!.jsonArray.withIndex()) {
                val q = qEl.jsonObject
                fun s(k: String) = q[k]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
                val got = lib.search(s("q")!!, q["favs"]!!.jsonPrimitive.boolean, s("kind"), s("tag"), q["archived"]!!.jsonPrimitive.boolean,
                    q["limit"]!!.jsonPrimitive.int, q["offset"]!!.jsonPrimitive.int).map { it.id }
                rep.check(got == q["ids"]!!.jsonArray.map { it.jsonPrimitive.long }) { "library script#$si query#$qi $q got=$got" }
                rep.check(lib.count(s("q")!!, q["favs"]!!.jsonPrimitive.boolean, s("kind"), s("tag"), q["archived"]!!.jsonPrimitive.boolean) == q["count"]!!.jsonPrimitive.int) { "library script#$si query#$qi count" }
            }
            rep.check(lib.tags().map { listOf(it.first, it.second.toString()) } ==
                so["tags"]!!.jsonArray.map { listOf(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }) { "library script#$si tags()" }
            val fin = so["final"]!!.jsonPrimitive.content
            if (fin.isNotEmpty()) rep.check(snapshot(store) == fin) { "library script#$si final snapshot" }
            rep.check(ids.map { lib.get(it)?.label } == so["labels"]!!.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.content }) { "library script#$si labels" }
            for ((ei, exEl) in so["exports"]!!.jsonArray.withIndex()) {
                val ex = exEl.jsonObject
                val fresh = MemoryStore()
                val fl = Library(fresh)
                val r1 = fl.importJson(ex["doc"]!!)
                rep.check(listOf(r1.first, r1.second) == ex["res"]!!.jsonArray.map { it.jsonPrimitive.int }) { "library script#$si export#$ei import res $r1 want=${ex["res"]}" }
                val r2 = fl.importJson(ex["doc"]!!)
                rep.check(listOf(r2.first, r2.second) == ex["again"]!!.jsonArray.map { it.jsonPrimitive.int }) { "library script#$si export#$ei re-import $r2" }
                rep.check(sha(snapshot(fresh)) == ex["sha"]!!.jsonPrimitive.content) { "library script#$si export#$ei imported state differs" }
                // Kotlin's own export must be importable and describe the same entries
                val mine = lib.exportJson(null, includeArchived = true)
                val f2 = Library(MemoryStore())
                val r3 = f2.importJson(mine)
                rep.check(r3.second == 0 || r3.first + r3.second == mine["entries"]!!.jsonArray.size) { "library script#$si own export import $r3" }
            }
        }
        for ((ji, jEl) in o["junk"]!!.jsonArray.withIndex()) {
            val j = jEl.jsonObject
            val fresh = MemoryStore()
            val r = Library(fresh).importJson(j["doc"]!!)
            rep.check(listOf(r.first, r.second) == j["res"]!!.jsonArray.map { it.jsonPrimitive.int }) { "junk#$ji res $r want=${j["res"]} doc=${j["doc"]}" }
            rep.check(sha(snapshot(fresh, false)) == j["sha"]!!.jsonPrimitive.content) {
                "junk#$ji state differs\n got=${snapshot(fresh, false).replace("\u001e", "\n     ").replace("\u001f", "|")}\n want=${j["snap"]!!.jsonPrimitive.content.replace("\u001e", "\n     ").replace("\u001f", "|")}" }
        }
        return rep
    }

    private fun JsonElement.strOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Message spec from the vectors: literal text, or prefix + a repeated char + suffix (for the huge ones). */
    private fun specText(o: JsonObject): String =
        if ("t" in o) o["t"]!!.jsonPrimitive.content
        else o["pre"]!!.jsonPrimitive.content + o["ch"]!!.jsonPrimitive.content.repeat(o["n"]!!.jsonPrimitive.int) + o["post"]!!.jsonPrimitive.content

    /** Compose <-> Mack bridge: what runs inside the page, and how untrusted console lines are read back. */
    fun mackbridge(): Report {
        val rep = Report("mackbridge")
        val o = load("mackbridge")
        rep.check(MackBridge.URL == o["url"]!!.jsonPrimitive.content) { "const url" }
        rep.check(MackBridge.KEYS == o["keys"]!!.jsonArray.map { it.jsonPrimitive.content }) { "const keys ${MackBridge.KEYS}" }
        rep.check(MackBridge.MAX_CONSOLE == o["max_console"]!!.jsonPrimitive.int && MackBridge.MAX_DEPTH == o["max_depth"]!!.jsonPrimitive.int) { "const limits" }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(MackBridge.FILLER_JS.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        rep.check(sha == o["filler_sha"]!!.jsonPrimitive.content && PyText.length(MackBridge.FILLER_JS) == o["filler_len"]!!.jsonPrimitive.int) { "const filler js differs from the desktop's" }

        for ((i, c) in o["fills"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val got = MackBridge.fillValues(Project.fromJson(co["project"]!!.jsonObject), co["pad"]!!.jsonPrimitive.boolean).map { it.key to it.value }
            val want = co["values"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
            rep.check(got == want) { "fill#$i got=$got want=$want" }
        }

        for ((i, c) in o["messages"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val payload = co["payload"]!!.jsonArray.map { p ->
                val key = p.jsonArray[0].jsonPrimitive.content
                val v = p.jsonArray[1].jsonArray
                key to (if (key == "values") JsonObject(LinkedHashMap<String, JsonElement>().also { m ->
                    for (kv in v) m[kv.jsonArray[0].jsonPrimitive.content] = kv.jsonArray[1]
                }) else JsonArray(v))
            }
            val js = MackBridge.messageJs(co["action"]!!.jsonPrimitive.content, co["id"]!!.jsonPrimitive.long, payload)
            rep.check(encMatches(js, co["js"]!!.jsonObject)) { "message#$i ${co["action"]} id=${co["id"]} got=${js.take(200)}" }
        }
        for (a in o["bad_actions"]!!.jsonArray) {
            val threw = try { MackBridge.messageJs(a.jsonPrimitive.content, 1); false } catch (e: IllegalArgumentException) { true }
            rep.check(threw) { "message bad action ${a.jsonPrimitive.content} accepted" }
        }

        for ((i, c) in o["parts"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val got = MackBridge.valuesToProjectParts(co["values"]!!.jsonObject)
            val wantBoxes = co["boxes"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
            rep.check(got.boxes.map { it.key to it.value } == wantBoxes) { "parts#$i boxes got=${got.boxes} want=$wantBoxes" }
            rep.check(got.negative == co["negative"]!!.strOrNull() && got.scratchpad == co["scratchpad"]!!.strOrNull()) { "parts#$i neg/pad" }
        }

        for ((i, c) in o["replies"]!!.jsonArray.withIndex()) {
            val co = c.jsonObject
            val msg = specText(co["msg"]!!.jsonObject)
            val got = MackBridge.parseReply(msg)
            val want = co["out"]!!.let { if (it is JsonNull) null else it.jsonObject }
            val shown = { if (msg.length > 120) msg.take(120) + "..." else msg }
            if (want == null) {
                rep.check(got == null) { "reply#$i accepted: ${shown()}" }
            } else if (got == null) {
                rep.check(false) { "reply#$i rejected: ${shown()}" }
            } else {
                rep.check(got.event == want["event"]!!.jsonPrimitive.content) { "reply#$i event ${shown()}" }
                rep.check(got.id?.toString() == want["id"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }) { "reply#$i id got=${got.id} want=${want["id"]} ${shown()}" }
                rep.check(got.filled == want["filled"]!!.jsonArray.map { it.jsonPrimitive.content }) { "reply#$i filled ${shown()}" }
                rep.check(got.values == want["values"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }) { "reply#$i values got=${got.values} ${shown()}" }
                rep.check(got.clicked == want["clicked"]!!.strOrNull()) { "reply#$i clicked ${shown()}" }
            }
        }
        return rep
    }

    fun all(): List<Report> = listOf(rng(), charclass(), dsl(), weights(), articles(), profiles(), compose(), lint(), export(), forge(), library(), mackbridge())
}
