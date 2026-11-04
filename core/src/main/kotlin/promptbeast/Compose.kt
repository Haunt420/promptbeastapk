package promptbeast

import kotlinx.serialization.json.*

/** One text box. Boxes sharing a [group] are joined with ", " into one paragraph. */
data class BoxSpec(val id: String, val label: String, val mackKey: String, val group: String)

object Boxes {
    val ALL: List<BoxSpec> = listOf(
        BoxSpec("main", "Main", "description", "main"),
        BoxSpec("style", "Style", "description2", "style"),
        BoxSpec("aesthetic", "Aesthetic", "description6", "aesthetic"),
        BoxSpec("subject", "Subject", "description3", "subject"),
        BoxSpec("pose", "Pose / action", "descriptionp", "subject"),
        BoxSpec("fashion", "Fashion", "descriptionf", "fashion"),
        BoxSpec("scene", "Scene", "description4", "scene"),
        BoxSpec("misc", "Miscellaneous", "description5", "misc"),
    )
    val IDS: Set<String> = ALL.map { it.id }.toSet()
    const val NEGATIVE_KEY = "negative"
    const val SCRATCHPAD_KEY = "scratchpad"
}

/** Python `str(x)` for the JSON values a saved project can plausibly hold. */
internal fun pyStr(e: JsonElement): String = when {
    e is JsonNull -> "None"
    e is JsonPrimitive && e.isString -> e.content
    e is JsonPrimitive && (e.content == "true") -> "True"
    e is JsonPrimitive && (e.content == "false") -> "False"
    e is JsonPrimitive -> e.content
    else -> e.toString()
}

class Project(
    var name: String = "Untitled",
    var boxes: MutableMap<String, String> = LinkedHashMap(),
    var picks: MutableMap<String, MutableList<String>> = LinkedHashMap(),
    var negative: String = "",
    var scratchpad: String = "",
    var profile: String = Profiles.DEFAULT,
    var stickyVars: Boolean = false,
    var fixArticles: Boolean = true,
    var version: Int = 1,
) {
    fun copy(): Project = Project(name, LinkedHashMap(boxes), picks.mapValuesTo(LinkedHashMap()) { it.value.toMutableList() },
        negative, scratchpad, profile, stickyVars, fixArticles, version)

    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("name", name)
        putJsonObject("boxes") { boxes.forEach { (k, v) -> put(k, v) } }
        putJsonObject("picks") { picks.forEach { (k, v) -> putJsonArray(k) { v.forEach { add(it) } } } }
        put("negative", negative)
        put("scratchpad", scratchpad)
        put("profile", profile)
        put("sticky_vars", stickyVars)
        put("fix_articles", fixArticles)
    }

    companion object {
        /** Python truthiness of a JSON value. */
        private fun truthy(e: JsonElement?, default: Boolean): Boolean = when (e) {
            null -> default
            is JsonNull -> false
            is JsonPrimitive -> when {
                e.isString -> e.content.isNotEmpty()
                e.content == "true" -> true
                e.content == "false" -> false
                else -> (e.doubleOrNull ?: 1.0) != 0.0
            }
            is JsonArray -> e.isNotEmpty()
            is JsonObject -> e.isNotEmpty()
        }

        /** Tolerant loader: unknown keys dropped, odd values coerced like the Python core's `str()` / `bool()`. */
        fun fromJson(d: JsonObject): Project {
            fun obj(k: String): JsonObject? = (d[k] as? JsonObject)
            val boxes = LinkedHashMap<String, String>()
            obj("boxes")?.forEach { (k, v) -> if (k in Boxes.IDS) boxes[k] = pyStr(v) }
            val picks = LinkedHashMap<String, MutableList<String>>()
            obj("picks")?.forEach { (k, v) ->
                if (k in Boxes.IDS) picks[k] = ((v as? JsonArray) ?: JsonArray(emptyList())).map { pyStr(it) }.toMutableList()
            }
            val version = d["version"]?.let { v ->
                val pv = v as? JsonPrimitive
                when {
                    pv == null || pv is JsonNull -> throw IllegalArgumentException("bad version")
                    pv.isString -> PyText.strip(pv.content).toIntOrNull()   // int("3") / int(" 4 ")
                    else -> pv.longOrNull?.toInt() ?: pv.doubleOrNull?.toLong()?.toInt()   // int(2.7) truncates
                } ?: throw IllegalArgumentException("bad version")
            } ?: 1
            return Project(
                name = d["name"]?.let { pyStr(it) } ?: "Untitled", boxes = boxes, picks = picks,
                negative = d["negative"]?.let { pyStr(it) } ?: "", scratchpad = d["scratchpad"]?.let { pyStr(it) } ?: "",
                profile = d["profile"]?.let { pyStr(it) } ?: Profiles.DEFAULT,
                stickyVars = truthy(d["sticky_vars"], false), fixArticles = truthy(d["fix_articles"], true), version = version,
            )
        }
    }
}

class Assembled(
    val prompt: String,
    val negative: String,
    val parts: Map<String, String>,
    val seed: Long?,
    val unresolved: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val weightsConverted: Int = 0,
)

object Composer {
    private fun clean(parts: List<String>): List<String> = parts.filter { it.isNotEmpty() && PyText.strip(it).isNotEmpty() }.map { PyText.strip(it) }

    /** One roll. Same project + same seed -> identical output (and identical to the Python core). */
    fun assemble(project: Project, seed: Long? = null): Assembled {
        val rng = PyRandom.ofInt(seed ?: java.security.SecureRandom().nextLong())
        val ev = Evaluator(Dsl.parseVariables(project.scratchpad), rng, sticky = project.stickyVars)
        val profile = Profiles.get(project.profile)

        var parts = LinkedHashMap<String, String>()
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (spec in Boxes.ALL) {
            val evaluated = ArrayList<String>()
            for (p in project.picks[spec.id].orEmpty()) evaluated += ev.eval(p)
            evaluated += ev.eval(project.boxes[spec.id] ?: "")
            val piece = clean(evaluated).joinToString(", ")
            if (piece.isNotEmpty()) {
                parts[spec.id] = piece
                groups.getOrPut(spec.group) { ArrayList() } += piece
            }
        }
        var prompt = groups.values.joinToString("\n\n") { it.joinToString(", ") }
        var negative = PyText.strip(ev.eval(project.negative))

        var converted = 0
        if (!profile.readsWeights) {
            val (p1, c1) = Weights.stripWeights(prompt)
            prompt = p1
            val (n1, c2) = Weights.stripWeights(negative, emphasis = false)
            negative = n1
            converted = c1 + c2
            parts = parts.mapValuesTo(LinkedHashMap()) { Weights.stripWeights(it.value).first }
        }
        if (project.fixArticles) {
            prompt = Articles.fixArticles(prompt)
            parts = parts.mapValuesTo(LinkedHashMap()) { Articles.fixArticles(it.value) }
        }
        return Assembled(prompt, negative, parts, seed, ev.unresolved.sorted(), ev.errors.toList(), converted)
    }

    /** n variants with consecutive seeds (base, base+1, ...); random base if seed is null. */
    fun roll(project: Project, n: Int = 4, seed: Long? = null): List<Assembled> {
        val base = seed ?: java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 1L shl 31)
        return (0 until maxOf(1, n)).map { assemble(project, base + it) }
    }
}
