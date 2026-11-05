package app.promptbeast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import promptbeast.Assembled
import promptbeast.Boxes
import promptbeast.Profiles

private val SIMPLE_BOXES = setOf("main", "style", "subject", "scene")

private val PLACEHOLDERS = mapOf(
    "main" to "Describe the whole image in full sentences. {a|b} picks one per roll; [NAME] uses a variable from Variables.",
    "style" to "Medium, lens, grade, film stock, art style.",
    "aesthetic" to "Overall look and era: moodboard words, palette, decade.",
    "subject" to "Who or what: appearance, age, build, face, hair. [NAME] works here.",
    "pose" to "Pose, action and expression with concrete body geometry.",
    "fashion" to "Clothing, fabrics, accessories, makeup.",
    "scene" to "Location, weather, time of day, lighting, props.",
    "misc" to "Anything else that should ride along.",
)

@Composable
fun ComposeScreen(vm: PbViewModel) {
    var sub by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Edit") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text(if (vm.rolls.isEmpty()) "Rolls" else "Rolls (${vm.rolls.size})") })
        }
        if (sub == 0) EditPane(vm, onRolled = { sub = 1 }) else RollsPane(vm)
    }
}

@Composable
private fun EditPane(vm: PbViewModel, onRolled: () -> Unit) {
    val specs = Boxes.ALL.filter { vm.advancedBoxes || it.id in SIMPLE_BOXES || (vm.boxes[it.id] ?: "").isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = vm.name, onValueChange = { vm.onName(it) }, label = { Text("Project name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "profile") {
                Column {
                    Label("Model")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, prof) in Profiles.ALL) {
                            FilterChip(selected = vm.profile == key, onClick = { vm.onProfile(key) }, label = { Text(prof.name) })
                        }
                    }
                    Dim(Profiles.get(vm.profile).note)
                }
            }
            items(specs, key = { it.id }) { spec ->
                OutlinedTextField(
                    value = vm.boxes[spec.id] ?: "", onValueChange = { vm.onBox(spec.id, it) },
                    label = { Text(spec.label) }, placeholder = { Text(PLACEHOLDERS[spec.id] ?: "") },
                    minLines = if (spec.id == "main") 4 else 2, modifier = Modifier.fillMaxWidth().testTag("box_${spec.id}"),
                )
            }
            item(key = "more") {
                TextButton(onClick = { vm.onAdvanced(!vm.advancedBoxes) }) { Text(if (vm.advancedBoxes) "Fewer boxes" else "More boxes") }
            }
            item(key = "negative") {
                OutlinedTextField(
                    value = vm.negative, onValueChange = { vm.onNegative(it) }, label = { Text("Negative") },
                    placeholder = { Text("Things to keep out. Weak on prose models: prefer describing what you want.") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "scratchpad") {
                OutlinedTextField(
                    value = vm.scratchpad, onValueChange = { vm.onScratchpad(it) }, label = { Text("Variables & notes") },
                    placeholder = {
                        Text("WHO = {a young sailor|an old poet}\nCOAT = a {emerald|oxblood} velvet coat\n\nOne NAME = value per line (CAPS, digits, _). Use [NAME] in any box. Other lines are notes.")
                    },
                    minLines = 4, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "opts") {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sticky variables")
                            Dim("Each [NAME] resolves once per roll, so the same pick shows up in every box.")
                        }
                        Switch(checked = vm.sticky, onCheckedChange = { vm.onSticky(it) })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fix a/an")
                            Dim("Correct a/an after random rolls (a emerald -> an emerald).")
                        }
                        Switch(checked = vm.fixArticles, onCheckedChange = { vm.onFixArticles(it) })
                    }
                }
            }
            item(key = "lint") { LintSection(vm) }
            item(key = "project") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.copyMackNow() }) { Text("Copy Mack key") }
                    OutlinedButton(onClick = { vm.newProject() }) { Text("New project") }
                }
            }
        }
        RollBar(vm, onRolled)
    }
}

@Composable
private fun LintSection(vm: PbViewModel) {
    Column {
        Label("Checks")
        if (vm.lintItems.isEmpty()) {
            Dim("Nothing to flag.")
        } else {
            for ((where, issue) in vm.lintItems) {
                Text(
                    "${severityGlyph(issue.severity)} $where: ${issue.message}",
                    style = MaterialTheme.typography.bodySmall, color = severityColor(issue.severity),
                )
            }
        }
    }
}

@Composable
private fun RollBar(vm: PbViewModel, onRolled: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = vm.seedText, onValueChange = { vm.seedText = it.filter { c -> c.isDigit() }.take(18) },
                label = { Text("Seed (blank = random)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { vm.cycleRollCount() }) { Text("x${vm.rollCount}") }
            Button(onClick = { if (vm.roll()) onRolled() }, modifier = Modifier.testTag("roll")) { Text("Roll") }
        }
    }
}

@Composable
private fun RollsPane(vm: PbViewModel) {
    if (vm.rolls.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Nothing rolled yet. Write something on Edit and hit Roll.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(vm.rolls) { i, a -> RollCard(vm, i, a) }
    }
}

@Composable
private fun RollCard(vm: PbViewModel, index: Int, a: Assembled) {
    val ctx = LocalContext.current
    PbCard {
        Dim("Roll ${index + 1}" + (a.seed?.let { " · seed $it" } ?: ""))
        Gap(4)
        SelectionContainer {
            Text(a.prompt.ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodyLarge)
        }
        if (a.negative.isNotEmpty()) {
            Gap(8)
            Label("Negative")
            SelectionContainer {
                Text(a.negative, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (a.unresolved.isNotEmpty()) {
            Gap(6)
            Text(
                "Undefined: " + a.unresolved.joinToString(", ") { "[$it]" } + " (stay literal)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
        for (err in a.errors) {
            Text("▲ $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (a.weightsConverted > 0) {
            Dim("${a.weightsConverted} weight(s) turned into plain words for this model.")
        }
        Gap(4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.copyPrompt(a) }) { Text("Copy") }
            if (a.negative.isNotEmpty()) TextButton(onClick = { vm.copyNegative(a) }) { Text("Copy negative") }
            TextButton(onClick = { vm.copyMack(a) }) { Text("Mack key") }
            TextButton(onClick = { shareText(ctx, a.prompt) }) { Text("Share") }
            TextButton(onClick = { vm.keep(a) }) { Text("Keep") }
        }
    }
}
