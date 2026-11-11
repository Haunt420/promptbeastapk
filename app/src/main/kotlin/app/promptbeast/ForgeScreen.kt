package app.promptbeast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import promptbeast.Boxes
import promptbeast.Forge
import promptbeast.Slot
import promptbeast.SlotResult

private val MODES = listOf("replace" to "Replace boxes", "append" to "Append to boxes", "fill_empty" to "Fill empty only")

@Composable
fun ForgeScreen(vm: PbViewModel, onSent: () -> Unit) {
    var askAdult by remember { mutableStateOf(false) }
    val lib = vm.forgeLib
    val res = vm.forgeResult
    val slots = remember(lib) { lib.ordered() }
    val boxLabel = remember { Boxes.ALL.associate { it.id to it.label } }
    // slots grouped under the Compose box they feed (in the box order), each group in roll order; strays land last
    val groups = remember(lib) {
        val byBox = slots.groupBy { it.box }
        val known = Boxes.ALL.map { it.id }
        (known + byBox.keys.filter { it !in known }).mapNotNull { id -> byBox[id]?.let { id to it } }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("forge_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                Column {
                    Heading("Forge")
                    Dim("Roll a coherent character from tag packs. Lock what you like, re-roll the rest, then send it to Compose.")
                }
            }
            item(key = "controls") {
                PbCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = vm.forgeSeedText, onValueChange = { vm.onForgeSeedText(it) },
                            label = { Text("Seed") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { vm.forgeRerollAll() }) { Text("New seed") }
                    }
                    Gap(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Adult packs (18+)")
                            Dim("Everyone depicted is an adult. Minor-coded wording is dropped automatically.")
                        }
                        Switch(
                            checked = vm.forgeAdults,
                            onCheckedChange = { on -> if (on && !vm.adultsAcked) askAdult = true else vm.onForgeAdults(on) },
                        )
                    }
                    if (vm.forgeAdults && lib.maxTier() > 0) {
                        Gap(8)
                        Label("How far")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in 0..lib.maxTier()) {
                                FilterChip(selected = vm.forgeTier == t, onClick = { vm.onForgeTier(t) }, label = { Text(Forge.tierLabel(t)) })
                            }
                        }
                        Label("Heat  ${"%.2f".format(vm.forgeHeat)}")
                        Slider(value = vm.forgeHeat, onValueChange = { vm.onForgeHeat(it) }, valueRange = 0f..1f)
                        Dim("Higher heat makes the adult-tier tags more likely.")
                    }
                    Gap(4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Strict coherence")
                            Dim("Off: allow picks that break a rule (they are noted below).")
                        }
                        Switch(checked = vm.forgeStrict, onCheckedChange = { vm.onForgeStrict(it) })
                    }
                }
            }
            item(key = "packs") { PacksCard(vm) }
            for ((boxId, group) in groups) {
                item(key = "box:$boxId") {
                    Column {
                        Label(boxLabel[boxId] ?: boxId)
                        Dim("${group.size} slot" + (if (group.size == 1) "" else "s") + " feeding this box")
                    }
                }
                items(group, key = { "slot:" + it.id }) { slot ->      // a user pack may name a slot "notes" or "head"
                    SlotRow(vm, slot, res?.picks?.get(slot.id), boxLabel[slot.box] ?: slot.box)
                }
            }
            item(key = "notes") {
                PbCard {
                    Label("Notes")
                    val notes = (res?.notes ?: emptyList<String>()) + lib.issues.map { "pack: $it" }
                    if (notes.isEmpty()) {
                        Dim("No notes. Everything fits together.")
                    } else {
                        for (n in notes) {
                            Text("▲ $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((mode, label) in MODES) {
                        FilterChip(selected = vm.forgeMode == mode, onClick = { vm.onForgeMode(mode) }, label = { Text(label) })
                    }
                }
                Button(onClick = { if (vm.forgeSend()) onSent() }, modifier = Modifier.fillMaxWidth().testTag("forge_send")) { Text("Send to Compose") }
            }
        }
    }

    if (askAdult) {
        AlertDialog(
            onDismissRequest = { askAdult = false },
            title = { Text("Adults only") },
            text = {
                Text("The adult packs are for people 18 and over and describe adult characters only. Everything they generate depicts adults.")
            },
            confirmButton = { TextButton(onClick = { vm.ackAdults(); askAdult = false }) { Text("I'm 18 or older") } },
            dismissButton = { TextButton(onClick = { askAdult = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotRow(vm: PbViewModel, slot: Slot, pick: SlotResult?, boxName: String) {
    val off = slot.id in vm.forgeOff
    val locked = slot.id in vm.forgeLocks
    var open by remember { mutableStateOf(false) }
    val adultOnly = remember(slot) { slot.tags.isNotEmpty() && slot.tags.all { it.tier > 0 } }
    PbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.titleSmall)
                Dim("-> $boxName" + (if (adultOnly) "  \u00B7  18+" else ""))
            }
            Switch(checked = !off, onCheckedChange = { vm.forgeToggleOff(slot.id) })
        }
        Gap(4)
        val text = when {
            off -> "off"
            pick == null || pick.texts.isEmpty() -> "-"
            else -> pick.texts.joinToString(slot.sep)
        }
        OutlinedButton(
            onClick = { open = true }, enabled = !off, modifier = Modifier.fillMaxWidth().testTag("slot_pick_" + slot.id),
        ) {
            Text(text, Modifier.weight(1f), textAlign = TextAlign.Start, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("  \u25BE")
        }
        if (!off) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = locked, onClick = { vm.forgeToggleLock(slot.id) }, label = { Text(if (locked) "Locked" else "Lock") })
                TextButton(onClick = { vm.forgeRerollSlot(slot.id) }) { Text("Reroll") }
            }
        }
    }
    if (open) SlotPicker(vm, slot, pick?.texts ?: emptyList()) { open = false }
}


/** Your own .pbpack files: import from the file picker, see what loaded, delete. */
@Composable
private fun PacksCard(vm: PbViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.packImport(uri) }
    PbCard {
        Label("Your packs")
        Dim("Add .pbpack files (one tag per line). Same format as the desktop app; Copy template shows it.")
        Gap(4)
        for (p in vm.userPacks) {
            Row(Modifier.fillMaxWidth().testTag("pack_row_" + p.stem), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, maxLines = 1)
                    Dim("${p.slots} slots, ${p.tags} tags" + (if (p.adults) ", 18+ (hidden unless Adult packs is on)" else "") +
                        (if (p.notes > 0) ", ${p.notes} notes" else ""))
                }
                TextButton(onClick = { vm.packDelete(p.stem) }, modifier = Modifier.testTag("pack_delete_" + p.stem)) { Text("Delete") }
            }
        }
        if (vm.userPacks.isEmpty()) Dim("None yet.")
        Gap(4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("pack_import")) { Text("Import pack") }
            OutlinedButton(onClick = { vm.packCopyTemplate() }, modifier = Modifier.testTag("pack_template")) { Text("Copy template") }
        }
    }
}
