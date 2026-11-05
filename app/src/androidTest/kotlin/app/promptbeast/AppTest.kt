package app.promptbeast

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        rule.waitUntil(300_000) { !vm.selfTestRunning && vm.selfTest.size == 11 }
        shot("5_selftest")
        val bad = vm.selfTest.filter { it.startsWith("FAIL") }
        val assets = listOf("", "golden", "packs").joinToString(" | ") { d -> "assets/$d: " + rule.activity.assets.list(d)?.joinToString(",") }
        // one line: Gradle's console only shows the first lines of an assertion message
        assertTrue("self-test: " + vm.selfTest.joinToString(" | ").replace("\n", " ") + " || " + assets, bad.isEmpty())
    }
}
