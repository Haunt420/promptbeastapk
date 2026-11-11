package promptbeast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64

/** Readable, user-facing failure (never contains the API key). */
open class ProviderError(message: String) : Exception(message)

/** The user pressed Stop while a reply was streaming. */
class Cancelled : ProviderError("Stopped")

/** `repr(float)` as CPython prints it (shortest digits that read back the same; 1e-05 / 1e+16 style outside 1e-4 .. 1e16). */
object PyFloat {
    fun repr(d: Double): String {
        require(d.isFinite()) { "not finite" }
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val a = Math.abs(d)
        val exact = BigDecimal(a)
        var rounded = exact
        for (p in 1..17) {
            val r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            rounded = r
            if (r.toString().toDouble() == a) break
        }
        val unscaled = rounded.unscaledValue().toString()
        val decpt = unscaled.length - rounded.scale()          // value = 0.DIGITS x 10^decpt
        val digits = unscaled.trimEnd('0').ifEmpty { "0" }
        val n = digits.length
        val body = if (decpt <= -4 || decpt > 16) {
            val e = decpt - 1
            val m = if (n > 1) digits[0] + "." + digits.substring(1) else digits
            m + "e" + (if (e < 0) "-" else "+") + Math.abs(e).toString().padStart(2, '0')
        } else if (decpt <= 0) {
            "0." + "0".repeat(-decpt) + digits
        } else if (decpt >= n) {
            digits + "0".repeat(decpt - n) + ".0"
        } else {
            digits.substring(0, decpt) + "." + digits.substring(decpt)
        }
        return if (d < 0) "-$body" else body
    }
}

/** Where the AI tasks send their prompts. Port of core/ai/provider.py (the parts that are pure are checked against the Python). */
class ProviderConfig(
    var kind: String = "manual",
    var baseUrl: String = "https://openrouter.ai/api/v1",
    var model: String = "",
    var temperature: Double = 0.8,
    var maxTokens: Int = 1200,
    var timeout: Int = 90,
    var vision: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("base_url", baseUrl)
        put("model", model)
        put("temperature", temperature)
        put("max_tokens", maxTokens)
        put("timeout", timeout)
        put("vision", vision)
    }

    companion object {
        val KINDS = listOf("manual", "openai")
        private val INT = Regex("-?[0-9]+")

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A JSON integer literal (no '.', no exponent) that isn't a boolean. */
        private fun intOf(e: JsonElement?): BigInteger? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull || p.isString || !INT.matches(p.content)) return null
            return p.content.toBigInteger()
        }

        private fun clamp(v: BigInteger, lo: Int, hi: Int): Int =
            if (v < BigInteger.valueOf(lo.toLong())) lo else if (v > BigInteger.valueOf(hi.toLong())) hi else v.toInt()

        /** Defensive: anything that isn't the right type is ignored, numbers are clamped. */
        fun fromJson(e: JsonElement?): ProviderConfig {
            val c = ProviderConfig()
            val d = e as? JsonObject ?: return c
            str(d["kind"])?.let { c.kind = it }
            str(d["base_url"])?.let { c.baseUrl = it }
            str(d["model"])?.let { c.model = it }
            (d["temperature"] as? JsonPrimitive)?.let { p ->
                if (p !is JsonNull && !p.isString && p.content != "true" && p.content != "false") {
                    val f = p.content.toDoubleOrNull()
                    if (f != null && f.isFinite()) c.temperature = f
                }
            }
            intOf(d["max_tokens"])?.let { c.maxTokens = clamp(it, 64, 8000) }
            intOf(d["timeout"])?.let { c.timeout = clamp(it, 5, 600) }
            (d["vision"] as? JsonPrimitive)?.let { p -> if (p !is JsonNull && !p.isString && (p.content == "true" || p.content == "false")) c.vision = p.content == "true" }
            if (c.kind !in KINDS) c.kind = "manual"
            // Python's min(2.0, max(0.0, t)): ties keep the first argument, so -0.0 becomes 0.0
            val hi = if (c.temperature > 0.0) c.temperature else 0.0
            c.temperature = if (hi < 2.0) hi else 2.0
            return c
        }
    }
}

