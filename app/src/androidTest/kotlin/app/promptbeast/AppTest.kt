package app.promptbeast

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import promptbeast.AiHttpSuite
import promptbeast.FakeHttp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Launches the real app on an emulator and drives it. Screenshots land in <filesDir>/shots; CI pulls them with run-as. */
@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm: PbViewModel get() = ViewModelProvider(rule.activity)[PbViewModel::class.java]

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(rule.activity.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Fires the node's click action directly: a snackbar sitting over the node can't swallow it the way a touch can. */
    private fun tap(tag: String, scroll: Boolean = true) {
        val n = rule.onNodeWithTag(tag)
        (if (scroll) n.performScrollTo() else n).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun aiState(): String =
        ("kind=${vm.aiKind} task=${vm.aiTask} box=${vm.aiBox} busy=${vm.aiBusy} problem=${vm.aiProblem.take(90)} status=${vm.aiStatus.take(90)} " +
            "instr=${vm.aiInstruction.length} paste=${vm.aiPaste.take(20)} result=${vm.aiResult != null} out=${vm.aiOut.take(40)} " +
            "main=${vm.boxes["main"]?.take(40)} rollNodes=${rule.onAllNodesWithTag("roll").fetchSemanticsNodes().size}").replace("\n", " ")

    /** waitUntil that names the step and dumps the AI state, so one CI failure says where it died. */
    private fun await(label: String, ms: Long = 5_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: Throwable) { throw AssertionError("timeout[$label] " + aiState()) }
    }

    @Test
    fun rollKeepAndLibrary() {
        rule.onNodeWithTag("box_main").performTextInput("a {red|green|blue} fox, 85mm")
        shot("1_edit")
        rule.onNodeWithTag("roll").performClick()
        rule.waitUntil(10_000) { vm.rolls.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(4, vm.rolls.size)
        assertTrue(vm.rolls.all { it.prompt.contains("fox") })
        shot("2_rolls")
        val before = vm.lib.count(kind = "saved")
        rule.onAllNodesWithText("Keep")[0].performClick()
        rule.waitUntil(10_000) { vm.lib.count(kind = "saved") > before || vm.lib.count(kind = "saved") >= 1 }
        rule.onNodeWithTag("nav_library").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("entries", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("3_library")
    }

    @Test
    fun forgeSendsToCompose() {
        rule.onNodeWithTag("nav_forge").performClick()
        rule.waitUntil(10_000) { vm.forgeResult != null }
        shot("4_forge")
        rule.onNodeWithTag("forge_send").performClick()
        rule.waitForIdle()
        assertTrue(vm.boxes.values.any { it.isNotBlank() })
        rule.onNodeWithTag("roll").assertExists()
    }

    @Test
    fun parityOnDevice() {
        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithTag("selftest_run").performClick()
        rule.waitUntil(300_000) { !vm.selfTestRunning && vm.selfTest.size == 13 }
        shot("5_selftest")
        val bad = vm.selfTest.filter { it.startsWith("FAIL") }
        val assets = listOf("", "golden", "packs").joinToString(" | ") { d -> "assets/$d: " + rule.activity.assets.list(d)?.joinToString(",") }
        // one line: Gradle's console only shows the first lines of an assertion message
        assertTrue("self-test: " + vm.selfTest.joinToString(" | ").replace("\n", " ") + " || " + assets, bad.isEmpty())
    }

    // ---- Mack pane, against a stand-in page served with Mack's own origin (no network) -----------------------------------

    private val fakeMack = """<!doctype html><html><body>
        <textarea name="description"></textarea>
        <textarea name="negative"></textarea>
        <button onclick="window.__gen=(window.__gen||0)+1">Generate</button>
        <iframe id="f" srcdoc="&lt;textarea name='descriptionp'&gt;&lt;/textarea&gt;"></iframe>
        </body></html>"""

    /** Runs [js] in the stand-in page and returns the JSON it evaluates to (strings come back quoted). */
    private fun page(js: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        rule.runOnUiThread { vm.mackEval(js) { out = it; latch.countDown() } }
        assertTrue("page did not answer: $js", latch.await(10, TimeUnit.SECONDS))
        return out
    }

    private fun waitForPage(js: String, want: String) {
        val end = System.currentTimeMillis() + 15_000
        var got = ""
        while (System.currentTimeMillis() < end) {
            got = page(js)
            if (got == want) return
            Thread.sleep(150)
        }
        assertEquals("page: $js", want, got)
    }

    @Test
    fun mackPaneFillsPullsAndGenerates() {
        rule.runOnUiThread {
            vm.newProject()
            vm.onBox("main", "a coat")
            vm.onBox("pose", "standing")
            vm.onNegative("blurry")
            vm.mackLoadHtml(fakeMack)
        }
        rule.waitUntil(20_000) { vm.mackState == "live" }
        rule.onNodeWithTag("nav_mack").performClick()
        rule.waitForIdle()
        shot("6_mack")

        val top = "document.querySelector('textarea[name=description]').value"
        val nested = "document.getElementById('f').contentDocument.querySelector('textarea').value"

        // Fill: top-level inputs and the nested frame's input
        rule.onNodeWithTag("mack_fill").performClick()
        waitForPage(top, "\"a coat\"")
        waitForPage(nested, "\"standing\"")
        waitForPage("document.querySelector('textarea[name=negative]').value", "\"blurry\"")

        // Pull: Mack's text wins, boxes Mack doesn't have stay as they were
        page("(function(){var t=document.querySelector('textarea[name=description]');t.value='from mack';" +
            "document.getElementById('f').contentDocument.querySelector('textarea').value='kneeling';return 1})()")
        rule.runOnUiThread { vm.onBox("style", "keep me") }
        rule.onNodeWithTag("mack_pull").performClick()
        rule.waitUntil(15_000) { vm.boxes["main"] == "from mack" }
        assertEquals("kneeling", vm.boxes["pose"])
        assertEquals("keep me", vm.boxes["style"])
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("roll").fetchSemanticsNodes().isNotEmpty() }   // back on Compose

        // the page survived the tab switch; Fill & generate presses Generate
        rule.onNodeWithTag("nav_mack").performClick()
        rule.waitForIdle()
        waitForPage(top, "\"from mack\"")
        rule.runOnUiThread { vm.onBox("main", "second") }
        rule.onNodeWithTag("mack_fill_go").performClick()
        waitForPage(top, "\"second\"")
        waitForPage("String(window.__gen||0)", "\"1\"")
    }

    /** Fill & go on pages that do not look like the stand-in: odd labels still match; a real miss reports what the page has. */
    @Test
    fun mackGenerateFindsOddButtonsAndReportsMisses() {
        val odd = """<!doctype html><html><body><textarea name="description"></textarea>
            <button onclick="window.__other=1">Copy link</button>
            <div role="button" style="cursor:pointer;width:80px;height:20px" onclick="window.__gen=(window.__gen||0)+1">&#127922; Generate!</div>
            </body></html>"""
        rule.runOnUiThread { vm.newProject(); vm.onBox("main", "odd"); vm.mackLoadHtml(odd) }
        rule.waitUntil(20_000) { vm.mackState == "live" }
        rule.onNodeWithTag("nav_mack").performClick()
        rule.waitForIdle()
        tap("mack_fill_go", scroll = false)
        waitForPage("String(window.__gen||0)", "\"1\"")
        assertEquals("", vm.mackDiag)

        val none = """<!doctype html><html><body><textarea name="description"></textarea>
            <button>Roll</button><a href="#top">Help</a></body></html>"""
        rule.runOnUiThread { vm.mackLoadHtml(none) }
        rule.waitUntil(20_000) { vm.mackState == "live" }
        rule.waitForIdle()
        tap("mack_fill_go", scroll = false)
        await("miss report") { vm.mackDiag.contains("Roll") && vm.mackDiag.contains("Help") }
        rule.onNodeWithTag("mack_diag").assertExists()
    }

    /** User packs: import, they show in Forge (even with slot ids that clash with the screen's own list keys), age guard, delete. */
    @Test
    fun userPacksImportShowAndDelete() {
        val pack = """@pack id=hats name="Hats and such" adults=false
            @slot notes label="Notes" box=fashion order=60
            a fez
            a teen beanie
            a bowler hat
            @slot head label="Head" box=fashion order=61
            a laurel wreath
            """.trimIndent().toByteArray()
        try {
            var ok = false
            rule.runOnUiThread { ok = vm.packImportBytes("Hats & Such.pbpack", pack) }
            assertTrue("import: " + vm.toast, ok)
            await("pack in library") { vm.forgeLib.slots.containsKey("notes") && vm.forgeLib.slots.containsKey("head") }
            assertEquals(listOf("a fez", "a bowler hat"), vm.forgeLib.slots["notes"]!!.tags.map { it.text })   // age guard dropped the teen tag
            assertTrue(File(rule.activity.filesDir, "packs/hats-such.pbpack").exists())
            assertEquals(1, vm.userPacks.size)
            assertEquals(1, vm.userPacks[0].notes)
            // a second import with the same name gets its own file, not an overwrite
            rule.runOnUiThread { ok = vm.packImportBytes("Hats & Such.pbpack", pack) }
            assertTrue(ok && File(rule.activity.filesDir, "packs/hats-such-2.pbpack").exists())
            rule.runOnUiThread { vm.packDelete("hats-such-2") }
            // refusals leave nothing behind
            rule.runOnUiThread { ok = vm.packImportBytes("junk.pbpack", byteArrayOf(0x41, 0xFF.toByte())) }
            assertTrue(!ok && !File(rule.activity.filesDir, "packs/junk.pbpack").exists())

            tap("nav_forge", scroll = false)
            await("packs card in Forge") { rule.onAllNodesWithTag("pack_row_hats-such").fetchSemanticsNodes().isNotEmpty() }
            shot("8_packs")

            // they survive a restart of the view-model's state: re-reading from disk gives the same library
            rule.runOnUiThread { vm.onForgeAdults(false) }
            assertTrue(vm.forgeLib.slots.containsKey("head"))

            tap("pack_delete_hats-such")
            await("pack removed") { !vm.forgeLib.slots.containsKey("notes") && vm.userPacks.isEmpty() }
            assertTrue(!File(rule.activity.filesDir, "packs/hats-such.pbpack").exists())
        } finally {
            File(rule.activity.filesDir, "packs").listFiles()?.forEach { it.delete() }
        }
    }

    // ---- AI Tools: a real round trip to a loopback endpoint, apply to Compose, and the manual paste-back path -------------

    @Test
    fun aiToolsRunApplyAndManualRoundTrip() {
        val reply = """{"choices":[{"message":{"content":"a {red|blue} wool coat on [WHO], stitched seams"}}]}"""
        FakeHttp { FakeHttp.Resp(body = reply.toByteArray()) }.use { srv ->
            rule.runOnUiThread {
                vm.newProject()
                vm.onBox("main", "a {red|blue} coat on [WHO]")
                vm.onScratchpad("WHO = a sailor")
                vm.onAiKind("openai"); vm.onAiBaseUrl("http://127.0.0.1:${srv.port}/v1"); vm.onAiModel("m1")
                vm.onAiTask("enhance"); vm.onAiBox("main"); vm.onAiExtra("rainy")
            }
            tap("nav_ai", scroll = false)
            tap("ai_run")
            await("enhance reply", 20_000) { vm.aiResult != null }
            rule.waitForIdle()
            assertEquals("a {red|blue} wool coat on [WHO], stitched seams", vm.aiOut)
            val sent = String(srv.seen.single().body, Charsets.US_ASCII)
            assertTrue("prompt reached the endpoint: " + sent.take(200), sent.contains("rainy") && sent.contains("[WHO]") && sent.contains("\"model\": \"m1\""))
            shot("7_ai")
            tap("ai_apply")
            await("apply wrote main") { vm.boxes["main"] == "a {red|blue} wool coat on [WHO], stitched seams" }
            await("back on compose") { rule.onAllNodesWithTag("roll").fetchSemanticsNodes().isNotEmpty() }
        }

        // manual provider: build the instruction, paste a refusal (discarded), then a good reply (read)
        rule.runOnUiThread { vm.onBox("style", "35mm film"); vm.onAiKind("manual"); vm.onAiTask("doctor") }
        tap("nav_ai", scroll = false)
        tap("ai_run")
        await("manual instruction") { vm.aiInstruction.isNotEmpty() }
        assertTrue(vm.aiInstruction.contains("Review this prompt") && vm.aiInstruction.contains("18+"))
        rule.runOnUiThread { vm.onAiPaste("REFUSED: minor") }
        tap("ai_read")
        await("refusal discarded") { vm.aiProblem.contains("declined") }
        assertEquals("35mm film", vm.boxes["style"])
        rule.runOnUiThread { vm.onAiPaste("### notes\n- sharper\n\n### style\n35mm film, grain") }
        tap("ai_read")
        await("doctor reply read") { vm.aiResult?.sections?.get("style") == "35mm film, grain" }
        assertEquals(listOf("sharper"), vm.aiResult!!.notes)
    }

    /** The provider's real HTTP behaviour (redirects, timeouts, error bodies) on this device's HttpURLConnection. */
    @Test
    fun aiHttpOnDevice() {
        val rep = AiHttpSuite.run()
        assertTrue(rep.toString() + " | " + rep.failures.joinToString(" | ").replace("\n", " "), rep.failures.isEmpty())
    }
}
