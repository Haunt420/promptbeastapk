package app.promptbeast

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import promptbeast.ENTRY_KINDS
import promptbeast.Entry
import promptbeast.EntryFilter
import promptbeast.EntryStore
import promptbeast.LibraryRules
import promptbeast.NewEntry
import promptbeast.PyText

/**
 * The desktop's `entries` table on android.database.sqlite: same columns, same SQL (see core/library.py), so a library
 * exported on one side imports cleanly on the other. [dbName] = null gives an in-memory database.
 */
class SqliteEntryStore(context: Context, dbName: String? = "library.db") : EntryStore {
    private val helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS entries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, created REAL NOT NULL, kind TEXT NOT NULL DEFAULT 'saved', " +
                    "title TEXT NOT NULL DEFAULT '', prompt TEXT NOT NULL DEFAULT '', negative TEXT NOT NULL DEFAULT '', " +
                    "seed INTEGER, profile TEXT NOT NULL DEFAULT '', project TEXT NOT NULL, raw TEXT NOT NULL DEFAULT '', " +
                    "fav INTEGER NOT NULL DEFAULT 0, tags TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', " +
                    "archived REAL, sig TEXT NOT NULL UNIQUE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS entries_created ON entries(created DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Opens (and creates) the file now so a broken store fails at startup rather than on the first tap. */
    fun open() { db.version }

    private fun idArg(id: Long) = arrayOf(id.toString())

    private fun set(id: Long, fill: ContentValues.() -> Unit) {
        db.update("entries", ContentValues().apply(fill), "id=?", idArg(id))
    }

    override fun findBySig(sig: String): Pair<Long, String>? =
        db.rawQuery("SELECT id, kind FROM entries WHERE sig=?", arrayOf(sig)).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        }

    override fun insert(e: NewEntry): Long {
        val seed = e.seed
        val cv = ContentValues()
        cv.put("created", e.created)
        cv.put("kind", e.kind)
        cv.put("title", e.title)
        cv.put("prompt", e.prompt)
        cv.put("negative", e.negative)
        if (seed != null) cv.put("seed", seed) else cv.putNull("seed")
        cv.put("profile", e.profile)
        cv.put("project", e.projectJson)
        cv.put("raw", e.raw)
        cv.put("tags", e.tags.joinToString(" "))
        cv.put("note", e.note)
        cv.put("sig", e.sig)
        return db.insertOrThrow("entries", null, cv)
    }

    override fun get(id: Long): Entry? =
        db.rawQuery("SELECT * FROM entries WHERE id=?", idArg(id)).use { c -> if (c.moveToFirst()) c.toEntry() else null }

    override fun setCreated(id: Long, created: Double) = set(id) { put("created", created) }
    override fun setKind(id: Long, kind: String) = set(id) { put("kind", kind) }
    override fun setArchived(id: Long, archived: Double?) = set(id) { if (archived != null) put("archived", archived) else putNull("archived") }
    override fun setTitle(id: Long, title: String) = set(id) { put("title", title) }
    override fun setNote(id: Long, note: String) = set(id) { put("note", note) }
    override fun setTags(id: Long, tags: List<String>) = set(id) { put("tags", tags.joinToString(" ")) }
    override fun setFav(id: Long, fav: Boolean) = set(id) { put("fav", if (fav) 1 else 0) }

    override fun deleteIfArchived(id: Long): Boolean = db.delete("entries", "id=? AND archived IS NOT NULL", idArg(id)) > 0

    override fun deleteAllArchived(): Int = db.delete("entries", "archived IS NOT NULL", null)

    override fun pruneHistory(keep: Int): Int = db.delete(
        "entries",
        "kind='history' AND fav=0 AND id NOT IN (SELECT id FROM entries WHERE kind='history' AND fav=0 " +
            "ORDER BY created DESC, id DESC LIMIT ${maxOf(0, keep)})",
        null,
    )

    private class Where(val sql: String, val args: Array<String>)

    private fun where(f: EntryFilter): Where {
        val w = ArrayList<String>()
        val args = ArrayList<String>()
        w += if (f.archived) "archived IS NOT NULL" else "archived IS NULL"
        if (f.favs) w += "fav=1"
        val kind = f.kind
        if (kind != null && kind in ENTRY_KINDS) { w += "kind=?"; args += kind }
        val tag = f.tag
        if (!tag.isNullOrEmpty()) {
            val norm = LibraryRules.normalizeTags(listOf(tag))
            w += "(' ' || tags || ' ') LIKE ?"
            args += "% ${norm.firstOrNull() ?: tag} %"
        }
        for (term in PyText.splitWhitespace(f.q)) {
            w += "(title || char(10) || prompt || char(10) || negative || char(10) || tags || char(10) || note || char(10) || raw) LIKE ? ESCAPE '\\'"
            args += LibraryRules.likePattern(term)
        }
        return Where(w.joinToString(" AND "), args.toTypedArray())
    }

    override fun search(f: EntryFilter, limit: Int, offset: Int): List<Entry> {
        val w = where(f)
        return db.rawQuery(
            "SELECT * FROM entries WHERE ${w.sql} ORDER BY created DESC, id DESC LIMIT $limit OFFSET $offset", w.args,
        ).use { c -> rows(c) }
    }

    override fun count(f: EntryFilter): Int {
        val w = where(f)
        return db.rawQuery("SELECT COUNT(*) FROM entries WHERE ${w.sql}", w.args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    override fun liveTags(): List<List<String>> =
        db.rawQuery("SELECT tags FROM entries WHERE archived IS NULL AND tags != ''", null).use { c ->
            val out = ArrayList<List<String>>()
            while (c.moveToNext()) out += (c.getString(0) ?: "").split(' ').filter { it.isNotEmpty() }
            out
        }

    override fun allByCreated(): List<Entry> =
        db.rawQuery("SELECT * FROM entries ORDER BY created, id", null).use { c -> rows(c) }

    private fun rows(c: Cursor): List<Entry> {
        val out = ArrayList<Entry>()
        while (c.moveToNext()) out += c.toEntry()
        return out
    }

    private fun Cursor.toEntry(): Entry {
        fun s(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
        val proj: JsonObject = try {
            Json.parseToJsonElement(s("project")) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        val seedAt = getColumnIndexOrThrow("seed")
        val archAt = getColumnIndexOrThrow("archived")
        return Entry(
            id = getLong(getColumnIndexOrThrow("id")),
            created = getDouble(getColumnIndexOrThrow("created")),
            kind = s("kind"), title = s("title"), prompt = s("prompt"), negative = s("negative"),
            seed = if (isNull(seedAt)) null else getLong(seedAt),
            profile = s("profile"), project = proj,
            fav = getInt(getColumnIndexOrThrow("fav")) != 0,
            tags = s("tags").split(' ').filter { it.isNotEmpty() },
            note = s("note"),
            archived = if (isNull(archAt)) null else getDouble(archAt),
        )
    }
}