object AiProvider {
    const val MAX_REPLY = 4_000_000
    const val MAX_ERR_BODY = 600
    const val MAX_DEPTH = 64
    const val MAX_MODELS = 1000
    const val NOT_JSON = "The endpoint did not return JSON (is the base URL an OpenAI-compatible API?)"

    private val URL_RE = Regex("^(?:[Hh][Tt][Tt][Pp][Ss]?)://([^/?#${PyRe.S}]+)")
    /** What a model id looks like; anything else in a /models reply is skipped. */
    private val MODEL_ID = Regex("[A-Za-z0-9@][A-Za-z0-9._:/@+~#-]{0,199}")
    private val HINTS = mapOf(401 to "check the API key", 403 to "the key lacks access to this model", 404 to "wrong base URL or model id",
        429 to "rate limited, try again shortly")

    fun imageDataUri(data: ByteArray, mime: String = "image/png"): String =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(data)

    private fun redact(text: String, key: String): String = if (key.isNotEmpty()) text.replace(key, "***") else text

    /** host[:port] of an http(s) URL, "" when it isn't one. */
    fun netloc(url: String): String = URL_RE.find(url)?.groupValues?.get(1) ?: ""

    fun chatUrl(baseUrl: String?): String {
        val base = PyText.strip(baseUrl ?: "").trimEnd('/')
        if (netloc(base).isEmpty()) throw ProviderError("Base URL must start with http:// or https://")
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** The endpoint's model list: the base URL (minus a trailing /chat/completions) + /models. */
    fun modelsUrl(baseUrl: String?): String {
        var base = PyText.strip(baseUrl ?: "").trimEnd('/')
        if (netloc(base).isEmpty()) throw ProviderError("Base URL must start with http:// or https://")
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions").trimEnd('/')
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /** The POST body exactly as Python's json.dumps writes it. */
    fun requestBody(cfg: ProviderConfig, messages: List<JsonElement>, stream: Boolean = false): String = buildString {
        append("{\"model\": ")
        PyJson.quoteAscii(this, PyText.strip(cfg.model))
        append(", \"messages\": ").append(PyJson.dumps(JsonArray(messages), ensureAscii = true))
        append(", \"temperature\": ").append(PyFloat.repr(cfg.temperature))
        append(", \"max_tokens\": ").append(cfg.maxTokens)
        append(", \"stream\": ").append(if (stream) "true" else "false").append("}")
    }

    /** [body] is the first MAX_ERR_BODY bytes of the error body. */
    fun httpErrorMessage(code: Int, body: ByteArray, key: String): String {
        val snippet = redact(PyText.strip(PyText.decodeUtf8(body)), key)
        val hint = HINTS[code] ?: ""
        return "HTTP $code" + (if (hint.isNotEmpty()) " ($hint)" else "") + (if (snippet.isNotEmpty()) ": " + PyText.prefix(snippet, 300) else "")
    }

    internal fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else if (e.content == "true") true else if (e.content == "false") false
            else (e.content.toDoubleOrNull() ?: 1.0) != 0.0
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
    }

    internal fun errorText(err: JsonElement?, key: String): String {
        val m = if (err is JsonObject) err["message"] else err
        var msg = (m as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (msg == null || PyText.strip(msg).isEmpty()) msg = "The endpoint reported an error (no message)"
        return PyText.prefix(redact(msg, key), 300)
    }

    /** The endpoint's reply bytes -> the assistant text, or a [ProviderError] with a readable message. */
    fun parseCompletion(raw: ByteArray, key: String): String {
        val data = StrictJson.parse(PyText.decodeUtf8(raw), MAX_DEPTH) ?: throw ProviderError(NOT_JSON)
        val obj = data as? JsonObject
        val err = obj?.get("error")
        if (obj != null && truthy(err)) throw ProviderError(errorText(err, key))
        val message = ((obj?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val content = message?.get("content") ?: throw ProviderError("Unexpected response shape (no choices[0].message.content)")
        val text: String? = when {
            content is JsonPrimitive && content !is JsonNull && content.isString -> content.content
            content is JsonArray -> buildString {      // some servers return content parts
                for (p in content) ((p as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.let { append(it.content) }
            }
            else -> null
        }
        if (text == null || PyText.strip(text).isEmpty()) throw ProviderError("The model returned an empty reply")
        return text
    }

    /**
     * A /models reply -> model ids in the server's order. Understands OpenAI / OpenRouter (`data[].id`), Ollama (`models[].name`)
     * and a bare list of ids; skips anything that doesn't look like an id and drops duplicates.
     */
    fun parseModels(raw: ByteArray, key: String): List<String> {
        val data = StrictJson.parse(PyText.decodeUtf8(raw), MAX_DEPTH) ?: throw ProviderError(NOT_JSON)
        val obj = data as? JsonObject
        if (obj != null && truthy(obj["error"])) throw ProviderError(errorText(obj["error"], key))
        val items: JsonArray = when {
            obj != null -> (obj["data"] as? JsonArray) ?: (obj["models"] as? JsonArray)
            data is JsonArray -> data
            else -> null
        } ?: throw ProviderError("Unexpected response shape (no model list)")
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (it in items) {
            val mid: String? = when (it) {
                is JsonObject -> {
                    val id = (it["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    if (id != null && PyText.strip(id).isNotEmpty()) id else (it["name"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                }
                is JsonPrimitive -> if (it.isString) it.content else null
                else -> null
            }
            if (mid == null) continue
            val id = PyText.strip(mid)
            if (!MODEL_ID.matches(id) || !seen.add(id)) continue
            out.add(id)
            if (out.size >= MAX_MODELS) break
        }
        if (out.isEmpty()) throw ProviderError("The endpoint listed no models")
        return out
    }

    /** One HTTP round trip; [read] turns the open connection into the result. Failures become [ProviderError]s. */
    private fun <T> call(cfg: ProviderConfig, key: String, url: String, body: ByteArray?, accept: String, read: (HttpURLConnection) -> T): T {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: IOException) {
            throw ProviderError("Can't reach ${netloc(url)}: ${redact(e.message ?: "bad URL", key)}")
        }
        try {
            conn.requestMethod = if (body != null) "POST" else "GET"
            conn.connectTimeout = cfg.timeout * 1000
            conn.readTimeout = cfg.timeout * 1000
            conn.instanceFollowRedirects = false           // a redirect would carry the Authorization header to another host
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("User-Agent", "PromptBeast")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // (no streaming mode for the request: the JDK refuses to hand over the error body of a 401 once the request was streamed)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code >= 300) {
                val head = conn.errorStream?.use { readAtMost(it, MAX_ERR_BODY) } ?: ByteArray(0)
                throw ProviderError(httpErrorMessage(code, head, key))
            }
            return read(conn)
        } catch (e: ProviderError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProviderError("Timed out after ${cfg.timeout}s")
        } catch (e: IOException) {
            val why = redact(e.message ?: e.javaClass.simpleName, key)
            throw ProviderError(if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is javax.net.ssl.SSLException)
                "Can't reach ${netloc(url)}: $why" else "Network error: $why")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * One chat completion over HTTP(S). Blocking: call it off the main thread. With [onDelta] (each new piece of text) or
     * [cancelled] (polled between pieces; true throws [Cancelled]) the reply is streamed; the result is the whole text either way.
     */
    fun complete(cfg: ProviderConfig, apiKey: String, messages: List<JsonElement>,
                 onDelta: ((String) -> Unit)? = null, cancelled: (() -> Boolean)? = null): String {
        val key = PyText.strip(apiKey)
        if (PyText.strip(cfg.model).isEmpty()) throw ProviderError("Pick a model name first (for example the id your provider lists).")
        val url = chatUrl(cfg.baseUrl)
        if (onDelta == null && cancelled == null) {
            val body = requestBody(cfg, messages).toByteArray(Charsets.US_ASCII)
            return call(cfg, key, url, body, "application/json") { parseCompletion(it.inputStream.use { s -> readAtMost(s, MAX_REPLY) }, key) }
        }
        val body = requestBody(cfg, messages, stream = true).toByteArray(Charsets.US_ASCII)
        return call(cfg, key, url, body, "text/event-stream") { readStream(it, key, onDelta, cancelled) }
    }

    private fun readStream(conn: HttpURLConnection, key: String, onDelta: ((String) -> Unit)?, cancelled: (() -> Boolean)?): String {
        if (!(conn.contentType ?: "").lowercase().contains("event-stream")) {
            val text = parseCompletion(conn.inputStream.use { readAtMost(it, MAX_REPLY) }, key)    // the server ignored stream=true: one JSON reply
            onDelta?.invoke(text)
            return text
        }
        val parser = SseParser(key)
        val parts = StringBuilder()
        fun take(pieces: List<String>) { for (p in pieces) { parts.append(p); onDelta?.invoke(p) } }
        conn.inputStream.use { input ->
            val buf = ByteArray(8192)
            var size = 0
            while (true) {
                if (cancelled?.invoke() == true) throw Cancelled()
                val n = input.read(buf)
                if (n < 0) break
                size += n
                take(parser.feed(buf, 0, n))
                if (parser.error.isNotEmpty() || parser.done || size >= MAX_REPLY) break
            }
        }
        take(parser.finish())
        if (parser.error.isNotEmpty()) throw ProviderError(parser.error)
        val text = parts.toString()
        if (PyText.strip(text).isEmpty()) throw ProviderError("The model returned an empty reply")
        return text
    }

    /** The model ids the endpoint offers (GET <base>/models). Blocking. */
    fun listModels(cfg: ProviderConfig, apiKey: String): List<String> {
        val key = PyText.strip(apiKey)
        val url = modelsUrl(cfg.baseUrl)
        return parseModels(call(cfg, key, url, null, "application/json") { it.inputStream.use { s -> readAtMost(s, MAX_REPLY) } }, key)
    }

    /** Reads up to [max] bytes (Python's `r.read(n)`: stops at n, or at the end of the body). */
    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 8192))
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Connection test: a one-word round trip. */
    fun test(cfg: ProviderConfig, apiKey: String): String {
        val msg = buildJsonObject { put("role", "user"); put("content", "Reply with the single word: ready") }
        return PyText.prefix(PyText.strip(complete(cfg, apiKey, listOf(msg))), 80)
    }
}


/**
 * Incremental parser for an OpenAI-style streamed reply (`text/event-stream`). [feed] returns the text pieces that completed
 * lines carried, in order; the result never depends on how the bytes were chunked (lines are split on the raw `\n` byte first,
 * so a multi-byte character can straddle chunks). [done] flips on `data: [DONE]`; [error] holds an in-stream `{"error": ...}`.
 */
class SseParser(private val key: String = "") {
    var done = false
        private set
    var error = ""
        private set
    private val buf = ByteArrayOutputStream()

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size): List<String> {
        val out = ArrayList<String>()
        var start = off
        val end = off + len
        for (i in off until end) {
            if (data[i] == '\n'.code.toByte()) {
                buf.write(data, start, i - start)
                val line = buf.toByteArray()
                buf.reset()
                line(line, out)
                start = i + 1
            }
        }
        if (start < end) buf.write(data, start, end - start)
        return out
    }

    /** The stream ended: a last line without a newline still counts. */
    fun finish(): List<String> {
        val out = ArrayList<String>()
        if (buf.size() > 0) {
            val line = buf.toByteArray()
            buf.reset()
            line(line, out)
        }
        return out
    }

    private fun line(bytes: ByteArray, out: MutableList<String>) {
        val text = PyText.decodeUtf8(bytes).trimEnd('\r')
        if (done || !text.startsWith("data:")) return                // comments (": keep-alive") and other SSE fields are ignored
        val payload = PyText.strip(text.substring(5))
        if (payload.isEmpty()) return
        if (payload == "[DONE]") { done = true; return }
        val obj = StrictJson.parse(payload, AiProvider.MAX_DEPTH) as? JsonObject ?: return
        val err = obj["error"]
        if (AiProvider.truthy(err)) {
            if (error.isEmpty()) error = AiProvider.errorText(err, key)
            return
        }
        val first = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val piece = (first["delta"] as? JsonObject)?.get("content")
        val text2: String? = when {
            piece is JsonPrimitive && piece !is JsonNull && piece.isString -> piece.content
            piece is JsonArray -> buildString {
                for (p in piece) ((p as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.let { append(it.content) }
            }
            else -> null
        }
        if (!text2.isNullOrEmpty()) out.add(text2)
    }
}
