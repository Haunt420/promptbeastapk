package app.promptbeast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
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
            items(slots, key = { it.id }) { slot ->
                SlotRow(vm, slot, res?.picks?.get(slot.id), boxLabel[slot.box] ?: slot.box)
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
                Button(onClick = { if (vm.forgeSend()) onSent() }, modifier = Modifier.fillMaxWidth()) { Text("Send to Compose") }
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
    PbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.titleSmall)
                Dim("-> $boxName")
            }
            Switch(checked = !off, onCheckedChange = { vm.forgeToggleOff(slot.id) })
        }
        Gap(4)
        val text = when {
            off -> "off"
            pick == null || pick.texts.isEmpty() -> "-"
            else -> pick.texts.joinToString(slot.sep)
        }
        Text(
            text, style = MaterialTheme.typography.bodyLarge,
            color = if (off || pick == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (!off) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = locked, onClick = { vm.forgeToggleLock(slot.id) }, label = { Text(if (locked) "Locked" else "Lock") })
                TextButton(onClick = { vm.forgeRerollSlot(slot.id) }) { Text("Reroll") }
            }
        }
    }
}
