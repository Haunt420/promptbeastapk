package promptbeast

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A tiny HTTP/1.1 server on a loopback socket (plain java.net: runs the same on a JVM and on a device). */
class FakeHttp(private val handler: (Req) -> Resp) : AutoCloseable {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    class Resp(val status: Int = 200, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val delayMs: Long = 0)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val seen = CopyOnWriteArrayList<Req>()

    init {
        thread(isDaemon = true, name = "fake-http") {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { serve(s) } catch (e: Exception) { /* client went away */ } finally { try { s.close() } catch (e: Exception) {} } }
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream().buffered()
        val first = readLine(input) ?: return
        val parts = first.split(" ")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val n = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(n)
        var got = 0
        while (got < n) { val r = input.read(body, got, n - got); if (r < 0) break; got += r }
        val req = Req(parts[0], parts.getOrElse(1) { "/" }, headers, body)
        seen += req
        val resp = handler(req)
        if (resp.delayMs > 0) Thread.sleep(resp.delayMs)
        val out = s.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${resp.status} X\r\nContent-Length: ${resp.body.size}\r\nConnection: close\r\n")
        for ((k, v) in resp.headers) head.append("$k: $v\r\n")
        out.write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
        out.write(resp.body)
        out.flush()
    }

    override fun close() { try { server.close() } catch (e: Exception) {} }
}

/**
 * Real HTTP round trips through [AiProvider.complete] against a loopback fake endpoint. Runs on the JVM (CI) and on the device
 * (where HttpURLConnection is OkHttp, not the JDK's).
 */
object AiHttpSuite {
    private fun ok(content: String) = ("""{"choices":[{"message":{"content":""" + "\"" + content + "\"" + "}}]}").toByteArray()
    private fun msgs(text: String) = listOf(buildJsonObject { put("role", "user"); put("content", text) })
    private fun cfg(port: Int, timeout: Int = 10) = ProviderConfig(kind = "openai", baseUrl = "http://127.0.0.1:$port/v1", model = "m1", timeout = timeout)
    private fun fail(f: () -> Unit): String? = try { f(); null } catch (e: ProviderError) { e.message }

