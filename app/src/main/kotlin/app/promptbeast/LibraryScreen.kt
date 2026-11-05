package app.promptbeast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import promptbeast.Entry
import promptbeast.Export

private val VIEWS = listOf("all" to "All", "fav" to "Favourites", "saved" to "Kept", "history" to "History", "archive" to "Archive")

private val EMPTY = mapOf(
    "all" to "Nothing here yet. Keep a roll from Compose, or copy a prompt: copies land in History.",
    "fav" to "No favourites yet. Star an entry to find it fast.",
    "saved" to "Nothing kept yet. Use Keep on a roll.",
    "history" to "History fills up as you copy prompts and Mack keys.",
    "archive" to "The archive is empty. Archived entries wait here until you delete them for good.",
)

@Composable
fun LibraryScreen(vm: PbViewModel, onLoad: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(-1L) }
    BackHandler(enabled = selected >= 0L) { selected = -1L }
    if (selected >= 0L) {
        EntryDetail(vm, selected, onBack = { selected = -1L }, onLoad = onLoad)
    } else {
        EntryList(vm, onOpen = { selected = it })
    }
}

@Composable
private fun EntryList(vm: PbViewModel, onOpen: (Long) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf("all") }
    var tag by rememberSaveable { mutableStateOf("") }
    var confirmPurge by remember { mutableStateOf(false) }

    val entries = remember(vm.libVersion, q, view, tag) { vm.libSearch(q, view, tag) }
    val tags = remember(vm.libVersion) { vm.libTags() }
    val total = remember(vm.libVersion, view) { vm.libCount(view) }
    val filtering = q.isNotBlank() || tag.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, label = { Text("Search prompts, notes, tags") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, label) in VIEWS) {
                    FilterChip(selected = view == key, onClick = { view = key }, label = { Text(label) })
                }
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tag.isEmpty(), onClick = { tag = "" }, label = { Text("All tags") })
                    for ((t, n) in tags) {
                        FilterChip(selected = tag == t, onClick = { tag = if (tag == t) "" else t }, label = { Text("#$t $n") })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dim(
                    if (filtering) "${entries.size} shown · $total total" else "${entries.size} entries",
                    Modifier.weight(1f),
                )
                if (view == "archive" && total > 0) TextButton(onClick = { confirmPurge = true }) { Text("Delete all") }
            }
            if (vm.libraryInMemory) Dim("Library is in memory only this session (the database could not be opened).")
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filtering) "No matches." else (EMPTY[view] ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { e -> EntryCard(e) { onOpen(e.id) } }
            }
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Delete the whole archive?") },
            text = { Text("$total archived entries will be removed for good. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.libPurgeArchived(); confirmPurge = false }) { Text("Delete forever") } },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EntryCard(e: Entry, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                (if (e.fav) "★ " else "") + e.label,
                style = MaterialTheme.typography.titleSmall, maxLines = 2,
            )
            val sub = listOf(
                whenText(e.created), if (e.kind == "history") "history" else "kept",
                e.seed?.let { "seed $it" } ?: "", e.tags.joinToString(" ") { "#$it" },
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            Dim(sub)
            if (e.prompt.isNotEmpty()) {
                Text(
                    e.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EntryDetail(vm: PbViewModel, id: Long, onBack: () -> Unit, onLoad: () -> Unit) {
    val e = remember(vm.libVersion, id) { vm.libGet(id) }
    if (e == null) {
        LaunchedEffect(id) { onBack() }
    } else {
        DetailBody(vm, e, onBack, onLoad)
    }
}

@Composable
private fun DetailBody(vm: PbViewModel, e: Entry, onBack: () -> Unit, onLoad: () -> Unit) {
    var title by remember(e.id) { mutableStateOf(e.title) }
    var tags by remember(e.id) { mutableStateOf(e.tags.joinToString(", ")) }
    var note by remember(e.id) { mutableStateOf(e.note) }
    var confirmPurge by remember { mutableStateOf(false) }
    val archived = e.archived != null
    val dirty = title != e.title || tags != e.tags.joinToString(", ") || note != e.note

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (dirty && !archived) vm.libEdit(e.id, title, tags, note)
                    onBack()
                }) { Text("< Back") }
                Dim(
                    listOf(
                        whenText(e.created), if (e.kind == "saved") "kept" else "history", e.seed?.let { "seed $it" } ?: "",
                        e.profile, if (archived) "archived" else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    Modifier.weight(1f),
                )
            }
        }
        item(key = "fields") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Title") },
                    singleLine = true, enabled = !archived, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it }, label = { Text("Tags (comma separated)") },
                    singleLine = true, enabled = !archived, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text("Note") },
                    minLines = 2, enabled = !archived, modifier = Modifier.fillMaxWidth(),
                )
                if (dirty && !archived) {
                    Button(onClick = { vm.libEdit(e.id, title, tags, note) }) { Text("Save changes") }
                }
            }
        }
        item(key = "prompt") {
            PbCard {
                Label("Prompt")
                SelectionContainer { Text(e.prompt.ifEmpty { "(none stored)" }, style = MaterialTheme.typography.bodyLarge) }
                if (e.negative.isNotEmpty()) {
                    Gap(8)
                    Label("Negative")
                    SelectionContainer {
                        Text(e.negative, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!archived) {
                    OutlinedButton(onClick = { vm.libFav(e.id, !e.fav) }) { Text(if (e.fav) "★ Favourite" else "☆ Favourite") }
                    OutlinedButton(onClick = { vm.clip(e.prompt, "Prompt") }) { Text("Copy prompt") }
                    OutlinedButton(onClick = { vm.clip(vm.mackKeyText(e.projectObj()), "Mack key") }) { Text("Copy Mack key") }
                }
                Button(onClick = { vm.loadEntry(e); onLoad() }) { Text("Load into Compose") }
                if (archived) {
                    OutlinedButton(onClick = { vm.libRestore(e.id) }) { Text("Restore") }
                    OutlinedButton(onClick = { confirmPurge = true }) { Text("Delete forever") }
                } else {
                    OutlinedButton(onClick = { vm.libArchive(e.id) }) { Text("Archive") }
                }
            }
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Delete forever?") },
            text = { Text("This entry will be removed for good. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.libPurge(e.id); confirmPurge = false }) { Text("Delete forever") } },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
        )
    }
}
