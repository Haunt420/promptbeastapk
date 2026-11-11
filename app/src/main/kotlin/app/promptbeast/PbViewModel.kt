package app.promptbeast

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import promptbeast.AiProvider
import promptbeast.AiRequest
import promptbeast.AiResult
import promptbeast.AiTasks
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
import promptbeast.MackBridge
import promptbeast.MackParts
import promptbeast.MackReply
import promptbeast.MemoryStore
import promptbeast.Pack
import promptbeast.PackParser
import promptbeast.Profiles
import promptbeast.Project
import promptbeast.PyJson
import promptbeast.ProviderConfig
import promptbeast.ProviderError
import promptbeast.PyText
import promptbeast.Rejected
import promptbeast.StrictJson
import promptbeast.TagLibrary
import promptbeast.UserPacks
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
    /** One line per installed user pack (adult ones too, even while hidden from the library). */
    class UserPackInfo(val stem: String, val name: String, val slots: Int, val tags: Int, val adults: Boolean, val notes: Int)
    var userPacks by mutableStateOf<List<UserPackInfo>>(emptyList())
    private val packDir: File get() = File(app.filesDir, "packs")

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
        packs += UserPacks.load(packFiles(), adults)
        return TagLibrary(packs)
    }

    /** The installed user packs as (stem, bytes), in name order. Nothing here throws. */
    private fun packFiles(): List<Pair<String, ByteArray>> {
        val files = try {
            packDir.listFiles { f -> f.isFile && f.name.endsWith(UserPacks.EXT) }
        } catch (e: SecurityException) { null } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            try {
                f.name.removeSuffix(UserPacks.EXT) to (if (f.length() > UserPacks.MAX_BYTES) ByteArray(UserPacks.MAX_BYTES + 1) else f.readBytes())
            } catch (e: IOException) { null }
        }
    }

    private fun refreshUserPacks() {
        val files = packFiles()
        // with adults = true, load() returns exactly one Pack per file, in order: pair them with the file's stem (the pack's own id may differ)
        userPacks = files.zip(UserPacks.load(files, true)).map { (f, p) ->
            UserPackInfo(f.first, p.name, p.slots.size, p.slots.values.sumOf { it.tags.size }, p.adults, p.issues.size)
        }
    }

    /** Re-reads every pack, drops locks on slots that no longer exist, and re-rolls. */
    private fun reloadPacks() {
        forgeLib = loadForgeLib(forgeAdults)
        refreshUserPacks()
        val ids = forgeLib.slots.keys
        forgeLocks = forgeLocks.filterKeys { it in ids }
        forgeOff = forgeOff.filter { it in ids }.toSet()
        runForge()
    }

    /** A file picked in the document picker. Read off the main thread; the checks and the save happen in [packImportBytes]. */
    fun packImport(uri: Uri) {
        Thread {
            var bytes: ByteArray? = null
            var name: String? = null
            var err: String? = null
            try {
                val cr = app.contentResolver
                bytes = cr.openInputStream(uri)?.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    while (out.size() <= UserPacks.MAX_BYTES) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray()
                } ?: throw IOException("can't open it")
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            val got = bytes          // (copies: a var captured by the posted lambda can't be smart-cast)
            val label = name
            val why = err
            handler.post { if (got != null) packImportBytes(label, got) else say("Can't read that file: $why") }
        }.start()
    }

    /** Checks the file (size, UTF-8, at least one tag, age guard via the parser), saves it as packs/<stem>.pbpack and reloads. */
    fun packImportBytes(displayName: String?, bytes: ByteArray): Boolean {
        val taken = (packDir.list() ?: emptyArray()).filter { it.endsWith(UserPacks.EXT) }.map { it.removeSuffix(UserPacks.EXT) }.toSet()
        val res = UserPacks.check(displayName, bytes, taken)
        if (!res.ok) { say(res.message); return false }
        try {
            packDir.mkdirs()
            val tmp = File(packDir, res.stem + ".tmp")
            tmp.writeBytes(res.text.toByteArray(Charsets.UTF_8))
            if (!tmp.renameTo(File(packDir, res.stem + UserPacks.EXT))) { tmp.delete(); throw IOException("couldn't move it into place") }
        } catch (e: IOException) { say("Couldn't save the pack: ${e.message}"); return false }
        reloadPacks()
        say(res.message)
        return true
    }

    fun packDelete(stem: String) {
        if (!Regex("[a-z0-9_-]{1,40}").matches(stem)) return
        if (File(packDir, stem + UserPacks.EXT).delete()) { reloadPacks(); say("Deleted $stem") } else say("Couldn't delete $stem")
    }

    fun packCopyTemplate() = clip(UserPacks.TEMPLATE, "Pack template")

    fun runForge() {
        val st = ForgeState(
            seed = forgeSeed, salts = HashMap(forgeSalts), locks = HashMap(forgeLocks), disabled = HashSet(forgeOff),
            maxTier = forgeLimitTier, heat = forgeHeat.toDouble(), strict = forgeStrict,
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

    /** The highest adult tier in play: 0 unless the adult packs are on. The slot pickers only list tags up to here. */
    val forgeLimitTier: Int get() = if (forgeAdults) forgeTier else 0

    /**
     * Pin a slot to the texts picked in its drop-down (library tags or the user's own words). Anything minor-coded is
     * refused here too, so typed text can't get past the age guard that every pack goes through.
     */
    fun forgeSetLock(id: String, texts: List<String>): Boolean {
        val slot = forgeLib.slots[id] ?: return false
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(maxOf(1, slot.hi))
        if (clean.any { PackParser.ageGuardHit(it) }) { say("Minor-coded wording isn't allowed"); return false }
        forgeLocks = if (clean.isEmpty()) forgeLocks - id else forgeLocks + (id to clean)
        if (clean.isNotEmpty()) forgeOff = forgeOff - id
        runForge()
        return true
    }

    /** Locks pinned to a tag above the new tier limit would break "how far", so they are released. */
    private fun dropLocksAboveTier() {
        val limit = forgeLimitTier
        forgeLocks = forgeLocks.filter { (sid, texts) ->
            val idx = forgeLib.slots[sid]?.tags?.associateBy { it.text } ?: emptyMap()
            texts.all { (idx[it]?.tier ?: 0) <= limit }
        }
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

    fun onForgeTier(t: Int) { forgeTier = t; dropLocksAboveTier(); runForge() }
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

    // ---- mack ------------------------------------------------------------------------------------------------------
    /** idle -> loading -> live | failed. The page is only fetched the first time the Mack tab is opened. */
    var mackState by mutableStateOf("idle")
    var mackNote by mutableStateOf("")
    /** When Fill & go cannot find Generate: the buttons the page does have (shown under the header so it can be read and reported). */
    var mackDiag by mutableStateOf("")
    var mackProgress by mutableStateOf(0)
    var mackPad by mutableStateOf(true)
    var mackUrl: String = MackBridge.URL
    var goTab by mutableStateOf<Int?>(null)

    /** Kept here (application context) so the page survives tab switches and rotation; the screen only attaches it. */
    var mackView by mutableStateOf<WebView?>(null)
        private set
    private var mackCtx: MutableContextWrapper? = null
    private var mackReq = 0L
    private val mackReplies = ArrayList<MackReply>()
    private var mackAfter: ((List<MackReply>) -> Unit)? = null
    private val mackFinish = Runnable {
        val cb = mackAfter
        mackAfter = null
        cb?.invoke(ArrayList(mackReplies))
    }

    private fun mackHostOk(u: Uri): Boolean {
        val scheme = u.scheme ?: return false
        if (scheme == "about" || scheme == "data" || scheme == "blob") return true
        if (scheme != "https") return false
        val host = (u.host ?: "").lowercase()
        return host == "perchance.org" || host.endsWith(".perchance.org")
    }

    private fun openExternal(u: Uri) {
        if (u.scheme != "https" && u.scheme != "http") return
        try { app.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { say("No browser to open that link") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildMackView(): WebView {
        // An Application-context WebView can't open <select> dropdowns or JS dialogs (Perchance's "Content Safety Settings" needs
        // both), so the view gets a wrapper whose base is swapped for the Activity while the Mack tab is on screen.
        val ctx = MutableContextWrapper(app)
        mackCtx = ctx
        val v = WebView(ctx)
        v.settings.javaScriptEnabled = true
        v.settings.domStorageEnabled = true
        v.settings.allowFileAccess = false
        v.settings.allowContentAccess = false
        v.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        // Perchance generators live in nested, possibly cross-origin frames: the filler must be in every one of them.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(v, MackBridge.FILLER_JS, setOf("https://perchance.org", "https://*.perchance.org"))
        }
        v.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null || !request.isForMainFrame || mackHostOk(request.url)) return false
                openExternal(request.url)           // leaving Mack/Perchance: use the real browser
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mackState = "loading"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(MackBridge.FILLER_JS, null)      // main frame too, in case the start script isn't supported
                if (mackState != "failed") mackState = "live"
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request != null && request.isForMainFrame) {
                    mackState = "failed"
                    mackNote = "Couldn't load Mack. Check your connection and try again, or use Mack in your own browser and paste the Mack key."
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request != null && request.isForMainFrame && (errorResponse?.statusCode ?: 0) >= 400) {
                    mackState = "failed"
                    mackNote = "Mack answered with an error (${errorResponse?.statusCode}). Try again in a bit, or use it in your own browser."
                }
            }
        }
        v.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val text = consoleMessage?.message() ?: return false
                if (text.startsWith(MackBridge.PREFIX)) {
                    val r = MackBridge.parseReply(text)
                    if (r != null && r.id == mackReq) mackReplies += r
                    return true
                }
                return false
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) { mackProgress = newProgress }
        }
        return v
    }

    /** Creates the WebView and (unless [load] is false) starts loading Mack, once. Safe to call every time the tab is shown. */
    fun mackEnsure(load: Boolean = true) {
        if (mackView != null) return
        try {
            val v = buildMackView()
            mackView = v
            if (load) {
                mackState = "loading"
                v.loadUrl(mackUrl)
            }
        } catch (e: Exception) {      // no WebView provider / broken GPU stack must not take the app down
            mackState = "failed"
            mackNote = "The embedded browser could not start (${e.javaClass.simpleName}). Mack still works in your own browser: open it, then paste the Mack key."
        }
    }

    fun mackReload() {
        val v = mackView
        if (v == null) { mackEnsure(); return }
        mackState = "loading"
        v.loadUrl(mackUrl)
    }

    fun mackOpenInBrowser() = openExternal(Uri.parse(MackBridge.URL))

    /** Test hook: load a page in place of Mack, with Mack's origin so the same frame rules apply. */
    fun mackLoadHtml(html: String) {
        mackEnsure(load = false)
        mackState = "loading"
        mackView?.loadDataWithBaseURL("https://perchance.org/mack-mcgenerator", html, "text/html", "utf-8", null)
    }

    /** Test hook: evaluate a script in the page (main thread only). */
    fun mackEval(js: String, cb: (String) -> Unit) { mackView?.evaluateJavascript(js) { cb(it ?: "null") } }

    /** The Mack tab is on screen: popups and dialogs inside the page may use this Activity. */
    fun mackAttach(host: Context) { mackCtx?.baseContext = host }

    /** The tab is gone: let go of the Activity (the view outlives it across rotation). */
    fun mackDetach() { mackCtx?.baseContext = app }

    fun mackPause(paused: Boolean) {
        if (paused) {
            mackView?.onPause()
            try { CookieManager.getInstance().flush() } catch (e: Exception) { /* no WebView */ }   // keep Perchance's settings if the app is killed
        } else mackView?.onResume()
    }

    override fun onCleared() {
        handler.removeCallbacks(mackFinish)
        mackDetach()
        try { mackView?.destroy() } catch (e: Exception) { /* already gone */ }
        mackView = null
        super.onCleared()
    }

    /** Runs [js] (built from a fresh request id) in the page; frames answer asynchronously, so replies are collected for 600 ms. */
    private fun mackSend(js: (Long) -> String, after: (List<MackReply>) -> Unit): Boolean {
        val v = mackView
        if (v == null || mackState != "live") {
            say("Mack isn't loaded. Use Copy Mack key and paste it in.")
            return false
        }
        mackReq += 1
        mackReplies.clear()
        mackAfter = after
        v.evaluateJavascript(js(mackReq), null)
        handler.removeCallbacks(mackFinish)
        handler.postDelayed(mackFinish, 600)
        return true
    }

    fun mackFill(then: (() -> Unit)? = null) {
        val values = MackBridge.fillValues(snapshot(), mackPad)
        mackSend({ MackBridge.fillJs(it, values) }) { replies ->
            val filled = replies.flatMap { it.filled }.toSet()
            if (filled.isEmpty()) {
                say("Couldn't find Mack's inputs. Wait for the page to finish loading, or use Copy Mack key.")
                return@mackSend
            }
            val miss = values.keys.filter { it !in filled }
            say("Filled ${filled.size} of ${values.size} Mack inputs" +
                if (miss.isEmpty()) "" else " (not found: ${miss.take(4).joinToString(", ")}${if (miss.size > 4) "…" else ""})")
            then?.invoke()
        }
    }

    fun mackFillAndGenerate() {
        mackDiag = ""
        mackFill(then = {
            mackSend({ MackBridge.clickJs(it) }) { replies ->
                val hit = replies.firstNotNullOfOrNull { it.clicked }
                if (hit == null) {
                    val seen = MackBridge.seenReport(replies)
                    mackDiag = if (seen.isEmpty()) "No clickable buttons found in the page yet." else "Buttons seen: $seen"
                }
                say(if (hit != null) "Generating…" else "Couldn't find Mack's Generate button. Press it in the pane.")
            }
        })
    }

    fun mackPull() {
        val keys = MackBridge.KEYS.filter { it != Boxes.SCRATCHPAD_KEY || mackPad }
        mackSend({ MackBridge.readJs(it, keys) }) { replies ->
            val merged = LinkedHashMap<String, JsonElement>()
            for (r in replies) for ((k, v) in r.values) merged[k] = JsonPrimitive(v)
            if (merged.isEmpty()) {
                say("Couldn't read Mack's inputs. Wait for the page to finish loading.")
                return@mackSend
            }
            val n = applyMackParts(MackBridge.valuesToProjectParts(merged))
            goTab = 0
            say(if (n > 0) "Pulled from Mack: updated $n field${if (n != 1) "s" else ""}" else "Pulled from Mack: nothing changed")
        }
    }

    /** Set boxes to exactly what Mack holds (its text already contains any picks, so picks are dropped for those boxes). */
    private fun applyMackParts(p: MackParts): Int {
        var n = 0
        for ((id, text) in p.boxes) {
            picks.remove(id)
            if ((boxes[id] ?: "") != text) { boxes[id] = text; n++ }
        }
        p.negative?.let { if (it != negative) { negative = it; n++ } }
        p.scratchpad?.let { if (it != scratchpad) { scratchpad = it; n++ } }
        rolls = emptyList()
        rolled = null
        dirty()
        return n
    }

    // ---- ai tools ----------------------------------------------------------------------------------------------------
    private val aiFile = File(app.filesDir, "ai.json")
    private val aiKeyFile = File(app.noBackupFilesDir, "ai.key")      // not in backups: a key is not something to copy to the cloud

    var aiKind by mutableStateOf("manual")
    var aiBaseUrl by mutableStateOf(ProviderConfig().baseUrl)
    var aiModel by mutableStateOf("")
    var aiTemp by mutableStateOf(0.8)
    var aiVision by mutableStateOf(false)
    var aiKeyDraft by mutableStateOf("")
    var aiKeySaved by mutableStateOf(false)
    private var aiMaxTokens = 1200
    private var aiTimeout = 90

    var aiTask by mutableStateOf("enhance")
    var aiBox by mutableStateOf("main")
    var aiTakes by mutableStateOf(4)
    var aiExtra by mutableStateOf("")
    private var aiImage: ByteArray? = null
    private var aiImageMime = "image/png"
    var aiImageLabel by mutableStateOf("")

    var aiBusy by mutableStateOf(false)
    var aiStatus by mutableStateOf("")
    private var aiRequest: AiRequest? = null
    var aiInstruction by mutableStateOf("")
    var aiPaste by mutableStateOf("")
    var aiResult by mutableStateOf<AiResult?>(null)
    var aiVariant by mutableStateOf(0)
    var aiOut by mutableStateOf("")
    var aiNotes by mutableStateOf<List<String>>(emptyList())
    var aiProblem by mutableStateOf("")

    init { loadAiSettings(); refreshUserPacks() }

    private fun loadAiSettings() {
        try {
            val c = ProviderConfig.fromJson(if (aiFile.exists()) StrictJson.parse(aiFile.readText(), 64) else null)
            aiKind = c.kind; aiBaseUrl = c.baseUrl; aiModel = c.model; aiTemp = c.temperature; aiVision = c.vision
            aiMaxTokens = c.maxTokens; aiTimeout = c.timeout
            aiKeySaved = readAiKey().isNotEmpty()
        } catch (e: Exception) { /* unreadable settings: start from the defaults */ }
    }

    private fun readAiKey(): String = try { if (aiKeyFile.exists()) PyText.strip(aiKeyFile.readText()) else "" } catch (e: IOException) { "" }

    /** The provider as the screen shows it, clamped like a loaded one. */
    fun aiConfig(): ProviderConfig = ProviderConfig.fromJson(buildJsonObject {
        put("kind", aiKind); put("base_url", PyText.strip(aiBaseUrl)); put("model", PyText.strip(aiModel)); put("temperature", aiTemp)
        put("max_tokens", aiMaxTokens); put("timeout", aiTimeout); put("vision", aiVision)
    })

    private fun aiKey(): String = PyText.strip(aiKeyDraft).ifEmpty { readAiKey() }

    fun onAiKind(v: String) { aiKind = v }
    fun onAiBaseUrl(v: String) { aiBaseUrl = v }
    fun onAiModel(v: String) { aiModel = v }
    fun onAiTemp(v: Double) { aiTemp = Math.round(v * 10) / 10.0 }
    fun onAiVision(v: Boolean) { aiVision = v }
    fun onAiKeyDraft(v: String) { aiKeyDraft = v }
    fun onAiTask(v: String) { aiTask = v }
    fun onAiBox(v: String) { aiBox = v }
    fun onAiTakes(v: Int) { aiTakes = v.coerceIn(2, 8) }
    fun onAiExtra(v: String) { aiExtra = v }
    fun onAiPaste(v: String) { aiPaste = v }
    fun onAiOut(v: String) { aiOut = v }

    private fun writePrivate(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun aiSave() {
        try {
            val c = aiConfig()
            writePrivate(aiFile, c.toJson().toString())
            if (PyText.strip(aiKeyDraft).isNotEmpty()) {
                writePrivate(aiKeyFile, PyText.strip(aiKeyDraft))
                aiKeyDraft = ""
                aiKeySaved = true
            }
            aiStatus = "Saved."
            say("AI provider settings saved")
        } catch (e: IOException) {
            aiStatus = "Couldn't save: ${e.message}"
        }
    }

    fun aiForgetKey() {
        aiKeyFile.delete()
        aiKeyDraft = ""
        aiKeySaved = false
        say("API key removed")
    }

    fun aiPickImage(uri: Uri) {
        try {
            val cr = app.contentResolver
            val bytes = cr.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (out.size() <= 8_000_000) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            } ?: throw IOException("can't open it")
            if (bytes.size > 8_000_000) { say("That image is over 8 MB; shrink it first"); return }
            val type = cr.getType(uri)
            aiImage = bytes
            aiImageMime = if (type in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) type!! else "image/png"
            var name = "image"
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
            aiImageLabel = "$name (${bytes.size / 1024} KB)"
        } catch (e: Exception) {
            say("Can't read that image: ${e.message}")
        }
    }

    private fun aiFail(msg: String) {
        aiProblem = msg
        aiStatus = msg
        aiNotes = listOf(msg)
        say(msg)
    }

    /** Builds the request; with the manual provider that is the pasteable instruction, otherwise it goes to the endpoint. */
    fun aiRun() {
        if (aiBusy) return
        val req = try {
            AiTasks.build(aiTask, snapshot(), box = aiBox, extra = aiExtra, n = aiTakes, image = aiImage, imageMime = aiImageMime)
        } catch (e: IllegalArgumentException) { aiFail(e.message ?: "Can't build that request"); return }
        aiRequest = req
        val cfg = aiConfig()
        if (cfg.kind == "manual") {
            aiInstruction = AiTasks.flatten(req)
            aiPaste = ""
            aiProblem = ""
            say("Instruction built. Copy it, then paste the reply below.")
            return
        }
        if (req.needsVision && !cfg.vision) { aiFail("This provider is not marked as vision-capable (tick 'Model can see images')."); return }
        val key = aiKey()
        aiBusy = true
        aiStatus = "Waiting for the model..."
        Thread {
            var res: AiResult? = null
            var err: String? = null
            try { res = AiTasks.parse(req, AiProvider.complete(cfg, key, req.messages)) }
            catch (e: ProviderError) { err = e.message }
            catch (e: Rejected) { err = e.message }
            catch (e: Exception) { err = "Unexpected error: ${e.javaClass.simpleName}: ${e.message}" }
            handler.post {
                aiBusy = false
                val r = res                      // (a var captured by the worker lambda can't be smart-cast)
                if (r != null) aiShow(r) else aiFail(err ?: "Failed")
            }
        }.start()
    }

    fun aiReadReply() {
        val req = aiRequest
        if (req == null) { aiFail("Press Build instruction first"); return }
        try { aiShow(AiTasks.parse(req, aiPaste)) } catch (e: Rejected) { aiFail(e.message ?: "Rejected") }
    }

    fun aiTest() {
        if (aiBusy) return
        val cfg = aiConfig()
        if (cfg.kind == "manual") return
        val key = aiKey()
        aiBusy = true
        aiStatus = "Testing..."
        Thread {
            var out: String? = null
            var err: String? = null
            try { out = AiProvider.test(cfg, key) } catch (e: ProviderError) { err = e.message } catch (e: Exception) { err = "Unexpected error: ${e.javaClass.simpleName}: ${e.message}" }
            handler.post {
                aiBusy = false
                val o = out
                if (o != null) { aiStatus = "Connected. The model replied: $o"; say(aiStatus) } else aiFail(err ?: "Failed")
            }
        }.start()
    }

    private fun aiShow(res: AiResult) {
        aiResult = res
        aiVariant = 0
        aiOut = if (res.sections.isNotEmpty()) res.sections.entries.joinToString("\n\n") { "### ${it.key}\n${it.value}" } else res.texts.firstOrNull() ?: ""
        aiNotes = if (res.notes.isEmpty()) listOf("No notes.") else res.notes.toList()
        aiProblem = ""      // no toast here: a snackbar would sit right over the Apply button
    }

    fun aiPickVariant(i: Int) {
        val res = aiResult ?: return
        if (i in res.texts.indices) { aiVariant = i; aiOut = res.texts[i] }
    }

    fun aiApply() {
        val res = aiResult
        if (res == null) { say("Nothing to apply yet"); return }
        if (res.sections.isNotEmpty()) { applyAi(res.sections); return }
        val text = PyText.strip(aiOut)
        if (text.isEmpty()) { say("The result is empty"); return }
        applyAi(mapOf(aiBox to text))
    }

    /** Replace the named boxes (a "negative" key sets the negative prompt), then show Compose. */
    private fun applyAi(sections: Map<String, String>) {
        var n = 0
        for (spec in Boxes.ALL) {
            val t = sections[spec.id] ?: continue
            if ((boxes[spec.id] ?: "") != t) { boxes[spec.id] = t; n++ }
        }
        sections[Boxes.NEGATIVE_KEY]?.let { negative = it; n++ }
        rolls = emptyList()
        rolled = null
        dirty()
        goTab = 0
        say("AI: updated $n box${if (n != 1) "es" else ""}")
    }

    // ---- parity self-test ------------------------------------------------------------------------------------------
    var selfTest by mutableStateOf<List<String>>(emptyList())
    var selfTestRunning by mutableStateOf(false)

    /** Re-runs the golden-vector suites (shipped as assets) against this device's runtime. */
    fun runSelfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        selfTest = emptyList()
        GoldenSuite.source = { n ->
            val gz = try { app.assets.open("golden/$n.json.gz") } catch (e: IOException) { app.assets.open("golden/$n.json") }
            gz.use { it.readBytes() }
        }
        Thread {
            val suites: List<Pair<String, () -> GoldenSuite.Report>> = listOf(
                "rng" to { GoldenSuite.rng() }, "charclass" to { GoldenSuite.charclass() }, "dsl" to { GoldenSuite.dsl() },
                "weights" to { GoldenSuite.weights() }, "articles" to { GoldenSuite.articles() },
                "profiles" to { GoldenSuite.profiles() }, "compose" to { GoldenSuite.compose() }, "lint" to { GoldenSuite.lint() },
                "export" to { GoldenSuite.export() }, "forge" to { GoldenSuite.forge() }, "library" to { GoldenSuite.library() },
                "mackbridge" to { GoldenSuite.mackbridge() }, "ai" to { GoldenSuite.ai() },
            )
            for ((n, run) in suites) {
                val line = try {
                    val r = run()
                    (if (r.failures.isEmpty()) "OK   " else "FAIL ") + r.toString() + r.failures.take(8).joinToString("") { "\n       $it" }
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
