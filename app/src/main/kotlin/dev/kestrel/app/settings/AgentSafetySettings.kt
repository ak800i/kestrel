package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.app.R
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private class ModeChoice(val mode: Mode, @StringRes val title: Int, @StringRes val detail: Int)

private val MODES = listOf(
    ModeChoice(Mode.STANDARD, R.string.safety_mode_standard, R.string.safety_mode_standard_detail),
    ModeChoice(Mode.RELAXED, R.string.safety_mode_relaxed, R.string.safety_mode_relaxed_detail),
    ModeChoice(Mode.FULL_AUTO, R.string.safety_mode_full_auto, R.string.safety_mode_full_auto_detail),
)

/**
 * The Safety page's parts for the agent (design D26 of sub-project 5): the mode with spec 12.3's sentence for each and the
 * security floor's note, test mode's names (the owner's e-mail addresses, his name in WhatsApp, the name of his Viber notes
 * chat; D18), and the agent-free apps with "Add an app", "Remove" and "Suggestions" (D16). Every choice is stored at once,
 * also when the owner leaves the page right after it.
 */
@Composable
internal fun AgentSafetySettings(data: SettingsData) {
    val safety by data.safety.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val current = safety ?: return
    Text(stringResource(R.string.safety_mode), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    for (choice in MODES) {
        // The whole row is one radio button, so that its title names it for Voice Access and TalkBack.
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = current.mode == choice.mode, role = Role.RadioButton) {
                    now(scope) { data.setSafety { it.copy(mode = choice.mode) } }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = current.mode == choice.mode, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(stringResource(choice.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(choice.detail), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text(stringResource(R.string.safety_floor), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.safety_test_names), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    NameField(R.string.safety_owner_emails, current.ownerEmails.joinToString(", ")) { text ->
        now(scope) { data.setSafety { it.copy(ownerEmails = ownerEmails(text)) } }
    }
    NameField(R.string.safety_whatsapp_name, current.whatsappOwnName.orEmpty()) { text ->
        now(scope) { data.setSafety { it.copy(whatsappOwnName = text.trim().ifEmpty { null }) } }
    }
    NameField(R.string.safety_viber_name, current.viberSelfChatName) { text ->
        now(scope) { data.setSafety { it.copy(viberSelfChatName = text.trim().ifEmpty { VIBER_DEFAULT }) } }
    }
    Text(stringResource(R.string.safety_viber_unchecked), style = MaterialTheme.typography.bodySmall)
    AgentFreeList(data)
}

private const val VIBER_DEFAULT = "My Notes"

// At once, so that the write is queued also when the owner leaves the page right after the change.
private fun now(scope: CoroutineScope, change: suspend () -> Unit) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) { change() }
}

/** A text field that starts with the stored [value] and stores each change, without the stored value moving the cursor. */
@Composable
private fun NameField(@StringRes label: Int, value: String, onChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun AgentFreeList(data: SettingsData) {
    val changes by data.agentFree.collectAsState(initial = null)
    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    LaunchedEffect(Unit) { apps = data.launcherApps() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var suggesting by rememberSaveable { mutableStateOf(false) }
    val ticked = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val current = changes ?: return
    val labels = apps.associate { it.packageName to it.label }
    Text(stringResource(R.string.safety_agent_free), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.safety_agent_free_detail), style = MaterialTheme.typography.bodySmall)
    // By the app's label, or by its package when it is not installed (design D16).
    for (packageName in AgentFreeEdits.listed(current).sortedBy { (labels[it] ?: it).lowercase() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(labels[packageName] ?: packageName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { now(scope) { data.setAgentFree { AgentFreeEdits.remove(it, packageName) } } }) {
                Text(stringResource(R.string.safety_remove))
            }
        }
    }
    Row {
        Button(onClick = { adding = !adding }, Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.safety_add_app)) }
        Button(onClick = { suggesting = !suggesting }) { Text(stringResource(R.string.safety_suggestions)) }
    }
    if (adding) {
        val list = AgentFreeApps.from(current)
        for (app in apps.filter { !list.isAgentFree(it.packageName) }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }) {
            TextButton(onClick = { now(scope) { data.setAgentFree { AgentFreeEdits.add(it, app.packageName) } } }) { Text(app.label) }
        }
    }
    if (suggesting) {
        val suggestions = AgentFreeEdits.suggestions(current, apps)
        if (suggestions.isEmpty()) Text(stringResource(R.string.safety_no_suggestions), style = MaterialTheme.typography.bodyMedium)
        for (app in suggestions) {
            val on = app.packageName in ticked
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = on, role = Role.Checkbox) { if (it) ticked += app.packageName else ticked -= app.packageName }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Text(app.label, Modifier.padding(start = 8.dp))
            }
        }
        Row {
            if (suggestions.isNotEmpty()) {
                Button(onClick = {
                    val chosen = ticked.toList()
                    ticked.clear()
                    suggesting = false
                    now(scope) {
                        data.setAgentFree { stored -> chosen.fold(stored) { edits, packageName -> AgentFreeEdits.add(edits, packageName) } }
                        data.setAgentFreeReviewed()
                    }
                }, Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.safety_add_ticked)) }
            }
            TextButton(onClick = {
                ticked.clear()
                suggesting = false
                now(scope) { data.setAgentFreeReviewed() }
            }) { Text(stringResource(R.string.safety_none_of_these)) }
        }
    }
}
