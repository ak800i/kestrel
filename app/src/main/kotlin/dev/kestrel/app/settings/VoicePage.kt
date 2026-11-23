package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.voice.VoiceChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.util.Locale

/** The id of the Kokoro voice's model in models/manifest.json. */
private const val KOKORO_MODEL_ID = "tts-kokoro"

/**
 * The page "Voice and speech" (design spec, sections 8.2 and 15): the voice, Piper or Kokoro, the speed, and "Try it",
 * which speaks a fixed sentence with the choice on the page. Kokoro can be chosen only while its files are on the phone
 * and intact, as [models] says, and the page says when Kokoro did not load the last time that Kestrel turned on. A new
 * choice counts from the next time that Kestrel turns on. The sample has no keyword and no command, because Kestrel's
 * own listening hears it, and its echo filter knows the sample only when the voice session speaks it, with its own
 * voice.
 */
@Composable
internal fun VoicePage(data: SettingsData, models: ModelCheck, onBack: () -> Unit) {
    val stored by data.voiceOptions.collectAsState(initial = null)
    val kokoroFailed by data.kokoroFailed.collectAsState(initial = false)
    val kokoroSlow by data.kokoroSlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // The options as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<VoiceOptionsSetting?>(null) }
    var trying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var tooSlowToTry by remember { mutableStateOf(false) }
    // Null while the check runs or after it failed; then the page says nothing about Kokoro's files.
    val kokoro = (models as? ModelCheck.Done)?.statuses?.firstOrNull { it.model.id == KOKORO_MODEL_ID }
    val kokoroReady = kokoro?.ready == true
    PageHeader(stringResource(R.string.settings_voice), onBack)
    val current = written ?: stored ?: return
    val speed = current.options().speed

    // Stores the options in the order of the changes, also when the owner leaves the page at once, as the page
    // "Listening and activation" does. A failure of "Try it" belongs to the choice that was tried.
    fun change(options: VoiceOptionsSetting) {
        written = options
        failed = false
        tooSlowToTry = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setVoiceOptions(options) }
    }

    Text(stringResource(R.string.voice_choice), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    VoiceRow(stringResource(R.string.voice_piper), current.voice == VoiceChoice.PIPER, enabled = true) {
        change(current.copy(voice = VoiceChoice.PIPER))
    }
    VoiceRow(stringResource(R.string.voice_kokoro), current.voice == VoiceChoice.KOKORO, enabled = kokoroReady) {
        change(current.copy(voice = VoiceChoice.KOKORO))
    }
    when {
        kokoro == null || kokoro.ready -> Unit
        kokoro.absent -> Text(stringResource(R.string.voice_kokoro_missing), style = MaterialTheme.typography.bodySmall)
        else -> Text(stringResource(R.string.voice_kokoro_damaged), style = MaterialTheme.typography.bodySmall)
    }
    if (kokoroFailed && current.voice == VoiceChoice.KOKORO) {
        Text(stringResource(R.string.voice_kokoro_failed), style = MaterialTheme.typography.bodySmall)
    }
    // Plan 2E: Kestrel measured Kokoro's speed at its first load on this phone.
    kokoroSlow?.takeIf { current.voice == VoiceChoice.KOKORO }?.let { factor ->
        Text(
            stringResource(R.string.voice_kokoro_slow, String.format(Locale.ROOT, "%.2f", factor)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        stringResource(R.string.voice_speed, speedText(speed)),
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    Row {
        Button(onClick = { change(current.slower()) }, enabled = speed > VoiceOptionsSetting.SLOWEST) {
            Text(stringResource(R.string.voice_slower))
        }
        Button(onClick = { change(current.faster()) }, Modifier.padding(start = 8.dp), enabled = speed < VoiceOptionsSetting.FASTEST) {
            Text(stringResource(R.string.voice_faster))
        }
    }
    Button(
        onClick = {
            trying = true
            failed = false
            tooSlowToTry = false
            scope.launch {
                try {
                    data.tryVoice(current)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: KokoroTooSlowException) {
                    tooSlowToTry = true
                } catch (e: Exception) {
                    failed = true
                } finally {
                    trying = false
                }
            }
        },
        modifier = Modifier.padding(top = 16.dp),
        enabled = !trying,
    ) {
        Text(stringResource(if (trying) R.string.voice_trying else R.string.voice_try))
    }
    if (failed) Text(stringResource(R.string.voice_try_failed), style = MaterialTheme.typography.bodyMedium)
    if (tooSlowToTry) Text(stringResource(R.string.voice_try_kokoro_off), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.voice_next_start), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
}

/** A speed as the pages show it, such as "1.2". */
internal fun speedText(speed: Float): String = String.format(Locale.ROOT, "%.1f", speed)

@Composable
internal fun VoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    // The whole row is one radio button, so that its label names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
