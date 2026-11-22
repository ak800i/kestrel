package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.memory.Routine
import dev.kestrel.app.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * What Kestrel's memory holds, for the Memory page (design D22, D23 and D26 of sub-project 5): the facts and aliases, the
 * route notes by app, the routines, the counts of finished tasks by `finish` signature, the contacts that recent tasks
 * found, and the launcher labels of the notes' apps. toString prints only counts (D25).
 */
data class MemoryView(
    val facts: List<Fact> = emptyList(),
    val notes: Map<String, List<RouteNote>> = emptyMap(),
    val routines: List<Routine> = emptyList(),
    val signatures: Map<String, Int> = emptyMap(),
    val foundContacts: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "MemoryView(facts=${facts.size}, notes=${notes.values.sumOf { it.size }}, routines=${routines.size}, " +
            "signatures=${signatures.size}, foundContacts=${foundContacts.size})"
}

/**
 * The Memory page (design D26 of sub-project 5; spec 11.9 and 15): the facts and aliases (add, edit, delete), the route
 * notes per app with their version (edit in their path form, delete, "Delete all notes of this app"; D22, review C M6),
 * the routines (rename, edit the phrasings, delete), the finished tasks' signatures and the contacts that recent tasks
 * found, and "Delete all memory" with a confirmation. The page reads memory again after each change.
 */
@Composable
internal fun MemoryPage(data: SettingsData, onBack: () -> Unit) {
    var memory by remember { mutableStateOf<MemoryView?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reads) { memory = data.memory() }
    // At once, so that the change is queued also when the owner leaves the page right after it; then the page reads again.
    val change: (suspend () -> Unit) -> Unit = { block -> scope.launch(start = CoroutineStart.UNDISPATCHED) { block(); reads++ } }
    PageHeader(stringResource(R.string.settings_memory), onBack)
    val current = memory ?: run {
        Text(stringResource(R.string.memory_loading), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Heading(R.string.memory_facts)
    if (current.facts.isEmpty()) Text(stringResource(R.string.memory_none), style = MaterialTheme.typography.bodyMedium)
    for (fact in current.facts) {
        FactCard(fact, onSave = { edited -> change { data.saveFact(edited) } }, onDelete = { change { data.deleteFact(fact.key) } })
    }
    NewFact { fact -> change { data.saveFact(fact) } }
    Heading(R.string.memory_notes)
    if (current.notes.values.all { it.isEmpty() }) Text(stringResource(R.string.memory_none), style = MaterialTheme.typography.bodyMedium)
    for ((packageName, notes) in current.notes.filterValues { it.isNotEmpty() }) {
        Text(current.labels[packageName] ?: packageName, style = MaterialTheme.typography.titleSmall)
        for (note in notes) {
            NoteCard(note, onSave = { edited -> change { data.replaceNote(note, edited) } }, onDelete = { change { data.deleteNote(note) } })
        }
        TextButton(onClick = { change { data.deleteNotes(packageName) } }) { Text(stringResource(R.string.memory_delete_app_notes)) }
    }
    Heading(R.string.memory_routines)
    if (current.routines.isEmpty()) Text(stringResource(R.string.memory_none), style = MaterialTheme.typography.bodyMedium)
    for (routine in current.routines) {
        RoutineCard(routine, onSave = { edited -> change { data.replaceRoutine(routine.name, edited) } }, onDelete = { change { data.deleteRoutine(routine.name) } })
    }
    Heading(R.string.memory_signatures)
    if (current.signatures.isEmpty()) Text(stringResource(R.string.memory_none), style = MaterialTheme.typography.bodyMedium)
    for ((signature, count) in current.signatures) {
        Text(pluralStringResource(R.plurals.memory_signature_runs, count, signature, count), style = MaterialTheme.typography.bodyMedium)
    }
    Heading(R.string.memory_found_contacts)
    if (current.foundContacts.isEmpty()) Text(stringResource(R.string.memory_none), style = MaterialTheme.typography.bodyMedium)
    for (name in current.foundContacts) Text(name, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = { deleting = true }, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.memory_delete_all)) }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            text = { Text(stringResource(R.string.memory_delete_all_question)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    change { data.deleteAllMemory() }
                }) { Text(stringResource(R.string.memory_delete_all_confirm)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.memory_cancel)) } },
        )
    }
}

@Composable
private fun Heading(id: Int) {
    Text(stringResource(id), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun FactCard(fact: Fact, onSave: (Fact) -> Unit, onDelete: () -> Unit) {
    var value by rememberSaveable(fact.key) { mutableStateOf(fact.value) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(fact.key, style = MaterialTheme.typography.titleSmall)
            if (fact.kind == FactKind.ALIAS) Text(stringResource(R.string.memory_alias), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.memory_value)) })
            Row {
                TextButton(onClick = { onSave(fact.copy(value = value.trim())) }, enabled = value.isNotBlank()) {
                    Text(stringResource(R.string.memory_save))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.memory_delete)) }
            }
        }
    }
}

@Composable
private fun NewFact(onAdd: (Fact) -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.memory_key)) })
    OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.memory_value)) })
    Row(
        Modifier.fillMaxWidth().toggleable(value = alias, role = Role.Checkbox) { alias = it }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = alias, onCheckedChange = null)
        Text(stringResource(R.string.memory_alias_check), Modifier.padding(start = 8.dp))
    }
    Button(onClick = {
        onAdd(Fact(key.trim(), value.trim(), if (alias) FactKind.ALIAS else FactKind.FACT, System.currentTimeMillis()))
        key = ""
        value = ""
        alias = false
    }, enabled = key.isNotBlank() && value.isNotBlank()) { Text(stringResource(R.string.memory_add)) }
}

@Composable
private fun NoteCard(note: RouteNote, onSave: (RouteNote) -> Unit, onDelete: () -> Unit) {
    var path by rememberSaveable(note.packageName, note.path) { mutableStateOf(note.path) }
    // A note keeps its path form: only fixed verbs and quoted labels (design D22, review C M6).
    val steps = RouteNote.parse(path)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            val about = listOfNotNull(
                note.version?.let { stringResource(R.string.memory_note_version, it) },
                stringResource(R.string.memory_note_new).takeUnless { note.active },
            )
            if (about.isNotEmpty()) Text(about.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), isError = steps == null)
            if (steps == null) Text(stringResource(R.string.memory_note_bad), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { steps?.let { onSave(note.copy(path = RouteNote.format(it))) } }, enabled = steps != null) {
                    Text(stringResource(R.string.memory_save))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.memory_delete)) }
            }
        }
    }
}

@Composable
private fun RoutineCard(routine: Routine, onSave: (Routine) -> Unit, onDelete: () -> Unit) {
    var name by rememberSaveable(routine.name) { mutableStateOf(routine.name) }
    var phrasings by rememberSaveable(routine.name) { mutableStateOf(routine.phrasings.joinToString("\n")) }
    val lines = phrasings.lines().map { it.trim() }.filter { it.isNotEmpty() }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.memory_routine_name)) })
            OutlinedTextField(phrasings, { phrasings = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.memory_routine_phrasings)) })
            Row {
                TextButton(
                    onClick = { onSave(routine.copy(name = name.trim(), phrasings = lines)) },
                    enabled = name.isNotBlank() && lines.isNotEmpty(),
                ) { Text(stringResource(R.string.memory_save)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.memory_delete)) }
            }
        }
    }
}
