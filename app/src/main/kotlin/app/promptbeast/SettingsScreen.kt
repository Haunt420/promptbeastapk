package app.promptbeast

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column

@Composable
fun SettingsScreen(vm: PbViewModel) {
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) vm.exportTo(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importFrom(uri)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "head") { Heading("Settings") }

        item(key = "appearance") {
            PbCard {
                Label("Appearance")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((mode, label) in listOf(0 to "System", 1 to "Dark", 2 to "Light")) {
                        FilterChip(selected = vm.themeMode == mode, onClick = { vm.onThemeMode(mode) }, label = { Text(label) })
                    }
                }
                Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show all eight boxes")
                        Dim("Off: Main, Style, Subject and Scene. Boxes with text always show.")
                    }
                    Switch(checked = vm.advancedBoxes, onCheckedChange = { vm.onAdvanced(it) })
                }
            }
        }

        item(key = "backup") {
            PbCard {
                Label("Library backup")
                Dim("Same JSON the desktop app reads and writes, so libraries move both ways.")
                Gap(8)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exporter.launch("promptbeast-library.json") }) { Text("Export") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
                }
            }
        }

        item(key = "selftest") {
            PbCard {
                Label("Parity self-test")
                Dim(
                    "Re-runs the Python reference's golden vectors against this device: RNG, text rules, DSL, Forge, Library. " +
                        "Charclass can differ on old Android versions (older Unicode tables).",
                )
                Gap(8)
                Button(onClick = { vm.runSelfTest() }, enabled = !vm.selfTestRunning, modifier = Modifier.testTag("selftest_run")) {
                    Text(if (vm.selfTestRunning) "Running..." else "Run self-test")
                }
                if (vm.selfTest.isNotEmpty()) {
                    Gap(8)
                    SelectionContainer {
                        Text(
                            vm.selfTest.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        )
                    }
                }
            }
        }

        item(key = "about") {
            PbCard {
                Label("About")
                Text("PromptBeast ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Gap(4)
                Text(
                    "A prompt workbench built around Perchance generators. Thanks to Mack (q1nerous1) for Mack's McGenerator, " +
                        "the paragon this app is modeled on. All tag lists are re-authored. Adult packs are 18+ and every " +
                        "character they produce is an adult.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Gap(4)
                Dim("The core runs the same seeded engine as the desktop app: same seed, same prompt.")
            }
        }
    }
}
