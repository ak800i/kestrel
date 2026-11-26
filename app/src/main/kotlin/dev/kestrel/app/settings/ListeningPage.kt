package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
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
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.orchestrator.WakeUpMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The page "Listening and activation" (design spec, sections 8.5 to 8.8 and 15): what Kestrel does after an unlock, the
 * pause after 30 s without speech, the commands during a call and the earbud microphone, which all count at once, and
 * how the ways to start Kestrel stand, from [status]. Its button opens Android's default apps through [onAction].
 */
@Composable
internal fun ListeningPage(data: SettingsData, status: SetupStatus, onAction: (SetupAction) -> Unit, onBack: () -> Unit) {
    val stored by data.listening.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // The options as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<ListeningOptions?>(null) }
    val options = written ?: stored
    // Stores the current options with one change, in the order of the changes, which the store keeps because each write
    // is queued at once; a write is made also when the owner leaves the page right after it. Nothing changes until the
    // stored options have been read. It reads the newest written options itself, so that two changes in one frame both
    // count.
    val change: ((ListeningOptions) -> ListeningOptions) -> Unit = { edit ->
        (written ?: stored)?.let { current ->
            val next = edit(current)
            written = next
            scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setListening(next) }
        }
    }
    PageHeader(stringResource(R.string.settings_listening), onBack)
    Text(stringResource(R.string.listening_wake_up), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    for ((mode, label) in WAKE_UP_CHOICES) {
        val selected = options?.onWakeUp == mode
        // The whole row is one choice, so that its label names it for Voice Access and TalkBack.
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = options != null, role = Role.RadioButton) {
                    change { it.copy(onWakeUp = mode) }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = options != null)
            Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    OptionSwitch(
        R.string.listening_pause_after_silence,
        R.string.listening_pause_after_silence_detail,
        options?.pauseAfterSilence,
    ) { on -> change { it.copy(pauseAfterSilence = on) } }
    OptionSwitch(R.string.listening_call_commands, R.string.listening_call_commands_detail, options?.callCommands) { on ->
        change { it.copy(callCommands = on) }
    }
    OptionSwitch(
        R.string.listening_earbud_microphone,
        R.string.listening_earbud_microphone_detail,
        options?.earbudMicrophone,
    ) { on -> change { it.copy(earbudMicrophone = on) } }
    Text(stringResource(R.string.listening_activation), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    Line(if (status.serviceOn) R.string.listening_service_on else R.string.listening_service_off)
    Line(if (status.shortcutTarget) R.string.listening_shortcut_set else R.string.listening_shortcut_not_set)
    Line(if (status.buttonTarget) R.string.listening_button_set else R.string.listening_button_not_set)
    // Android does not tell an app whether its tile is in Quick Settings.
    Line(R.string.listening_tile)
    Line(if (status.assistantRole) R.string.listening_assistant_held else R.string.listening_assistant_not_held)
    Button(onClick = { onAction(SetupAction.DEFAULT_APPS) }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.setup_open_default_apps))
    }
}

/** A switch whose whole row toggles it, so that its title names it for Voice Access and TalkBack, with a detail below. */
@Composable
internal fun OptionSwitch(@StringRes title: Int, @StringRes detail: Int, on: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on == true, enabled = on != null, role = Role.Switch, onValueChange = onChange)
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = on == true, onCheckedChange = null, enabled = on != null)
    }
    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
}

/** One line about a way to start Kestrel. */
@Composable
private fun Line(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
}

private val WAKE_UP_CHOICES = listOf(
    WakeUpMode.RESUME to R.string.listening_wake_up_resume,
    WakeUpMode.LISTEN to R.string.listening_wake_up_listen,
    WakeUpMode.STAY_OFF to R.string.listening_wake_up_stay_off,
)
