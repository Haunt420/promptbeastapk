package app.promptbeast

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import promptbeast.AiTasks
import promptbeast.Boxes

private val TASK_NAMES = linkedMapOf("enhance" to "Enhance", "reshape" to "Reshape", "variations" to "Variations", "doctor" to "Doctor", "analyze" to "Analyze")

@Composable
fun AiScreen(vm: PbViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> if (uri != null) vm.aiPickImage(uri) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("AI Tools")
        ProviderCard(vm)
        TaskCard(vm, onPickImage = { picker.launch("image/*") })
        if (vm.aiKind == "manual") ManualCard(vm)
        ResultCard(vm)
    }
    if (vm.aiModelPickerOpen) ModelPicker(vm)
}

/** The endpoint's own model list: search (try ":free" on OpenRouter), tap one to use it. */
@Composable
private fun ModelPicker(vm: PbViewModel) {
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    val hasFree = remember(vm.aiModels) { vm.aiModels.any { it.endsWith(":free") } }
    val shown = remember(vm.aiModels, query, freeOnly) {
        val q = query.trim()
        vm.aiModels.filter { (!freeOnly || it.endsWith(":free")) && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
            .sortedBy { it.lowercase() }
    }
    Dialog(onDismissRequest = { vm.aiModelPickerOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 580.dp).testTag("model_picker"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Models", style = MaterialTheme.typography.titleMedium)
                Dim("${vm.aiModels.size} from the endpoint" + (if (shown.size != vm.aiModels.size) ", ${shown.size} shown" else "") + ". Tap one to use it.")
                Gap(8)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().testTag("model_search"))
                if (hasFree) {
                    Gap(4)
                    FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free only (:free)") }, modifier = Modifier.testTag("model_free"))
                }
                Gap(4)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shown) { id ->
                        Column(Modifier.fillMaxWidth().clickable { vm.aiPickModel(id) }.testTag("model_opt:$id")) {
                            Text(
                                (if (id == vm.aiModel) "\u2713 " else "") + id, style = MaterialTheme.typography.bodyMedium,
                                color = if (id == vm.aiModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                    if (shown.isEmpty()) item(key = "none") { Dim("Nothing matches.", Modifier.padding(vertical = 10.dp)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.aiModelPickerOpen = false }, modifier = Modifier.testTag("model_close")) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(vm: PbViewModel) {
    PbCard {
        Label("Provider")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.aiKind == "manual", onClick = { vm.onAiKind("manual") }, label = { Text("Manual: paste") },
                modifier = Modifier.testTag("ai_kind_manual"))
            FilterChip(selected = vm.aiKind == "openai", onClick = { vm.onAiKind("openai") }, label = { Text("OpenAI-compatible") },
                modifier = Modifier.testTag("ai_kind_openai"))
        }
        Gap(4)
        if (vm.aiKind == "manual") {
            Dim("No account or key needed. Run builds the instruction, you paste it into any chat or Perchance text generator, then paste the reply back.")
        } else {
            OutlinedTextField(value = vm.aiBaseUrl, onValueChange = { vm.onAiBaseUrl(it) }, label = { Text("Base URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("ai_base_url"))
            Gap(4)
            OutlinedTextField(value = vm.aiModel, onValueChange = { vm.onAiModel(it) }, label = { Text("Model") },
                placeholder = { Text("model id from your provider") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ai_model"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.aiLoadModels() }, enabled = !vm.aiBusy && !vm.aiModelsBusy, modifier = Modifier.testTag("ai_models")) {
                    Text("Pick from the endpoint's models")
                }
                if (vm.aiModelsBusy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            }
            Dim("Uses the key and base URL above. Free providers often list dozens; search narrows them.")
            Gap(4)
            OutlinedTextField(value = vm.aiKeyDraft, onValueChange = { vm.onAiKeyDraft(it) }, label = { Text("API key") },
                placeholder = { Text(if (vm.aiKeySaved) "saved (hidden)" else "paste your key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("ai_key"))
            if (vm.aiKeySaved) TextButton(onClick = { vm.aiForgetKey() }) { Text("Remove saved key") }
            Gap(4)
            Text("Temperature ${vm.aiTemp}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = vm.aiTemp.toFloat(), onValueChange = { vm.onAiTemp(it.toDouble()) }, valueRange = 0f..2f, steps = 19)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Model can see images", Modifier.weight(1f))
                Switch(checked = vm.aiVision, onCheckedChange = { vm.onAiVision(it) })
            }
            Gap(4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.aiSave() }, modifier = Modifier.testTag("ai_save")) { Text("Save") }
                OutlinedButton(onClick = { vm.aiTest() }, enabled = !vm.aiBusy, modifier = Modifier.testTag("ai_test")) { Text("Test connection") }
            }
            Gap(4)
            Dim("The key stays on this phone (kept out of backups). http:// is fine for your own server on the same network.")
        }
        if (vm.aiStatus.isNotEmpty()) { Gap(4); Dim(vm.aiStatus) }
    }
}

@Composable
private fun TaskCard(vm: PbViewModel, onPickImage: () -> Unit) {
    PbCard {
        Label("Task")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, name) in TASK_NAMES) {
                FilterChip(selected = vm.aiTask == id, onClick = { vm.onAiTask(id) }, label = { Text(name) }, modifier = Modifier.testTag("ai_task_$id"))
            }
        }
        Dim(AiTasks.TASKS[vm.aiTask] ?: "")
        if (vm.aiTask == "enhance" || vm.aiTask == "reshape" || vm.aiTask == "variations") {
            Gap(8)
            Dim("Box")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (spec in Boxes.ALL) {
                    FilterChip(selected = vm.aiBox == spec.id, onClick = { vm.onAiBox(spec.id) }, label = { Text(spec.label) }, modifier = Modifier.testTag("ai_box_${spec.id}"))
                }
                FilterChip(selected = vm.aiBox == Boxes.NEGATIVE_KEY, onClick = { vm.onAiBox(Boxes.NEGATIVE_KEY) }, label = { Text("Negative") },
                    modifier = Modifier.testTag("ai_box_negative"))
            }
        }
        if (vm.aiTask == "variations") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Takes", Modifier.padding(end = 12.dp))
                OutlinedButton(onClick = { vm.onAiTakes(vm.aiTakes - 1) }) { Text("-") }
                Text("${vm.aiTakes}", Modifier.padding(horizontal = 16.dp))
                OutlinedButton(onClick = { vm.onAiTakes(vm.aiTakes + 1) }) { Text("+") }
            }
        }
        if (vm.aiTask == "analyze") {
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickImage, modifier = Modifier.testTag("ai_image")) { Text("Choose image...") }
                Dim(vm.aiImageLabel.ifEmpty { "no image" }, Modifier.padding(start = 12.dp))
            }
        }
        Gap(8)
        OutlinedTextField(value = vm.aiExtra, onValueChange = { vm.onAiExtra(it) }, label = { Text("Direction (optional)") },
            placeholder = { Text("e.g. more rain, less neon") }, modifier = Modifier.fillMaxWidth().testTag("ai_extra"))
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.aiRun() }, enabled = !vm.aiBusy, modifier = Modifier.testTag("ai_run")) {
                Text(if (vm.aiKind == "manual") "Build instruction" else "Run")
            }
            if (vm.aiBusy) {
                OutlinedButton(onClick = { vm.aiStop() }, modifier = Modifier.padding(start = 8.dp).testTag("ai_stop")) { Text("Stop") }
                CircularProgressIndicator(Modifier.padding(start = 16.dp).size(22.dp), strokeWidth = 2.dp)
                Dim(vm.aiStatus, Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun ManualCard(vm: PbViewModel) {
    PbCard {
        Label("Manual round trip")
        Dim("1. Copy this instruction into a Perchance text generator (or any chat).")
        Gap(4)
        if (vm.aiInstruction.isEmpty()) {
            Dim("Press Build instruction.")
        } else {
            SelectionContainer {
                Text(vm.aiInstruction, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 12, modifier = Modifier.fillMaxWidth().testTag("ai_instruction"))
            }
            TextButton(onClick = { vm.clip(vm.aiInstruction, "Instruction") }, modifier = Modifier.testTag("ai_copy_instruction")) { Text("Copy instruction") }
        }
        Gap(8)
        Dim("2. Paste its reply here, then press Read reply.")
        Gap(4)
        OutlinedTextField(value = vm.aiPaste, onValueChange = { vm.onAiPaste(it) }, label = { Text("Reply") }, minLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().testTag("ai_paste"))
        Gap(4)
        Button(onClick = { vm.aiReadReply() }, modifier = Modifier.testTag("ai_read")) { Text("Read reply") }
    }
}

@Composable
private fun ResultCard(vm: PbViewModel) {
    val res = vm.aiResult
    PbCard {
        Label("Result")
        if (vm.aiProblem.isNotEmpty()) {
            Text("● " + vm.aiProblem, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai_problem"))
            Gap(4)
        }
        if (vm.aiBusy && vm.aiLive.isNotEmpty()) {
            Dim("Writing...")
            Text(vm.aiLive, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().testTag("ai_live"))
            Gap(4)
        }
        if (res == null) {
            if (!(vm.aiBusy && vm.aiLive.isNotEmpty())) Dim("Run a task to see the result here.")
            return@PbCard
        }
        if (res.texts.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                res.texts.forEachIndexed { i, t ->
                    val flat = t.trim().split(Regex("\\s+")).joinToString(" ")
                    FilterChip(selected = vm.aiVariant == i, onClick = { vm.aiPickVariant(i) },
                        label = { Text("#${i + 1}  " + if (flat.length > 28) flat.take(28) + "…" else flat, maxLines = 1) },
                        modifier = Modifier.testTag("ai_variant_$i"))
                }
            }
            Gap(4)
        }
        val sections = res.sections.isNotEmpty()
        OutlinedTextField(value = vm.aiOut, onValueChange = { vm.onAiOut(it) }, readOnly = sections, minLines = 5,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().testTag("ai_out"))
        Gap(4)
        for (n in vm.aiNotes) Dim("▲  $n")
        Gap(8)
        val boxLabel = if (vm.aiBox == Boxes.NEGATIVE_KEY) "Negative" else Boxes.ALL.firstOrNull { it.id == vm.aiBox }?.label ?: vm.aiBox
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.aiApply() }, modifier = Modifier.testTag("ai_apply")) { Text(if (sections) "Apply all boxes" else "Apply to $boxLabel") }
            OutlinedButton(onClick = { vm.clip(vm.aiOut, "Result") }, modifier = Modifier.testTag("ai_copy")) { Text("Copy") }
        }
    }
}
