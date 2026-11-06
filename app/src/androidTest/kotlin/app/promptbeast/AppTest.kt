package app.promptbeast

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
        rule.waitUntil(300_000) { !vm.selfTestRunning && vm.selfTest.size == 12 }
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
}
