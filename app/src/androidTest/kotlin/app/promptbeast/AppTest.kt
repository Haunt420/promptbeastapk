package app.promptbeast

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
        rule.waitUntil(300_000) { !vm.selfTestRunning && vm.selfTest.size == 14 }
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
            rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("pack_row_hats-such"))     // the list is lazy: bring the card into composition
            await("packs card in Forge") { rule.onAllNodesWithTag("pack_row_hats-such").fetchSemanticsNodes().isNotEmpty() }
            shot("8_packs")

            // they survive a restart of the view-model's state: re-reading from disk gives the same library
            rule.runOnUiThread { vm.onForgeAdults(false) }
            assertTrue(vm.forgeLib.slots.containsKey("head"))

            rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("pack_delete_hats-such"))
            tap("pack_delete_hats-such", scroll = false)
            await("pack removed") { !vm.forgeLib.slots.containsKey("notes") && vm.userPacks.isEmpty() }
            assertTrue(!File(rule.activity.filesDir, "packs/hats-such.pbpack").exists())
        } finally {
            File(rule.activity.filesDir, "packs").listFiles()?.forEach { it.delete() }
        }
    }

    /** Forge drop-downs: pick a tag (locks), type your own, minor-coded text is refused, Random unlocks, a multi slot toggles + Done. */
    @Test
    fun forgeDropdownsPickLockAndRandom() {
        try {
            rule.runOnUiThread { vm.onForgeAdults(true); vm.onForgeTier(4) }
            val acts = vm.forgeLib.slots["acts"]
            assertTrue("NSFW Acts slot with explicit tags", acts != null && acts.tags.any { it.tier == 4 })
            tap("nav_forge", scroll = false)
            await("forge ready") { vm.forgeResult != null }

            fun openPicker(slotId: String) {
                rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("slot_pick_$slotId"))
                tap("slot_pick_$slotId", scroll = false)
                await("picker open for $slotId") { rule.onAllNodesWithTag("slot_picker").fetchSemanticsNodes().isNotEmpty() }
            }
            fun closed() = await("picker closed") { rule.onAllNodesWithTag("slot_picker").fetchSemanticsNodes().isEmpty() }

            // 1) pick a library tag from the special NSFW slot: it locks and shows up in the roll
            val tag = acts!!.tags.filter { it.tier == 4 }.minByOrNull { it.text.length }!!.text
            openPicker("acts")
            rule.onNodeWithTag("pick_search").performTextInput(tag)
            tap("opt:$tag", scroll = false)
            closed()
            await("acts locked") { vm.forgeLocks["acts"] == listOf(tag) }
            assertEquals(listOf(tag), vm.forgeResult!!.picks["acts"]?.texts)
            shot("9_picker_locked")

            // 2) your own wording
            openPicker("age")
            rule.onNodeWithTag("pick_search").performTextInput("a lighthouse keeper")
            tap("pick_custom", scroll = false)
            closed()
            await("custom locked") { vm.forgeLocks["age"] == listOf("a lighthouse keeper") }
            assertEquals(listOf("a lighthouse keeper"), vm.forgeResult!!.picks["age"]?.texts)

            // 3) minor-coded wording never gets in: no "use" row, and the VM refuses it too
            openPicker("age")
            rule.onNodeWithTag("pick_search").performTextInput("a teen")
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithTag("pick_custom").fetchSemanticsNodes().isEmpty())
            var ok = true
            rule.runOnUiThread { ok = vm.forgeSetLock("age", listOf("a teen")) }
            assertTrue(!ok && vm.forgeLocks["age"] == listOf("a lighthouse keeper"))
            tap("pick_cancel", scroll = false)
            closed()

            // 4) Random hands the slot back to the dice
            openPicker("age")
            tap("pick_random", scroll = false)
            closed()
            await("age unlocked") { !vm.forgeLocks.containsKey("age") }

            // 5) a slot that rolls several tags: toggle two, Done locks both
            val multi = vm.forgeLib.slots["accessory"]!!
            assertTrue(multi.hi > 1)
            val two = multi.tags.filter { it.tier == 0 }.map { it.text }.sorted().take(2)
            openPicker("accessory")
            for (t in two) {
                rule.onNodeWithTag("pick_list").performScrollToNode(hasTestTag("opt:$t"))
                tap("opt:$t", scroll = false)
            }
            tap("pick_done", scroll = false)
            closed()
            await("accessory locked") { vm.forgeLocks["accessory"] == two }

            // 6) turning "how far" down releases a lock that sits above the new limit
            rule.runOnUiThread { vm.onForgeTier(1) }
            await("explicit lock released") { !vm.forgeLocks.containsKey("acts") }
            assertTrue(vm.forgeLocks.containsKey("accessory"))
        } finally {
            rule.runOnUiThread { vm.onForgeAdults(false) }
        }
    }

    /** The pack editor: new pack from the template, Insert slot, save, edit in place, refuse an empty pack, age guard note. */
    @Test
    fun packEditorCreatesEditsAndRefuses() {
        val packs = File(rule.activity.filesDir, "packs")
        try {
            tap("nav_forge", scroll = false)
            rule.runOnUiThread { vm.packEditNew(); vm.onPackEditName("Hat Rack") }
            await("editor open") { rule.onAllNodesWithTag("pack_editor").fetchSemanticsNodes().isNotEmpty() }
            tap("pack_edit_slot", scroll = false)
            await("slot inserted") { vm.packEditText.contains("@slot new_slot") }
            rule.runOnUiThread { vm.onPackEditText(vm.packEditText + "a teen beanie\n") }
            rule.waitForIdle()
            tap("pack_edit_save", scroll = false)
            await("saved") { !vm.packEditOpen && File(packs, "hat-rack.pbpack").exists() }
            await("in the library") { vm.forgeLib.slots.containsKey("charm") && vm.forgeLib.slots.containsKey("new_slot") }
            assertTrue(vm.forgeLib.slots["new_slot"]!!.tags.none { it.text.contains("teen") })        // age guard

            // edit in place (same file), then a refusal that keeps the editor open
            rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("pack_edit_hat-rack"))
            tap("pack_edit_hat-rack", scroll = false)
            await("editor reopened") { vm.packEditOpen && vm.packEditStem == "hat-rack" && vm.packEditText.contains("a first tag") }
            rule.runOnUiThread { vm.onPackEditText(vm.packEditText + "a deerstalker\n") }
            tap("pack_edit_save", scroll = false)
            await("edit saved") { !vm.packEditOpen && vm.forgeLib.slots["new_slot"]!!.tags.any { it.text == "a deerstalker" } }
            assertEquals(1, packs.listFiles { f -> f.name.endsWith(".pbpack") }!!.size)
            rule.runOnUiThread { vm.packEditExisting("hat-rack"); vm.onPackEditText("# nothing here") }
            tap("pack_edit_save", scroll = false)
            await("refused") { vm.packEditOpen && vm.packEditProblem.startsWith("No tags found") }
            rule.onNodeWithTag("pack_edit_problem").assertExists()
            assertTrue(File(packs, "hat-rack.pbpack").readText().contains("a deerstalker"))          // the good file is untouched
            tap("pack_edit_cancel", scroll = false)
            await("closed") { !vm.packEditOpen }
        } finally {
            rule.runOnUiThread { vm.packEditClose() }
            packs.listFiles()?.forEach { it.delete() }
        }
    }

    // ---- AI Tools: model picker, streamed reply, Stop ---------------------------------------------------------------------

    @Test
    fun aiModelPickerStreamingAndStop() {
        fun sse(vararg pieces: String) = pieces.map { ("data: {\"choices\":[{\"delta\":{\"content\":\"" + it + "\"}}]}\n\n").toByteArray() }
        val stream = mapOf("Content-Type" to "text/event-stream")
        var slow = false
        FakeHttp { req ->
            when {
                req.path.endsWith("/models") -> FakeHttp.Resp(body = """{"data":[{"id":"m1"},{"id":"vendor/m2:free"},{"id":"zeta-large"}]}""".toByteArray())
                slow -> FakeHttp.Resp(headers = stream, chunks = sse("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"), chunkDelayMs = 400)
                else -> FakeHttp.Resp(headers = stream, chunks = sse("a {red|", "blue} wool coat ", "on [WHO]") + "data: [DONE]\n\n".toByteArray())
            }
        }.use { srv ->
            rule.runOnUiThread {
                vm.newProject()
                vm.onBox("main", "a {red|blue} coat on [WHO]")
                vm.onScratchpad("WHO = a sailor")
                vm.onAiKind("openai"); vm.onAiBaseUrl("http://127.0.0.1:${srv.port}/v1"); vm.onAiModel("")
                vm.onAiTask("enhance"); vm.onAiBox("main")
            }
            tap("nav_ai", scroll = false)

            // the model picker: fetch, search, choose
            tap("ai_models")
            await("picker open") { vm.aiModelPickerOpen && rule.onAllNodesWithTag("model_picker").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("m1", "vendor/m2:free", "zeta-large"), vm.aiModels)
            rule.onNodeWithTag("model_search").performTextInput("m2")
            tap("model_opt:vendor/m2:free", scroll = false)
            await("model chosen") { vm.aiModel == "vendor/m2:free" && !vm.aiModelPickerOpen }

            // a streamed reply arrives, becomes the parsed result, and the request asked for a stream
            tap("ai_run")
            await("streamed result", 20_000) { vm.aiResult != null }
            assertTrue(vm.aiOut, vm.aiOut.contains("wool coat"))
            assertTrue(!vm.aiBusy && vm.aiLive.isEmpty())
            assertTrue(srv.seen.any { it.method == "POST" && String(it.body, Charsets.US_ASCII).endsWith("\"stream\": true}") })

            // Stop releases the screen at once and a late reply never lands
            rule.runOnUiThread { vm.aiResult = null; slow = true }
            tap("ai_run")
            await("live text", 15_000) { vm.aiLive.isNotEmpty() }
            tap("ai_stop")
            await("released") { !vm.aiBusy }
            assertEquals("Stopped", vm.aiStatus)
            Thread.sleep(1_500)
            rule.waitForIdle()
            assertTrue(vm.aiResult == null && !vm.aiBusy)
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

    /** The page's file inputs: a request reaches the screen, the picked file reaches the page, and every way out answers the page. */
    @Test
    fun mackFileChooserAnswersThePage() {
        class Params : WebChromeClient.FileChooserParams() {
            override fun getMode() = WebChromeClient.FileChooserParams.MODE_OPEN
            override fun getAcceptTypes() = arrayOf("image/*")
            override fun isCaptureEnabled() = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        val got = ArrayList<String>()
        fun cb(tag: String) = ValueCallback<Array<Uri>> { got += tag + ":" + (it?.joinToString(",") ?: "null") }
        val pic = Uri.parse("content://media/external/images/media/1")
        rule.runOnUiThread {
            assertTrue(vm.mackFileChooser(cb("a"), Params()))
            assertTrue("request handed to the screen", vm.mackFileRequest != null)
            vm.mackFileDeliver(arrayOf(pic))                       // picked
            assertTrue(vm.mackFileRequest == null)
            assertTrue(vm.mackFileChooser(cb("b"), Params()))
            assertTrue(vm.mackFileChooser(cb("c"), Params()))      // a second request answers the first with "nothing"
            vm.mackFileDeliver(null)                               // picker dismissed
            vm.mackFileDeliver(arrayOf(pic))                       // a late duplicate answer is ignored
            assertTrue(vm.mackFileChooser(cb("d"), Params()))
            vm.mackReload()                                        // leaving the page answers too
        }
        assertEquals(listOf("a:$pic", "b:null", "c:null", "d:null"), got)
    }

    /** Saved sign-in: stored encrypted, typed only into a visible password form, never into hidden ones; Forget wipes it. */
    @Test
    fun mackSignInFillsTheVisibleForm() {
        val page = """<!doctype html><html><body>
            <input type="text" id="u"><input type="password" id="p">
            <input type="password" id="hid" style="display:none">
            <iframe id="f" srcdoc="&lt;input type='email' id='fu'&gt;&lt;input type='password' id='fp'&gt;"></iframe>
            </body></html>"""
        rule.runOnUiThread { vm.signInUser = "killa"; vm.signInPass = "hunter2"; vm.signInSave(); vm.mackLoadHtml(page) }
        assertTrue("saved", vm.signInSaved)
        val raw = java.io.File(rule.activity.noBackupFilesDir, "signin.bin").readBytes()
        assertTrue("file is not plaintext", !String(raw, Charsets.ISO_8859_1).contains("hunter2") && !String(raw, Charsets.ISO_8859_1).contains("killa"))
        rule.waitUntil(20_000) { vm.mackState == "live" }
        rule.runOnUiThread { vm.mackSignIn() }
        waitForPage("document.getElementById('u').value + '/' + document.getElementById('p').value", "\"killa/hunter2\"")
        waitForPage("document.getElementById('hid').value", "\"\"")
        waitForPage("(function(){var d=document.getElementById('f').contentDocument;return d.getElementById('fu').value+'/'+d.getElementById('fp').value})()", "\"killa/hunter2\"")
        rule.runOnUiThread { vm.signInForget() }
        assertTrue(!vm.signInSaved && !java.io.File(rule.activity.noBackupFilesDir, "signin.bin").exists())
    }

    /** Surprise me: odd/dare pin free slots inside the tier limit, wild moves the heat but keeps locks, send lands in Compose. */
    @Test
    fun forgeSurpriseButtons() {
        rule.runOnUiThread { vm.onSurpriseFlavour("any"); vm.onForgeAdults(true); vm.onForgeTier(2); vm.onForgeHeat(0.05f) }
        tap("nav_forge", scroll = false)
        await("forge ready") { vm.forgeResult != null }
        rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("surprise_odd"))
        tap("surprise_odd", scroll = false)
        await("one pinned") { vm.forgeLocks.size == 1 }
        tap("surprise_dare", scroll = false)
        await("three more pinned") { vm.forgeLocks.size == 4 }
        for ((sid, texts) in vm.forgeLocks) {
            val idx = vm.forgeLib.slots[sid]!!.tags.associateBy { it.text }
            assertTrue("$sid within tier", texts.size == 1 && texts[0] in idx && idx[texts[0]]!!.tier <= 2)
        }
        val locks = vm.forgeLocks
        tap("surprise_wild", scroll = false)
        await("heat moved") { vm.forgeHeat >= 0.35f }
        assertEquals(locks, vm.forgeLocks)
        rule.runOnUiThread { vm.onBox("main", ""); vm.onForgeMode("replace") }
        tap("surprise_send", scroll = false)
        await("landed in compose") { rule.onAllNodesWithTag("roll").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("boxes filled", vm.boxes.values.any { it.isNotBlank() })
    }

    /** Surprise flavours: spicy/nsfw refuse without adult packs; nsfw pins adult-tier options only and runs hot; every kind can be undone. */
    @Test
    fun forgeSurpriseFlavoursKindsAndUndo() {
        try {
            rule.runOnUiThread { vm.onSurpriseFlavour("any"); vm.onForgeAdults(false) }
            tap("nav_forge", scroll = false)
            await("forge ready") { vm.forgeResult != null }
            rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("flavour_nsfw"))
            tap("flavour_nsfw", scroll = false)
            await("nsfw chosen") { vm.surpriseFlavour == "nsfw" }
            tap("surprise_dare", scroll = false)
            rule.waitForIdle()
            assertTrue("refused without adult packs", vm.forgeLocks.isEmpty() && !vm.surpriseCanUndo)

            rule.runOnUiThread { vm.onForgeAdults(true); vm.onForgeTier(4); vm.onForgeHeat(0.1f) }
            rule.onNodeWithTag("forge_list").performScrollToNode(hasTestTag("surprise_chaos"))
            tap("surprise_chaos", scroll = false)
            await("chaos pinned") { vm.forgeLocks.size >= 3 }
            for ((sid, texts) in vm.forgeLocks) {
                val idx = vm.forgeLib.slots[sid]!!.tags.associateBy { it.text }
                assertTrue("$sid adult-tier only: $texts", texts.all { (idx[it]?.tier ?: 0) >= 1 })
            }
            assertTrue("nsfw runs hot", vm.forgeHeat >= 0.8f)
            tap("surprise_undo", scroll = false)
            await("undone") { vm.forgeLocks.isEmpty() && !vm.surpriseCanUndo }
            assertEquals(0.1f, vm.forgeHeat, 0.001f)

            tap("flavour_any", scroll = false)
            await("any chosen") { vm.surpriseFlavour == "any" }
            tap("surprise_rare", scroll = false)
            await("rare pinned") { vm.forgeLocks.size in 1..2 }
            val rareSlots = vm.forgeLocks.keys
            tap("surprise_lucky", scroll = false)
            await("lucky locked more") { vm.forgeLocks.size > rareSlots.size }
            assertTrue("earlier locks kept", vm.forgeLocks.keys.containsAll(rareSlots))
            val seed = vm.forgeSeed
            tap("surprise_remix", scroll = false)
            rule.waitForIdle()
            assertEquals("remix keeps the seed", seed, vm.forgeSeed)
            val h = vm.forgeHeat
            tap("surprise_heat", scroll = false)
            await("heat up") { vm.forgeHeat > h || h >= 1f }
        } finally {
            rule.runOnUiThread { vm.onSurpriseFlavour("any") }
        }
    }
}
