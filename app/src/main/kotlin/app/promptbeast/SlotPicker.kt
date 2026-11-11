package app.promptbeast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import promptbeast.Forge
import promptbeast.PackParser
import promptbeast.Slot

/**
 * The drop-down behind every Forge slot: every tag the slot has (up to the adult tier in play), a search box that also
 * takes your own wording, and "Random" to hand the slot back to the dice. Picking pins (locks) the slot.
 * Slots that roll several tags (count > 1) toggle and apply with Done.
 */
@Composable
fun SlotPicker(vm: PbViewModel, slot: Slot, current: List<String>, onDismiss: () -> Unit) {
    val limit = vm.forgeLimitTier
    val tags = remember(slot, limit) { slot.tags.filter { it.tier <= limit }.sortedBy { it.text.lowercase() } }
    val multi = slot.hi > 1
    var query by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(current.distinct()) }
    val q = query.trim()
    val shown = remember(tags, q) { if (q.isEmpty()) tags else tags.filter { it.text.contains(q, ignoreCase = true) } }
    val custom = q.isNotEmpty() && tags.none { it.text.equals(q, ignoreCase = true) }
    val blocked = custom && PackParser.ageGuardHit(q)

    fun choose(text: String) {
        if (multi) {
            sel = if (text in sel) sel - text else (sel + text).takeLast(slot.hi)
        } else if (vm.forgeSetLock(slot.id, listOf(text))) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 580.dp).testTag("slot_picker"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(slot.label, style = MaterialTheme.typography.titleMedium)
                Dim("${tags.size} options" + (if (multi) ", up to ${slot.hi} at once" else "") + ". Picking one locks the slot.")
                Gap(8)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search, or type your own") },
                    modifier = Modifier.fillMaxWidth().testTag("pick_search"),
                )
                Gap(4)
                LazyColumn(Modifier.weight(1f, fill = false).testTag("pick_list")) {
                    item(key = "pick:random") {
                        PickRow("Random: unlock and reroll", null, false, Modifier.testTag("pick_random")) {
                            vm.forgeRerollSlot(slot.id); onDismiss()
                        }
                    }
                    if (custom) {
                        item(key = "pick:custom") {
                            if (blocked) {
                                Dim("Minor-coded wording isn't allowed.", Modifier.padding(vertical = 10.dp))
                            } else {
                                PickRow("Use \"$q\"", "your own text", q in sel, Modifier.testTag("pick_custom")) { choose(q) }
                            }
                        }
                    }
                    items(shown) { t ->
                        PickRow(t.text, if (t.tier > 0) Forge.tierLabel(t.tier) else null, t.text in sel, Modifier.testTag("opt:" + t.text)) { choose(t.text) }
                    }
                    if (shown.isEmpty() && !custom) item(key = "pick:none") { Dim("Nothing matches.", Modifier.padding(vertical = 10.dp)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("pick_cancel")) { Text(if (multi) "Cancel" else "Close") }
                    if (multi) {
                        Button(
                            onClick = {
                                if (sel.isEmpty()) vm.forgeRerollSlot(slot.id) else vm.forgeSetLock(slot.id, sel)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("pick_done"),
                        ) { Text(if (sel.isEmpty()) "Random" else "Done (${sel.size})") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(text: String, note: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (selected) "✓ " else "") + text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (note != null) Dim(note, Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
    }
}
