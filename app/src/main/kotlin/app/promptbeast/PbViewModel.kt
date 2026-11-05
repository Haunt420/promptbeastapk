package app.promptbeast

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import promptbeast.Assembled
import promptbeast.Boxes
import promptbeast.Composer
import promptbeast.Entry
import promptbeast.EntryStore
import promptbeast.Export
import promptbeast.Forge
import promptbeast.ForgeResult
import promptbeast.ForgeState
import promptbeast.GoldenSuite
import promptbeast.Issue
import promptbeast.Library
import promptbeast.LibraryRules
import promptbeast.Lint
import promptbeast.MemoryStore
import promptbeast.Pack
import promptbeast.Profiles
import promptbeast.Project
import promptbeast.PyJson
import promptbeast.PyText
import promptbeast.TagLibrary
import promptbeast.parsePack
import java.io.File
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

/** Everything the screens read and the only place that mutates it. State is Compose snapshot state, so screens just observe. */
class PbViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("pb", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val projectFile = File(app.filesDir, "project.json")

    /** One-shot message for the snackbar. */
    var toast by mutableStateOf<String?>(null)

    private fun say(msg: String) { toast = msg }

    // ---- settings -----------------------------------------------------------------------------------------------
    /** 0 = follow the system, 1 = dark, 2 = light. */
    var themeMode by mutableStateOf(prefs.getInt("theme", 0))
    var advancedBoxes by mutableStateOf(prefs.getBoolean("advanced", false))

    fun onThemeMode(m: Int) { themeMode = m; prefs.edit().putInt("theme", m).apply() }
    fun onAdvanced(on: Boolean) { advancedBoxes = on; prefs.edit().putBoolean("advanced", on).apply() }

    // ---- compose: the project being edited ------------------------------------------------------------------------
    var name by mutableStateOf("Untitled")
    val boxes = mutableStateMapOf<String, String>()
    var negative by mutableStateOf("")
    var scratchpad by mutableStateOf("")
    var profile by mutableStateOf(Profiles.DEFAULT)
    var sticky by mutableStateOf(false)
    var fixArticles by mutableStateOf(true)
    private var picks: MutableMap<String, MutableList<String>> = LinkedHashMap()

    var seedText by mutableStateOf("")
    var rollCount by mutableStateOf(4)
    var rolls by mutableStateOf<List<Assembled>>(emptyList())
    private var rolled: Project? = null
    var lintItems by mutableStateOf<List<Pair<String, Issue>>>(emptyList())

    private val saver = Runnable { writeProject(); runLint() }

    private fun dirty() { handler.removeCallbacks(saver); handler.postDelayed(saver, 700) }

    fun onName(v: String) { name = v; dirty() }
    fun onBox(id: String, v: String) { boxes[id] = v; dirty() }
    fun onNegative(v: String) { negative = v; dirty() }
    fun onScratchpad(v: String) { scratchpad = v; dirty() }
    fun onProfile(v: String) { profile = v; dirty() }
    fun onSticky(v: Boolean) { sticky = v; dirty() }
    fun onFixArticles(v: Boolean) { fixArticles = v; dirty() }

    fun snapshot(): Project {
        val b = LinkedHashMap<String, String>()
        for (spec in Boxes.ALL) b[spec.id] = boxes[spec.id] ?: ""
        val p = LinkedHashMap<String, MutableList<String>>()
        for ((k, v) in picks) p[k] = v.toMutableList()
        return Project(name = name, boxes = b, picks = p, negative = negative, scratchpad = scratchpad,
            profile = profile, stickyVars = sticky, fixArticles = fixArticles)
    }

    private fun isEmpty(p: Project): Boolean =
        p.boxes.values.all { PyText.strip(it).isEmpty() } && PyText.strip(p.negative).isEmpty() &&
            PyText.strip(p.scratchpad).isEmpty() && p.picks.values.all { l -> l.all { PyText.strip(it).isEmpty() } }

    fun loadProject(p: Project) {
        name = p.name
        boxes.clear()
        for (spec in Boxes.ALL) boxes[spec.id] = p.boxes[spec.id] ?: ""
        negative = p.negative
        scratchpad = p.scratchpad
        profile = if (p.profile in Profiles.ALL) p.profile else Profiles.DEFAULT
        sticky = p.stickyVars
        fixArticles = p.fixArticles
        picks = LinkedHashMap()
        for ((k, v) in p.picks) picks[k] = v.toMutableList()
        rolls = emptyList()
        rolled = null
        dirty()
    }

    fun newProject() = loadProject(Project())

    private fun writeProject() {
        try { projectFile.writeText(snapshot().toJson().toString()) } catch (e: IOException) { /* best effort */ }
    }

    fun persistNow() { handler.removeCallbacks(saver); writeProject() }

    fun runLint() {
        val out = ArrayList<Pair<String, Issue>>()
        for (spec in Boxes.ALL) {
            val t = boxes[spec.id] ?: ""
            if (PyText.strip(t).isEmpty()) continue
            for (i in Lint.lint(t, profile, scratchpad = scratchpad)) if (i.severity != "info") out += spec.label to i
        }
        if (PyText.strip(negative).isNotEmpty()) {
            for (i in Lint.lint(negative, profile, scratchpad = scratchpad, isNegative = true)) if (i.severity != "info") out += "Negative" to i
        }
        lintItems = out
    }

    fun cycleRollCount() { rollCount = when (rollCount) { 1 -> 2; 2 -> 4; 4 -> 6; 6 -> 8; else -> 1 } }

    fun roll(): Boolean {
        val txt = seedText.trim()
        val seed = if (txt.isEmpty()) null else txt.toLongOrNull()
        if (txt.isNotEmpty() && seed == null) { say("Seed must be a whole number"); return false }
        val p = snapshot()
        if (isEmpty(p)) { say("Nothing to roll yet. Write something in a box."); return false }
        rolled = p
        rolls = Composer.roll(p, rollCount, seed)
        runLint()
        return true
    }

    // ---- clipboard / sharing / history -----------------------------------------------------------------------------
    fun clip(text: String, what: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PromptBeast", text))
        say("$what copied")
    }

    private fun history(a: Assembled) {
        val p = rolled ?: snapshot()
        libCall { lib.add(p, a.prompt, a.negative, a.seed, kind = "history") }
    }

    fun copyPrompt(a: Assembled) { clip(a.prompt, "Prompt"); history(a) }
    fun copyNegative(a: Assembled) { clip(a.negative, "Negative"); history(a) }

    fun mackKeyText(p: Project): String = Export.toJson(Export.mackKey(p))
    fun copyMack(a: Assembled) { clip(mackKeyText(rolled ?: snapshot()), "Mack key"); history(a) }
    fun copyMackNow() { clip(mackKeyText(snapshot()), "Mack key") }

    fun keep(a: Assembled) {
        val p = rolled ?: snapshot()
        libCall { lib.add(p, a.prompt, a.negative, a.seed, kind = "saved", title = if (p.name == "Untitled") "" else p.name) }
        say("Kept in the Library")
    }

    // ---- library ---------------------------------------------------------------------------------------------------
    private val store: EntryStore = try {
        SqliteEntryStore(app).also { it.open() }
    } catch (e: Exception) {
        MemoryStore()
    }
    val libraryInMemory: Boolean = store is MemoryStore
    val lib = Library(store)

    /** Bumped after every library write; screens key their queries on it. */
    var libVersion by mutableStateOf(0)

    fun libCall(block: () -> Unit) {
        try { block() } catch (e: Exception) { say("Library error: ${e.message}") }
        libVersion++
    }

    fun libSearch(q: String, view: String, tag: String): List<Entry> = try {
        lib.search(q, favs = view == "fav", kind = if (view == "saved" || view == "history") view else null,
            tag = tag.ifEmpty { null }, archived = view == "archive", limit = 500)
    } catch (e: Exception) { emptyList() }

    fun libCount(view: String): Int = try { lib.count(archived = view == "archive") } catch (e: Exception) { 0 }
    fun libTags(): List<Pair<String, Int>> = try { lib.tags() } catch (e: Exception) { emptyList() }
    fun libGet(id: Long): Entry? = try { lib.get(id) } catch (e: Exception) { null }

    fun libEdit(id: Long, title: String, tags: String, note: String) = libCall {
        lib.setTitle(id, title)
        lib.setTags(id, LibraryRules.normalizeTags(tags))
        lib.setNote(id, note)
    }

    fun libFav(id: Long, on: Boolean) = libCall { lib.setFav(id, on) }
    fun libArchive(id: Long) = libCall { lib.archive(id) }
    fun libRestore(id: Long) = libCall { lib.restore(id) }
    fun libPurge(id: Long) = libCall { lib.purge(id) }
    fun libPurgeArchived() = libCall { say("Deleted ${lib.purgeArchived()} archived entries") }

    fun loadEntry(e: Entry) {
        loadProject(e.projectObj())
        say("Loaded into Compose")
    }

    fun exportTo(uri: Uri) {
        try {
            val json = PyJson.dumps(lib.exportJson(null, false))
            val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open file")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            say("Library exported")
        } catch (e: Exception) {
            say("Export failed: ${e.message}")
        }
    }

    fun importFrom(uri: Uri) {
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("cannot open file")
            val text = input.use { String(it.readBytes(), Charsets.UTF_8) }
            val (added, skipped) = lib.importJson(Json.parseToJsonElement(text))
            say("Imported $added, skipped $skipped")
        } catch (e: Exception) {
            say("Import failed: ${e.message}")
        }
        libVersion++
    }

    // ---- forge -----------------------------------------------------------------------------------------------------
    var forgeAdults by mutableStateOf(prefs.getBoolean("adults", false))
    var adultsAcked by mutableStateOf(prefs.getBoolean("adults_ack", false))
    var forgeLib by mutableStateOf(loadForgeLib(prefs.getBoolean("adults", false)))
    var forgeSeed by mutableStateOf(ThreadLocalRandom.current().nextLong(0, 1L shl 31))
    var forgeSeedText by mutableStateOf("")
    var forgeTier by mutableStateOf(0)
    var forgeHeat by mutableStateOf(0.5f)
    var forgeStrict by mutableStateOf(true)
    var forgeSalts by mutableStateOf<Map<String, Long>>(emptyMap())
    var forgeLocks by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var forgeOff by mutableStateOf<Set<String>>(emptySet())
    var forgeMode by mutableStateOf("replace")
    var forgeResult by mutableStateOf<ForgeResult?>(null)

    private fun loadForgeLib(adults: Boolean): TagLibrary {
        val packs = ArrayList<Pack>()
        for (n in TagLibrary.BUILTIN) {
            val text: String? = try {
                app.assets.open("packs/$n.pbpack").use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: IOException) {
                TagLibrary.builtinText(n)
            }
            if (text == null) continue
            val p = parsePack(text, n)
            if (p.adults && !adults) continue
            packs += p
        }
        return TagLibrary(packs)
    }

    fun runForge() {
        val st = ForgeState(
            seed = forgeSeed, salts = HashMap(forgeSalts), locks = HashMap(forgeLocks), disabled = HashSet(forgeOff),
            maxTier = if (forgeAdults) forgeTier else 0, heat = forgeHeat.toDouble(), strict = forgeStrict,
        )
        forgeResult = Forge.forge(forgeLib, st)
        forgeSeedText = forgeSeed.toString()
    }

    fun forgeRerollAll() {
        forgeSeed = ThreadLocalRandom.current().nextLong(0, 1L shl 31)
        forgeSalts = emptyMap()
        runForge()
    }

    fun forgeRerollSlot(id: String) {
        forgeLocks = forgeLocks - id
        forgeSalts = forgeSalts + (id to ((forgeSalts[id] ?: 0L) + 1L))
        runForge()
    }

    fun forgeToggleLock(id: String) {
        if (id in forgeLocks) {
            forgeLocks = forgeLocks - id
        } else {
            val texts = forgeResult?.picks?.get(id)?.texts
            if (texts != null) forgeLocks = forgeLocks + (id to texts)
        }
        runForge()
    }

    fun forgeToggleOff(id: String) {
        forgeOff = if (id in forgeOff) forgeOff - id else forgeOff + id
        runForge()
    }

    fun onForgeSeedText(v: String) {
        val clean = v.filter { it.isDigit() }.take(18)
        forgeSeedText = clean
        val n = clean.toLongOrNull()
        if (n != null) { forgeSeed = n; runForge(); forgeSeedText = clean }
    }

    fun onForgeTier(t: Int) { forgeTier = t; runForge() }
    fun onForgeHeat(h: Float) { forgeHeat = h; runForge() }
    fun onForgeStrict(on: Boolean) { forgeStrict = on; runForge() }
    fun onForgeMode(m: String) { forgeMode = m }

    fun onForgeAdults(on: Boolean) {
        forgeAdults = on
        prefs.edit().putBoolean("adults", on).apply()
        forgeLib = loadForgeLib(on)
        if (!on) forgeTier = 0
        runForge()
    }

    fun ackAdults() {
        adultsAcked = true
        prefs.edit().putBoolean("adults_ack", true).apply()
        onForgeAdults(true)
    }

    /** Forge picks -> Compose boxes (replace / append / fill_empty), plus the adult negative when an adult tier was used. */
    fun forgeSend(): Boolean {
        val res = forgeResult ?: return false
        val bx = Forge.toBoxes(forgeLib, res)
        if (bx.isEmpty()) { say("Nothing to send: every slot is empty or switched off"); return false }
        for (spec in Boxes.ALL) {
            val t = bx[spec.id] ?: continue
            boxes[spec.id] = Forge.mergeText(boxes[spec.id] ?: "", t, forgeMode)
        }
        val add = Forge.negativeAdd(res)
        if (add.isNotEmpty()) {
            val have = negative.split(",").map { PyText.strip(it).lowercase() }.toSet()
            val extra = add.split(",").map { PyText.strip(it) }.filter { it.isNotEmpty() && it.lowercase() !in have }
            if (extra.isNotEmpty()) negative = Forge.mergeText(negative, extra.joinToString(", "), "append")
        }
        rolls = emptyList()
        dirty()
        say("Sent to Compose")
        return true
    }

    // ---- parity self-test ------------------------------------------------------------------------------------------
    var selfTest by mutableStateOf<List<String>>(emptyList())
    var selfTestRunning by mutableStateOf(false)

    /** Re-runs the golden-vector suites (shipped as assets) against this device's runtime. */
    fun runSelfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        selfTest = emptyList()
        GoldenSuite.source = { n -> app.assets.open("golden/$n.json.gz").use { it.readBytes() } }
        Thread {
            val suites: List<Pair<String, () -> GoldenSuite.Report>> = listOf(
                "rng" to { GoldenSuite.rng() }, "charclass" to { GoldenSuite.charclass() }, "dsl" to { GoldenSuite.dsl() },
                "weights" to { GoldenSuite.weights() }, "articles" to { GoldenSuite.articles() },
                "profiles" to { GoldenSuite.profiles() }, "compose" to { GoldenSuite.compose() }, "lint" to { GoldenSuite.lint() },
                "export" to { GoldenSuite.export() }, "forge" to { GoldenSuite.forge() }, "library" to { GoldenSuite.library() },
            )
            for ((n, run) in suites) {
                val line = try {
                    val r = run()
                    (if (r.failures.isEmpty()) "OK   " else "FAIL ") + r.toString() + r.failures.take(3).joinToString("") { "\n       $it" }
                } catch (t: Throwable) {
                    "FAIL $n crashed: ${t.javaClass.simpleName} ${t.message}"
                }
                handler.post { selfTest = selfTest + line }
            }
            handler.post { selfTestRunning = false }
        }.start()
    }

    init {
        try {
            if (projectFile.exists()) {
                val p = Project.fromJson(Json.parseToJsonElement(projectFile.readText()).jsonObject)
                loadProject(p)
                handler.removeCallbacks(saver)
            }
        } catch (e: Exception) { /* unreadable draft: start fresh */ }
        if (boxes.isEmpty()) for (spec in Boxes.ALL) boxes[spec.id] = ""
        runForge()
    }
}
