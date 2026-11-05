package promptbeast

import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * Library logic (saved prompts + usage history), storage-agnostic: port of core/library.py. The SQLite part lives behind
 * [EntryStore] (Android implements it with android.database.sqlite, tests with [MemoryStore]); everything observable -
 * signatures, tag rules, promotion/prune rules, backup JSON - is identical to the desktop so libraries move between them.
 */
const val LIBRARY_SCHEMA_VERSION = 1
val ENTRY_KINDS = listOf("saved", "history")
const val HISTORY_CAP = 500

data class Entry(
    val id: Long,
    val created: Double,
    val kind: String,
    val title: String,
    val prompt: String,
    val negative: String,
    val seed: Long?,
    val profile: String,
    val project: JsonObject,
    val fav: Boolean,
    val tags: List<String>,
    val note: String,
    val archived: Double?,
) {
    fun projectObj(): Project = Project.fromJson(project)

    val label: String
        get() {
            val t = PyText.strip(title).ifEmpty { PyText.prefix(PyText.splitWhitespace(prompt).joinToString(" "), 70) }
                .ifEmpty { "(empty prompt)" }
            return if (PyText.length(t) <= 70) t else PyText.prefix(t, 69) + "…"
        }
}

/** The row written on insert (id is assigned by the store). */
class NewEntry(
    val created: Double, val kind: String, val title: String, val prompt: String, val negative: String, val seed: Long?,
    val profile: String, val projectJson: String, val raw: String, val tags: List<String>, val note: String, val sig: String,
)

class EntryFilter(
    val q: String = "", val favs: Boolean = false, val kind: String? = null, val tag: String? = null, val archived: Boolean = false,
)

/** Primitive operations the Library needs; the SQL twin of each is in the desktop's library.py. */
interface EntryStore {
    fun findBySig(sig: String): Pair<Long, String>?                       // (id, kind)
    fun insert(e: NewEntry): Long
    fun get(id: Long): Entry?
    fun setCreated(id: Long, created: Double)
    fun setKind(id: Long, kind: String)
    fun setArchived(id: Long, archived: Double?)
    fun setTitle(id: Long, title: String)
    fun setNote(id: Long, note: String)
    fun setTags(id: Long, tags: List<String>)
    fun setFav(id: Long, fav: Boolean)
    fun deleteIfArchived(id: Long): Boolean
    fun deleteAllArchived(): Int
    fun pruneHistory(keep: Int): Int
    fun search(f: EntryFilter, limit: Int, offset: Int): List<Entry>    // newest first (created DESC, id DESC)
    fun count(f: EntryFilter): Int
    fun liveTags(): List<List<String>>                                   // tag lists of non-archived entries
    fun allByCreated(): List<Entry>                                      // created ASC, id ASC (backups)
}

object LibraryRules {
    private val TAG_NOT_WORD = Regex("[^\\p{L}\\p{N}_\\-]+")
    private val SPLIT = Regex("[,\n]+")

    /** Lower-case, de-duplicated, order-kept tags (max 20, each max 32 code points) from a list or a 'a, b c' string. */
    fun normalizeTags(tags: Iterable<String>): List<String> {
        val out = ArrayList<String>()
        for (raw in tags) {
            val t = TAG_NOT_WORD.replace(PyText.lower(PyText.strip(raw)), "-").trim('-')
            if (t.isNotEmpty() && t !in out) out += PyText.prefix(t, 32)     // dedupe on the full text, store truncated (as Python does)
        }
        return out.take(20)
    }

    fun normalizeTags(text: String): List<String> = normalizeTags(SPLIT.split(text))

    fun sig(projectJson: String, prompt: String, negative: String, seed: Long?): String {
        val s = listOf(projectJson, prompt, negative, seed?.toString() ?: "None").joinToString("\u001f")
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun rawText(p: Project): String {
        val parts = ArrayList<String>()
        for (v in p.boxes.values) if (PyText.strip(v).isNotEmpty()) parts += v
        parts += p.negative
        parts += p.scratchpad
        for (picks in p.picks.values) parts += picks
        return parts.filter { PyText.strip(it).isNotEmpty() }.joinToString("\n")
    }

    /** `Project.to_dict()` as the desktop stores it: json.dumps(ensure_ascii=False, sort_keys=True). */
    fun projectJson(p: Project): String = PyJson.dumps(p.toJson(), sortKeys = true)

    fun likePattern(term: String): String = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}

/** `json.dumps` with Python's default separators (", " and ": "), optional sort_keys, ensure_ascii=False. */
object PyJson {
    fun dumps(e: JsonElement, sortKeys: Boolean = false, ensureAscii: Boolean = false): String =
        StringBuilder().also { write(it, e, sortKeys, ensureAscii) }.toString()

