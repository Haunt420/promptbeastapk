package promptbeast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The reply was discarded (minor-coded wording, or the model refused). */
class Rejected(message: String) : Exception(message)

class AiRequest(
    val task: String,
    val messages: List<JsonObject>,
    val allowedVars: List<String> = emptyList(),
    val expect: String = "text",            // text | variants | sections
    val needsVision: Boolean = false,
    val box: String = "",
)

class AiResult(
    val texts: List<String> = emptyList(),                       // text / variants
    val sections: LinkedHashMap<String, String> = LinkedHashMap(),   // doctor / analyze: box id -> text
    val notes: MutableList<String> = ArrayList(),
    val raw: String = "",
)

/**
 * AI tasks: build chat messages from a project, clean + validate the reply. Port of core/ai/tasks.py, checked against the
 * Python with golden vectors. Hard rules baked into every task: all people are adults, DSL syntax survives, minor-coded
 * output is discarded.
 */
object AiTasks {
    val TASKS: LinkedHashMap<String, String> = linkedMapOf(
        "enhance" to "Enhance a box with concrete detail",
        "reshape" to "Rewrite in the model's native style",
        "variations" to "Write alternative takes on a box",
        "doctor" to "Prompt Doctor: review and fix every box",
        "analyze" to "Analyze an image into prompt boxes",
    )
    val BOX_IDS: List<String> = Boxes.ALL.map { it.id }
    private const val S = PyText.WS
    private const val D = "\\p{Nd}"
    private val VAR_RE = Regex("\\[([A-Z0-9_]+)\\]")
    private val UNIX = RegexOption.UNIX_LINES
    private val PREFACE = Regex("^[$S]*(?:sure|certainly|okay|ok|of course|absolutely|here)${PyRe.B}[^\\n]{0,120}:[$S]*\\n+", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("^[$S]*```[a-zA-Z]*\\n(.*?)\\n?```[$S]*$", setOf(RegexOption.DOT_MATCHES_ALL, UNIX))
    private val ONE_FENCE = Regex("```[a-zA-Z]*\\n(.*?)\\n?```", setOf(RegexOption.DOT_MATCHES_ALL, UNIX))
    private val REFUSED = Regex("^[$S]*REFUSED${PyRe.B}", RegexOption.IGNORE_CASE)
    private val DASHES = Regex("^[$S]*-{3,}[$S]*$", setOf(RegexOption.MULTILINE, UNIX))
    private val BULLET = Regex("^[$S]*(?:$D+[.)]|[-*])[$S]+")
    private val HEADER = Regex("^[$S]*#{2,4}[$S]*([A-Za-z0-9_ ]+?)[$S]*$", UNIX)

    fun systemPrompt(project: Project, allowedVars: List<String>): String {
        val prof = Profiles.get(project.profile)
        val style = if (prof.prose)
            "The target image model reads connected, descriptive sentences best; it ignores (word:1.2) weights and " +
                "brackets, and negatives barely work, so describe what you want rather than what to avoid."
        else "The target model reads comma-separated tags and understands (word:1.2) weights."
        val varsLine = if (allowedVars.isNotEmpty())
            "Variables available: " + allowedVars.joinToString(", ") { "[$it]" } + ". Copy them exactly."
        else "There are no variables; do not invent any [NAME]."
        return "You write and edit prompts for an AI image generator. The user is an adult working on adult-oriented art.\n" +
            "Rules you never break:\n" +
            "- Every person depicted is an adult (18+). Never describe, imply or hint at anyone under 18, and never use " +
            "youthful or child-coded wording for a person. If a request needs that, reply with exactly: REFUSED: minor\n" +
            "- Keep the user's syntax intact: {a|b|c} picks one option at random (keep braces balanced), [NAME] is a variable " +
            "reference, a backslash escapes the next character. Do not rename or remove variables.\n" +
            "- $varsLine\n" +
            "- $style\n" +
            "- Reply with the requested text only: no preface, no explanation, no markdown fences, no quotes around it."
    }

    private fun boxText(p: Project, box: String): String =
        PyText.strip(if (box == Boxes.NEGATIVE_KEY) p.negative else (p.boxes[box] ?: ""))

    private fun msg(role: String, content: String): JsonObject = buildJsonObject { put("role", role); put("content", content) }

    /** @throws IllegalArgumentException with the same text as the Python ValueError. */
    fun build(task: String, project: Project, box: String = "main", extra: String = "", n: Int = 4,
              image: ByteArray? = null, imageMime: String = "image/png"): AiRequest {
        require(task in TASKS) { "unknown task $task" }
        val allowed = Dsl.parseVariables(project.scratchpad).keys.toList()
        val sys = msg("system", systemPrompt(project, allowed))
        val ex = PyText.strip(extra)
        val note = if (ex.isNotEmpty()) "\nExtra direction from the user: $ex" else ""
        val prof = Profiles.get(project.profile)

        if (task == "enhance" || task == "reshape" || task == "variations") {
            val text = boxText(project, box)
            require(text.isNotEmpty()) { "the $box box is empty" }
            val label = Boxes.ALL.firstOrNull { it.id == box }?.label ?: box
            val ask: String
            val exp: String
            when (task) {
                "enhance" -> {
                    ask = "Enhance this '$label' box. Add concrete, visible detail (materials, light, texture, small telling " +
                        "specifics) without changing who or what it is about. Keep the length within about 60% more than the " +
                        "original and keep every {a|b} choice and [NAME] where it is.$note"
                    exp = "text"
                }
                "reshape" -> {
                    ask = if (prof.prose)
                        "Rewrite this box as flowing, connected descriptive sentences (no tag lists, no weights), keeping every " +
                            "detail and every {a|b} choice and [NAME].$note"
                    else
                        "Rewrite this box as comma-separated tags, most important first, adding (word:1.2) weights only where " +
                            "emphasis matters, keeping every {a|b} choice and [NAME].$note"
                    exp = "text"
                }
                else -> {
                    ask = "Write $n different takes on this '$label' box. Keep the subject and intent, change the details, angle " +
                        "and wording, and turn some details into {a|b} choices where variety makes sense. Separate the takes " +
                        "with a line containing only ---.$note"
                    exp = "variants"
                }
            }
            return AiRequest(task, listOf(sys, msg("user", "$ask\n\n$text")), allowed, exp, false, box)
        }

        if (task == "doctor") {
            val parts = ArrayList<String>()
            for (spec in Boxes.ALL) {
                val t = boxText(project, spec.id)
                if (t.isNotEmpty()) parts += "### ${spec.id}\n$t"
            }
            require(parts.isNotEmpty()) { "there is nothing to review yet" }
            val issues = ArrayList<String>()
            for (spec in Boxes.ALL) {
                for (i in Lint.lint(project.boxes[spec.id] ?: "", project.profile, scratchpad = project.scratchpad)) {
                    if (i.code != "I000") issues += "- ${spec.id}: ${i.message}"
                }
            }
            val neg = boxText(project, Boxes.NEGATIVE_KEY)
            val ask = "Review this prompt, split into boxes, for the target model. Fix contradictions, vague wording, filler quality " +
                "tags the model ignores, and anything the automatic checks flagged. Return ONLY the boxes you changed, each as a " +
                "line '### <box id>' followed by its full new text. Start with a section '### notes' holding at most 5 short " +
                "bullets saying what you changed and why.$note\n\nAutomatic checks:\n" + (if (issues.isEmpty()) "- none" else issues.joinToString("\n")) +
                (if (neg.isNotEmpty()) "\n\nNegative prompt (do not rewrite): $neg" else "") + "\n\n" + parts.joinToString("\n\n")
            return AiRequest(task, listOf(sys, msg("user", ask)), allowed, "sections", false)
        }

        // analyze
        require(image != null && image.isNotEmpty()) { "attach an image first" }
        val ids = BOX_IDS.filter { it in setOf("main", "style", "subject", "pose", "fashion", "scene") }.joinToString(", ")
        val ask = "Describe this image as an image-generation prompt split into boxes. Return sections, each a line '### <box id>' " +
            "followed by its text, using these ids: $ids. Be concrete and visual; describe only what is visible. If any " +
            "person in the image appears to be under 18, reply with exactly: REFUSED: minor.$note"
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", ask) })
            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", AiProvider.imageDataUri(image, imageMime)) }) })
        }
        val user = buildJsonObject { put("role", "user"); put("content", content) }
        return AiRequest(task, listOf(sys, user), allowed, "sections", true)
    }

