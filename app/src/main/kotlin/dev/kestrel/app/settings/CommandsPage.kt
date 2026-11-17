package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.commands.CommandHelp
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The page "Instant commands" (design spec, sections 9.4, 9.5, 9.8 and 15): "Require verbs", where the numbers go, and
 * the list of commands that "What can I say?" opens. Each choice counts from the next command.
 */
@Composable
internal fun CommandsPage(data: SettingsData, onBack: () -> Unit) {
    val stored by data.commandOptions.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // The options as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<CommandOptions?>(null) }
    val options = written ?: stored
    // Stores the current options with one change, as the page "Listening and activation" does: each write is queued at
    // once, so the store keeps the order of the changes, and a write is made also when the owner leaves the page at once.
    val change: ((CommandOptions) -> CommandOptions) -> Unit = { edit ->
        (written ?: stored)?.let { current ->
            val next = edit(current)
            written = next
            scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setCommandOptions(next) }
        }
    }
    PageHeader(stringResource(R.string.settings_commands), onBack)
    // The whole row toggles the switch, so that its title names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = options?.requireVerbs == true, enabled = options != null, role = Role.Switch) { on ->
                change { it.copy(requireVerbs = on) }
            }
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.commands_require_verbs), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = options?.requireVerbs == true, onCheckedChange = null, enabled = options != null)
    }
    Text(stringResource(R.string.commands_require_verbs_detail), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.commands_numbers), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    for ((mode, label) in NUMBER_CHOICES) {
        val selected = options?.numbers == mode
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = options != null, role = Role.RadioButton) {
                    change { it.copy(numbers = mode) }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = options != null)
            Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Text(stringResource(R.string.commands_list), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.commands_list_detail), style = MaterialTheme.typography.bodyMedium)
    for (group in CommandHelp.groups) {
        Text(group.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        for (example in group.examples) Text(example, style = MaterialTheme.typography.bodyMedium)
    }
}

private val NUMBER_CHOICES = listOf(
    NumbersMode.ALL to R.string.commands_numbers_all,
    NumbersMode.UNLABELED to R.string.commands_numbers_unlabeled,
)