    /** `json.dumps(ensure_ascii=True)` string: everything outside space..~ becomes \\uXXXX (UTF-16 units, so astral = surrogate pair). */
    internal fun quoteAscii(sb: StringBuilder, s: String): StringBuilder {
        sb.append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000c' -> sb.append("\\f")
            ch in ' '..'~' -> sb.append(ch)
            else -> sb.append("\\u%04x".format(ch.code))
        }
        return sb.append('"')
    }

    private fun quote(sb: StringBuilder, s: String, ascii: Boolean) = if (ascii) quoteAscii(sb, s) else Export.quote(sb, s)

    private fun write(sb: StringBuilder, e: JsonElement, sortKeys: Boolean, ascii: Boolean) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content, ascii) else sb.append(e.content)
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, x -> if (i > 0) sb.append(", "); write(sb, x, sortKeys, ascii) }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                val keys = if (sortKeys) e.keys.sortedWith(PyText.CODEPOINT_ORDER) else e.keys.toList()
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(", ")
                    quote(sb, k, ascii).append(": ")
                    write(sb, e.getValue(k), sortKeys, ascii)
                }
                sb.append('}')
            }
        }
    }
}

class Library(private val store: EntryStore, private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    /** Store a snapshot. Returns the entry id (the existing one when the snapshot is already stored). */
    fun add(
        project: Project, prompt: String = "", negative: String = "", seed: Long? = null,
        title: String = "", kind: String = "saved", tags: List<String> = emptyList(), note: String = "", now: Double? = null,
    ): Long {
        val k = if (kind in ENTRY_KINDS) kind else "saved"
        val pj = LibraryRules.projectJson(project)
        val sig = LibraryRules.sig(pj, prompt, negative, seed)
        val ts = now ?: clock()
        val hit = store.findBySig(sig)
        if (hit != null) {
            val id = hit.first
            if (k == "saved") {          // keeping something that was only in history promotes it and un-archives it
                store.setKind(id, "saved"); store.setArchived(id, null); store.setCreated(id, ts)
                if (PyText.strip(title).isNotEmpty()) store.setTitle(id, PyText.prefix(PyText.strip(title), 200))
                val add = LibraryRules.normalizeTags(tags)
                if (add.isNotEmpty()) {
                    val merged = (store.get(id)!!.tags.toSet() + add.toSet()).sortedWith(PyText.CODEPOINT_ORDER)
                    store.setTags(id, LibraryRules.normalizeTags(merged))
                }
            } else store.setCreated(id, ts)
            return id
        }
        val id = store.insert(NewEntry(ts, k, PyText.prefix(PyText.strip(title), 200), prompt, negative, seed, project.profile, pj,
            LibraryRules.rawText(project), LibraryRules.normalizeTags(tags), PyText.prefix(note, 4000), sig))
        if (k == "history") pruneHistory()
        return id
    }

    fun setFav(id: Long, on: Boolean) { store.setFav(id, on); if (on) store.setKind(id, "saved") }
    fun setTitle(id: Long, title: String) = store.setTitle(id, PyText.prefix(PyText.strip(title), 200))
    fun setNote(id: Long, note: String) = store.setNote(id, PyText.prefix(note, 4000))
    fun setTags(id: Long, tags: Iterable<String>) = store.setTags(id, LibraryRules.normalizeTags(tags))
    fun archive(id: Long) = store.setArchived(id, clock())
    fun restore(id: Long) = store.setArchived(id, null)

    /** Delete for good. Only archived entries can be purged, so a stray tap can't lose a prompt. */
    fun purge(id: Long): Boolean = store.deleteIfArchived(id)
    fun purgeArchived(): Int = store.deleteAllArchived()

    /** Drop the oldest un-favourited history entries beyond [keep]. Saved entries are never touched. */
    fun pruneHistory(keep: Int = HISTORY_CAP): Int = store.pruneHistory(keep)

    fun get(id: Long): Entry? = store.get(id)

    fun search(q: String = "", favs: Boolean = false, kind: String? = null, tag: String? = null, archived: Boolean = false,
               limit: Int = 300, offset: Int = 0): List<Entry> = store.search(EntryFilter(q, favs, kind, tag, archived), limit, offset)

    fun count(q: String = "", favs: Boolean = false, kind: String? = null, tag: String? = null, archived: Boolean = false): Int =
        store.count(EntryFilter(q, favs, kind, tag, archived))

    /** (tag, count) over live entries, most used first. */
    fun tags(): List<Pair<String, Int>> {
        val n = LinkedHashMap<String, Int>()
        for (list in store.liveTags()) for (t in list) n[t] = (n[t] ?: 0) + 1
        return n.entries.map { it.key to it.value }.sortedWith(compareBy<Pair<String, Int>> { -it.second }.thenBy(PyText.CODEPOINT_ORDER) { it.first })
    }

    // ---- backup / share ------------------------------------------------------------------------------------------
    fun exportJson(ids: Set<Long>? = null, includeArchived: Boolean = false): JsonObject = buildJsonObject {
        put("promptbeast_library", LIBRARY_SCHEMA_VERSION)
        putJsonArray("entries") {
            for (e in store.allByCreated()) {
                if ((ids != null && e.id !in ids) || (e.archived != null && !includeArchived)) continue
                add(buildJsonObject {
                    put("created", e.created); put("kind", e.kind); put("title", e.title); put("prompt", e.prompt)
                    put("negative", e.negative); put("seed", e.seed?.let { JsonPrimitive(it) } ?: JsonNull); put("fav", e.fav)
                    putJsonArray("tags") { e.tags.forEach { add(it) } }
                    put("note", e.note); put("project", e.project)
                })
            }
        }
    }

    private fun tagsFrom(e: JsonElement?): List<String> = when {
        e == null || e is JsonNull -> emptyList()
        e is JsonArray -> LibraryRules.normalizeTags(e.map { pyStr(it) })
        e is JsonObject -> LibraryRules.normalizeTags(e.keys)
        e is JsonPrimitive && e.isString -> LibraryRules.normalizeTags(e.content)
        else -> throw IllegalArgumentException("tags")
    }

    /** Merge an export. Returns (added, skipped). Junk rows are skipped, never thrown. */
    fun importJson(data: JsonElement): Pair<Int, Int> {
        val entries = ((data as? JsonObject)?.get("entries") as? JsonArray) ?: return 0 to 0
        var added = 0
        var skipped = 0
        for (d in entries) {
            try {
                val o = d as JsonObject
                val proj = Project.fromJson(o["project"] as? JsonObject ?: throw IllegalArgumentException("project"))
                val before = count(archived = false) + count(archived = true)
                val seedEl = o["seed"] as? JsonPrimitive
                val seed = if (seedEl != null && !seedEl.isString && seedEl.content != "true" && seedEl.content != "false") seedEl.longOrNull else null
                fun str(k: String) = o[k]?.let { pyStr(it) } ?: ""
                val created = (o["created"] as? JsonPrimitive)?.takeIf { !it.isString && it.content != "true" && it.content != "false" }?.doubleOrNull
                val kindEl = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "saved"
                val id = add(proj, str("prompt"), str("negative"), seed, title = str("title"), kind = kindEl, tags = tagsFrom(o["tags"]),
                    note = str("note"), now = created)
                val fav = o["fav"]
                if (fav != null && fav !is JsonNull && !(fav is JsonPrimitive && !fav.isString && (fav.content == "false" || fav.doubleOrNull == 0.0)) &&
                    !(fav is JsonPrimitive && fav.isString && fav.content.isEmpty()) && !(fav is JsonArray && fav.isEmpty()) && !(fav is JsonObject && fav.isEmpty())) setFav(id, true)
                val after = count(archived = false) + count(archived = true)
                if (after > before) added++ else skipped++
            } catch (e: RuntimeException) {
                skipped++
            }
        }
        return added to skipped
    }
}

