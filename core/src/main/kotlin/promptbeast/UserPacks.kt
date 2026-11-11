package promptbeast

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What came of one picked file: [ok] says whether it may be saved as [stem]; [message] is what the person is told. */
class PackImport(val ok: Boolean, val message: String, val stem: String = "", val text: String = "", val pack: Pack? = null)

/**
 * Your own `.pbpack` files, the same format and the same loader rules as the desktop's `~/.local/share/promptbeast/packs/`.
 * Pure (bytes and strings in, results out) so the JVM tests cover it; the app only does the file picking and the disk I/O.
 * The age guard lives in [PackParser], so a user pack can never carry minor-coded text.
 */
object UserPacks {
    const val EXT = ".pbpack"
    const val MAX_BYTES = 2_000_000
    const val MAX_PACKS = 40
    private const val MAX_STEM = 40
    private val NOT_STEM = Regex("[^a-z0-9_-]+")

    /** A starting point to copy and edit. */
    val TEMPLATE: String = """
# My pack. One thing per line; # starts a comment.
#   @pack id=<id> name="<shown name>" adults=false
#   @slot <id> label="<shown name>" box=<main|style|aesthetic|subject|pose|fashion|scene|misc> order=<roll order> chance=<0-1> count=<n or lo-hi> sep=", "
#   <tag text> ; w=<weight> ; give=a,b ; need=a,b ; any=a,b ; not=a,b ; tier=sensual|nude|partner (adult packs only)
# give/need/any/not are traits: a tag with need=cold only appears once something earlier gave "cold".
# The tag text goes into the box as written, so {a|b} and [VAR] work inside it.
# Reusing a built-in slot id adds your tags to that slot. Everyone in a pack must be an adult.
@pack id=mine name="My pack" adults=false

@slot charm label="Pocket charm" box=fashion order=46 chance=0.5
a brass key on a leather cord ; give=casual ; not=formal
a tiny enamel pin on the lapel ; w=2
a pressed flower tucked behind the ear
a {red|green|navy} ribbon tied to a wrist ; give=casual
""".trimStart()

    /** A new @slot block for the pack editor's "Insert slot". */
    val SLOT_SNIPPET: String = """
@slot new_slot label="New slot" box=misc order=60 chance=0.5
a first tag
a second tag ; w=2
"""

    /** Why this text can't be saved as a pack (the editor's checks), or null when it can. */
    fun validateText(text: String): String? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return "That is over ${MAX_BYTES / 1_000_000} MB, which is too big for a pack."
        val p = parsePack(text, "draft")
        if (p.slots.isEmpty() || tagCount(p) == 0) {
            val why = p.issues.firstOrNull()?.let { " ($it)" } ?: ""
            return "No tags found$why. A pack needs an @slot line followed by one tag per line."
        }
        return null
    }

    /** File stem for a picked document's display name: lowercase [a-z0-9_-], at most 40 characters, never empty. */
    fun stemFor(displayName: String?): String {
        var s = (displayName ?: "").substringAfterLast('/').substringAfterLast('\\')
        if (s.lowercase(java.util.Locale.ROOT).endsWith(EXT)) s = s.substring(0, s.length - EXT.length)
        s = NOT_STEM.replace(s.lowercase(java.util.Locale.ROOT), "-").trim('-', '_')
        if (s.length > MAX_STEM) s = s.substring(0, MAX_STEM).trim('-', '_')
        return s.ifEmpty { "pack" }
    }

    /** [stem], or stem-2, stem-3... when taken. */
    fun uniqueStem(stem: String, taken: Set<String>): String {
        if (stem !in taken) return stem
        val base = if (stem.length > MAX_STEM - 4) stem.substring(0, MAX_STEM - 4) else stem
        for (i in 2..9999) if ("$base-$i" !in taken) return "$base-$i"
        return "$base-x"
    }

    /** Strict UTF-8 (a stray byte is an error, not a replacement character); null when it is not text. */
    fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (e: CharacterCodingException) { null }

    private fun tagCount(p: Pack) = p.slots.values.sumOf { it.tags.size }

    /** Everything that is checked before a picked file is saved. [taken] = stems already installed. */
    fun check(displayName: String?, bytes: ByteArray, taken: Set<String>): PackImport {
        if (taken.size >= MAX_PACKS) return PackImport(false, "You already have $MAX_PACKS packs. Delete one first.")
        if (bytes.size > MAX_BYTES) return PackImport(false, "That file is over ${MAX_BYTES / 1_000_000} MB, which is too big for a pack.")
        val text = decode(bytes) ?: return PackImport(false, "That file isn't UTF-8 text, so it can't be a pack.")
        val stem = uniqueStem(stemFor(displayName), taken)
        val p = parsePack(text, stem)
        if (p.slots.isEmpty() || tagCount(p) == 0) {
            val why = p.issues.firstOrNull()?.let { " ($it)" } ?: ""
            return PackImport(false, "No tags found$why. A pack needs an @slot line followed by one tag per line; use Copy template.")
        }
        val notes = if (p.issues.isEmpty()) "" else "; ${p.issues.size} note${if (p.issues.size != 1) "s" else ""} (see Forge)"
        val adult = if (p.adults) "; it is an 18+ pack, hidden until Adult packs is on" else ""
        return PackImport(true, "Imported $stem: ${p.slots.size} slot${if (p.slots.size != 1) "s" else ""}, ${tagCount(p)} tags$adult$notes", stem, text, p)
    }

    /**
     * Parse the installed files (stem to bytes, in name order). Never throws; an unreadable file becomes an empty pack with an issue.
     * With [adults] = true nothing is skipped, so the result pairs up one-to-one with [files].
     */
    fun load(files: List<Pair<String, ByteArray>>, adults: Boolean): List<Pack> {
        val out = ArrayList<Pack>()
        for ((stem, bytes) in files) {
            val text = if (bytes.size > MAX_BYTES) null else decode(bytes)
            if (text == null) {
                out += Pack(id = stem, name = stem).also { it.issues += "$stem$EXT: unreadable (too big or not UTF-8 text)" }
                continue
            }
            val p = parsePack(text, stem)
            if (p.adults && !adults) continue
            out += p
        }
        return out
    }
}