    fun run(): GoldenSuite.Report {
        val rep = GoldenSuite.Report("aihttp")

        FakeHttp { FakeHttp.Resp(body = ok("a {red|blue} coat")) }.use { srv ->
            val c = cfg(srv.port)
            val out = AiProvider.complete(c, " sk-SECRET123 ", msgs("hi é😀"))
            rep.check(out == "a {red|blue} coat") { "request: reply $out" }
            val r = srv.seen.single()
            rep.check(r.method == "POST" && r.path == "/v1/chat/completions") { "request: ${r.method} ${r.path}" }
            rep.check(r.headers["authorization"] == "Bearer sk-SECRET123") { "request: auth ${r.headers["authorization"]}" }
            rep.check(r.headers["content-type"] == "application/json" && r.headers["user-agent"] == "PromptBeast") { "request: headers ${r.headers}" }
            rep.check(String(r.body, Charsets.US_ASCII) == AiProvider.requestBody(c, msgs("hi é😀"))) { "request: body ${String(r.body, Charsets.US_ASCII).take(120)}" }
            AiProvider.complete(c, "", msgs("x"))
            rep.check(srv.seen[1].headers["authorization"] == null) { "request: auth sent without a key" }
            rep.check(AiProvider.test(c, "") == "a {red|blue} coat") { "test: reply" }
        }

        FakeHttp { FakeHttp.Resp(401, body = """{"error":{"message":"bad key sk-SECRET123"}}""".toByteArray()) }.use { srv ->
            val m = fail { AiProvider.complete(cfg(srv.port), "sk-SECRET123", msgs("x")) }
            rep.check(m != null && m.startsWith("HTTP 401 (check the API key)") && !m.contains("sk-SECRET123") && m.contains("***")) { "http401: $m" }
        }
        FakeHttp { FakeHttp.Resp(500) }.use { srv ->
            val m = fail { AiProvider.complete(cfg(srv.port), "k", msgs("x")) }
            rep.check(m == "HTTP 500") { "http500: $m" }
        }
        FakeHttp { FakeHttp.Resp(200, body = """{"error":{"message":"model overloaded"}}""".toByteArray()) }.use { srv ->
            rep.check(fail { AiProvider.complete(cfg(srv.port), "", msgs("x")) } == "model overloaded") { "errbody" }
        }
        FakeHttp { FakeHttp.Resp(200, body = "<html>".toByteArray()) }.use { srv ->
            rep.check(fail { AiProvider.complete(cfg(srv.port), "", msgs("x")) } == AiProvider.NOT_JSON) { "html reply" }
        }
        FakeHttp { FakeHttp.Resp(200, body = """{"choices":[{"message":{"content":[{"text":"ab"},{"text":"cd"}]}}]}""".toByteArray()) }.use { srv ->
            rep.check(AiProvider.complete(cfg(srv.port), "", msgs("x")) == "abcd") { "content parts" }
        }

        // a redirect must not be followed: the Authorization header would go to whatever host it names
        FakeHttp { FakeHttp.Resp(body = ok("elsewhere")) }.use { other ->
            FakeHttp { FakeHttp.Resp(302, headers = mapOf("Location" to "http://127.0.0.1:${other.port}/v1/chat/completions")) }.use { srv ->
                val m = fail { AiProvider.complete(cfg(srv.port), "sk-SECRET123", msgs("x")) }
                rep.check(m != null && m.startsWith("HTTP 302")) { "redirect: $m" }
                rep.check(other.seen.isEmpty()) { "redirect: followed to another host (${other.seen.size} requests)" }
            }
        }

        FakeHttp { FakeHttp.Resp(body = ok("slow"), delayMs = 3000) }.use { srv ->
            val t0 = System.currentTimeMillis()
            val m = fail { AiProvider.complete(cfg(srv.port, timeout = 1), "", msgs("x")) }
            rep.check(m == "Timed out after 1s") { "timeout: $m" }
            rep.check(System.currentTimeMillis() - t0 < 2900) { "timeout: took ${System.currentTimeMillis() - t0} ms" }
        }

        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val refused = fail { AiProvider.complete(cfg(dead), "sk-SECRET123", msgs("x")) }
        rep.check(refused != null && refused.startsWith("Can't reach 127.0.0.1:$dead") && !refused.contains("sk-SECRET123")) { "unreachable: $refused" }

        val huge = ByteArray(AiProvider.MAX_REPLY + 100) { 'a'.code.toByte() }
        "{\"choices\":[{\"message\":{\"content\":\"".toByteArray().copyInto(huge)
        FakeHttp { FakeHttp.Resp(body = huge) }.use { srv ->
            rep.check(fail { AiProvider.complete(cfg(srv.port), "", msgs("x")) } == AiProvider.NOT_JSON) { "huge reply is cut, so it isn't JSON" }
        }

        rep.check(fail { AiProvider.complete(ProviderConfig(kind = "openai", baseUrl = "http://127.0.0.1:9/v1"), "", msgs("x")) }?.startsWith("Pick a model name") == true) { "no model" }
        rep.check(fail { AiProvider.complete(ProviderConfig(kind = "openai", baseUrl = "ftp://x", model = "m"), "", msgs("x")) } == "Base URL must start with http:// or https://") { "bad scheme" }

        // a whole task through a real endpoint: build -> complete -> parse -> apply
        FakeHttp { FakeHttp.Resp(body = ok("a {red|blue} wool coat on [WHO], stitched seams")) }.use { srv ->
            val project = Project(boxes = linkedMapOf("main" to "a {red|blue} coat on [WHO]"), scratchpad = "WHO = a sailor")
            val req = AiTasks.build("enhance", project, box = "main", extra = "rainy")
            val res = AiTasks.parse(req, AiProvider.complete(cfg(srv.port), "", req.messages))
            rep.check(res.texts == listOf("a {red|blue} wool coat on [WHO], stitched seams") && res.notes.isEmpty()) { "task: ${res.texts} ${res.notes}" }
            rep.check(AiTasks.applyResult(project, res, "main").boxes["main"] == res.texts[0] && project.boxes["main"]!!.startsWith("a {red|blue} coat")) { "task: apply" }
            val sent = String(srv.seen.single().body, Charsets.US_ASCII)
            rep.check(sent.contains("rainy") && sent.contains("[WHO]")) { "task: prompt didn't reach the endpoint" }
        }
        return rep
    }
}