/** In-memory [EntryStore]: used by the tests and as a no-persistence fallback. Mirrors the SQL semantics of the desktop. */
class MemoryStore : EntryStore {
    private val rows = ArrayList<Row>()
    private var nextId = 1L

    private class Row(var e: Entry, var raw: String, val sig: String)

    private fun asciiLower(s: String): String = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') (c + 32) else c) }
    private fun likeContains(hay: String, term: String) = asciiLower(hay).contains(asciiLower(term))

    private fun matches(r: Row, f: EntryFilter): Boolean {
        val e = r.e
        if ((e.archived != null) != f.archived) return false
        if (f.favs && !e.fav) return false
        if (f.kind != null && f.kind in ENTRY_KINDS && e.kind != f.kind) return false
        if (!f.tag.isNullOrEmpty()) {
            val norm = LibraryRules.normalizeTags(listOf(f.tag))
            val want = norm.firstOrNull() ?: f.tag
            if (!likeContains(" " + e.tags.joinToString(" ") + " ", " $want ")) return false
        }
        for (term in PyText.splitWhitespace(f.q)) {
            val fields = listOf(e.title, e.prompt, e.negative, e.tags.joinToString(" "), e.note, r.raw)
            if (fields.none { likeContains(it, term) }) return false
        }
        return true
    }

