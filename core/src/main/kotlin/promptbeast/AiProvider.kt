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
class ProviderError(message: String) : Exception(message)

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
    const val NOT_JSON = "The endpoint did not return JSON (is the base URL an OpenAI-compatible API?)"

    private val URL_RE = Regex("^(?:[Hh][Tt][Tt][Pp][Ss]?)://([^/?#${PyRe.S}]+)")
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

    /** The POST body exactly as Python's json.dumps writes it. */
    fun requestBody(cfg: ProviderConfig, messages: List<JsonElement>): String = buildString {
        append("{\"model\": ")
        PyJson.quoteAscii(this, PyText.strip(cfg.model))
        append(", \"messages\": ").append(PyJson.dumps(JsonArray(messages), ensureAscii = true))
        append(", \"temperature\": ").append(PyFloat.repr(cfg.temperature))
        append(", \"max_tokens\": ").append(cfg.maxTokens)
        append(", \"stream\": false}")
    }

    /** [body] is the first MAX_ERR_BODY bytes of the error body. */
    fun httpErrorMessage(code: Int, body: ByteArray, key: String): String {
        val snippet = redact(PyText.strip(PyText.decodeUtf8(body)), key)
        val hint = HINTS[code] ?: ""
        return "HTTP $code" + (if (hint.isNotEmpty()) " ($hint)" else "") + (if (snippet.isNotEmpty()) ": " + PyText.prefix(snippet, 300) else "")
    }

    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else if (e.content == "true") true else if (e.content == "false") false
            else (e.content.toDoubleOrNull() ?: 1.0) != 0.0
        is JsonObject -> e.isNotEmpty()
        is JsonArray -> e.isNotEmpty()
    }

    /** The endpoint's reply bytes -> the assistant text, or a [ProviderError] with a readable message. */
    fun parseCompletion(raw: ByteArray, key: String): String {
        val data = StrictJson.parse(PyText.decodeUtf8(raw), MAX_DEPTH) ?: throw ProviderError(NOT_JSON)
        val obj = data as? JsonObject
        val err = obj?.get("error")
        if (obj != null && truthy(err)) {
            val m = if (err is JsonObject) err["message"] else err
            var msg = (m as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (msg == null || PyText.strip(msg).isEmpty()) msg = "The endpoint reported an error (no message)"
            throw ProviderError(PyText.prefix(redact(msg, key), 300))
        }
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

    /** One chat completion over HTTP(S). Blocking: call it off the main thread. */
    fun complete(cfg: ProviderConfig, apiKey: String, messages: List<JsonElement>): String {
        val key = PyText.strip(apiKey)
        if (PyText.strip(cfg.model).isEmpty()) throw ProviderError("Pick a model name first (for example the id your provider lists).")
        val url = chatUrl(cfg.baseUrl)
        val body = requestBody(cfg, messages).toByteArray(Charsets.US_ASCII)
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: IOException) {
            throw ProviderError("Can't reach ${netloc(url)}: ${redact(e.message ?: "bad URL", key)}")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = cfg.timeout * 1000
            conn.readTimeout = cfg.timeout * 1000
            conn.instanceFollowRedirects = false           // a redirect would carry the Authorization header to another host
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "PromptBeast")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            // (no streaming mode: the JDK refuses to hand over the error body of a 401 once the request was streamed)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code >= 300) {
                val head = conn.errorStream?.use { readAtMost(it, MAX_ERR_BODY) } ?: ByteArray(0)
                throw ProviderError(httpErrorMessage(code, head, key))
            }
            return parseCompletion(conn.inputStream.use { readAtMost(it, MAX_REPLY) }, key)
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
