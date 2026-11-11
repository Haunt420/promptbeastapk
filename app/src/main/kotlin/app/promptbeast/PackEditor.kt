package app.promptbeast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import promptbeast.UserPacks
import promptbeast.parsePack

/**
 * Write or edit a .pbpack without leaving the app. The text is parsed with the real parser on every change, so the counts and
 * the notes (with line numbers, age guard included) are exactly what the Forge will make of it.
 */
@Composable
fun PackEditorDialog(vm: PbViewModel) {
    val text = vm.packEditText
    val parsed = remember(text) { parsePack(text, "draft") }
    val tags = parsed.slots.values.sumOf { it.tags.size }
    Dialog(onDismissRequest = { vm.packEditClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.fillMaxSize().padding(8.dp).testTag("pack_editor")) {
            Column(Modifier.padding(16.dp)) {
                Text(if (vm.packEditStem == null) "New pack" else "Edit pack", style = MaterialTheme.typography.titleMedium)
                Gap(8)
                if (vm.packEditStem == null) {
                    OutlinedTextField(value = vm.packEditName, onValueChange = { vm.onPackEditName(it) }, label = { Text("File name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pack_edit_name"))
                } else {
                    Dim("Editing ${vm.packEditStem}${UserPacks.EXT}")
                }
                Gap(4)
                OutlinedTextField(
                    value = text, onValueChange = { vm.onPackEditText(it) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("pack_edit_text"),
                )
                Gap(4)
                Dim(
                    "${parsed.slots.size} slot${if (parsed.slots.size != 1) "s" else ""}, $tags tag${if (tags != 1) "s" else ""}" +
                        (if (parsed.adults) ", 18+ pack (hidden unless Adult packs is on)" else "") +
                        (if (parsed.issues.isNotEmpty()) ", ${parsed.issues.size} note${if (parsed.issues.size != 1) "s" else ""}" else ""),
                    Modifier.testTag("pack_edit_status"),
                )
                for (n in parsed.issues.take(4)) Text("▲ $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                if (parsed.issues.size > 4) Dim("…and ${parsed.issues.size - 4} more")
                if (vm.packEditProblem.isNotEmpty()) {
                    Text("● " + vm.packEditProblem, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pack_edit_problem"))
                }
                Gap(8)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.packEditInsertSlot() }, modifier = Modifier.testTag("pack_edit_slot")) { Text("Insert slot") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.packEditClose() }, modifier = Modifier.testTag("pack_edit_cancel")) { Text("Cancel") }
                    Button(onClick = { vm.packEditSave() }, modifier = Modifier.testTag("pack_edit_save")) { Text("Save") }
                }
            }
        }
    }
}