    override fun findBySig(sig: String) = rows.firstOrNull { it.sig == sig }?.let { it.e.id to it.e.kind }

    override fun insert(e: NewEntry): Long {
        val id = nextId++
        val proj = Json.parseToJsonElement(e.projectJson) as? JsonObject ?: JsonObject(emptyMap())
        rows += Row(Entry(id, e.created, e.kind, e.title, e.prompt, e.negative, e.seed, e.profile, proj, false, e.tags, e.note, null), e.raw, e.sig)
        return id
    }

    private fun row(id: Long) = rows.firstOrNull { it.e.id == id }
    override fun get(id: Long) = row(id)?.e
    override fun setCreated(id: Long, created: Double) { row(id)?.let { it.e = it.e.copy(created = created) } }
    override fun setKind(id: Long, kind: String) { row(id)?.let { it.e = it.e.copy(kind = kind) } }
    override fun setArchived(id: Long, archived: Double?) { row(id)?.let { it.e = it.e.copy(archived = archived) } }
    override fun setTitle(id: Long, title: String) { row(id)?.let { it.e = it.e.copy(title = title) } }
    override fun setNote(id: Long, note: String) { row(id)?.let { it.e = it.e.copy(note = note) } }
    override fun setTags(id: Long, tags: List<String>) { row(id)?.let { it.e = it.e.copy(tags = tags) } }
    override fun setFav(id: Long, fav: Boolean) { row(id)?.let { it.e = it.e.copy(fav = fav) } }
    override fun deleteIfArchived(id: Long) = rows.removeAll { it.e.id == id && it.e.archived != null }
    override fun deleteAllArchived(): Int { val n = rows.count { it.e.archived != null }; rows.removeAll { it.e.archived != null }; return n }

    override fun pruneHistory(keep: Int): Int {
        val cand = rows.filter { it.e.kind == "history" && !it.e.fav }
            .sortedWith(compareByDescending<Row> { it.e.created }.thenByDescending { it.e.id })
        val drop = cand.drop(keep).toSet()
        rows.removeAll(drop)
        return drop.size
    }

    private fun filtered(f: EntryFilter) = rows.filter { matches(it, f) }
        .sortedWith(compareByDescending<Row> { it.e.created }.thenByDescending { it.e.id })

    override fun search(f: EntryFilter, limit: Int, offset: Int) = filtered(f).drop(offset).take(limit).map { it.e }
    override fun count(f: EntryFilter) = filtered(f).size
    override fun liveTags() = rows.filter { it.e.archived == null && it.e.tags.isNotEmpty() }.map { it.e.tags }
    fun allById(): List<Entry> = rows.sortedBy { it.e.id }.map { it.e }
    override fun allByCreated() = rows.sortedWith(compareBy<Row> { it.e.created }.thenBy { it.e.id }).map { it.e }
}