    /** The whole request as one pasteable block (manual provider). */
    fun flatten(req: AiRequest): String = req.messages.joinToString("\n\n") { m ->
        val c = m["content"]
        if (c is JsonArray) c.joinToString("\n") { p ->
            ((p as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: "[image omitted, attach it in the chat yourself]"
        } else (c as JsonPrimitive).content
    }

    // ---- reply handling ----------------------------------------------------------------------------------------------
    fun cleanText(raw: String): String {
        var s = PyText.strip(raw)
        val pre = PREFACE.find(PyText.asciiFold(s))
        if (pre != null && pre.range.first == 0) s = s.substring(pre.range.last + 1)
        s = PyText.strip(s)
        FENCE.find(s)?.let { s = PyText.strip(it.groupValues[1]) }
        if (PyText.length(s) > 1) {
            val a = s.first()
            if (a == s.last() && (a == '"' || a == '\'' || a == '\u201c')) s = PyText.strip(s.substring(1, s.length - 1))
        }
        return s
    }

    private fun check(text: String, allowed: List<String>, where: String, notes: MutableList<String>) {
        if (PackParser.ageGuardHit(text)) throw Rejected("The reply contained minor-coded wording and was discarded.")
        var depth = 0
        for (ch in text.replace("\\{", "").replace("\\}", "")) {
            if (ch == '{') depth++
            if (ch == '}') depth--
            if (depth < 0) break
        }
        if (depth != 0) notes += "$where: unbalanced { } in the reply (check before applying)"
        val extra = VAR_RE.findAll(text).map { it.groupValues[1] }.toSet().filter { it !in allowed.toSet() }.sorted()
        if (extra.isNotEmpty()) notes += "$where: reply uses undefined variable(s) " + extra.joinToString(", ") { "[$it]" }
    }

    private fun sections(raw: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        var cur: String? = null
        for (line in PyText.splitLines(raw)) {
            val m = HEADER.find(line)
            if (m != null) {
                cur = PyText.strip(m.groupValues[1]).lowercase().replace(" ", "_")
                out.getOrPut(cur) { "" }
            } else if (cur != null) {
                out[cur] = out.getValue(cur) + line + "\n"
            }
        }
        val res = LinkedHashMap<String, String>()
        for ((k, v) in out) if (PyText.strip(v).isNotEmpty()) res[k] = cleanText(v)
        return res
    }

    private fun splitVariants(body: String): List<String> {
        val parts = ArrayList<String>()
        var last = 0
        for (m in DASHES.findAll(body)) { parts += body.substring(last, m.range.first); last = m.range.last + 1 }
        parts += body.substring(last)
        return parts
    }

    /** @throws Rejected when the reply has to be discarded. */
    fun parse(req: AiRequest, raw: String): AiResult {
        val body = cleanText(raw)
        if (REFUSED.containsMatchIn(PyText.asciiFold(body))) throw Rejected("The model declined: it would not write that (minors are never allowed).")
        var texts: List<String> = emptyList()
        var secs = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        when (req.expect) {
            "text" -> {
                val fences = ONE_FENCE.findAll(body).map { it.groupValues[1] }.toList()
                texts = listOf(if (fences.size == 1) cleanText(fences[0]) else body)   // one fenced block = the answer, rest is chatter
            }
            "variants" -> texts = splitVariants(body).map { cleanText(it) }.filter { it.isNotEmpty() }.map { BULLET.replaceFirst(it, "") }
            else -> {
                secs = sections(body)
                if (req.task == "doctor") {
                    val n = secs.remove("notes") ?: ""
                    if (n.isNotEmpty()) {
                        for (ln in PyText.splitLines(n)) if (PyText.strip(ln).isNotEmpty()) notes += PyText.strip(ln.trimStart('-', '*', ' '))
                    }
                    secs = LinkedHashMap(secs.filterKeys { it in BOX_IDS || it == Boxes.NEGATIVE_KEY })
                } else {
                    secs = LinkedHashMap(secs.filterKeys { it in BOX_IDS })
                }
            }
        }
        if (texts.isEmpty() && secs.isEmpty()) throw Rejected("The reply was empty or in a format I couldn't read.")
        texts.forEachIndexed { i, t -> check(t, req.allowedVars, if (texts.size > 1) "reply ${i + 1}" else "reply", notes) }
        for ((k, t) in secs) check(t, req.allowedVars, k, notes)
        return AiResult(texts, secs, notes, raw)
    }

    /** A copy of the project with the result applied (text -> box, sections -> their boxes). */
    fun applyResult(project: Project, res: AiResult, box: String = "", variant: Int = 0): Project {
        val p = Project.fromJson(project.toJson())
        if (res.sections.isNotEmpty()) {
            for ((k, v) in res.sections) {
                if (k == Boxes.NEGATIVE_KEY) p.negative = v else if (k in BOX_IDS) p.boxes[k] = v
            }
        } else if (res.texts.isNotEmpty() && box.isNotEmpty()) {
            val t = res.texts[maxOf(0, minOf(variant, res.texts.size - 1))]
            if (box == Boxes.NEGATIVE_KEY) p.negative = t else p.boxes[box] = t
        }
        return p
    }
}
