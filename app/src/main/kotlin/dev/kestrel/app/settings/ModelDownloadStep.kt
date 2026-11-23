package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.download.DownloadChoice
import dev.kestrel.app.download.DownloadState
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.models.DownloadFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The bytes of a megabyte, as the step shows sizes. */
private const val MB = 1_000_000L

/**
 * The download of step 2 of the setup checklist (design spec, sections 8.10 and 15; release plan): the voice, Kokoro or
 * Piper, with the one that suits this phone chosen first; whether the download may use mobile data; Download while
 * [needsAction], the progress with Cancel while a download runs, and why the last download stopped. The screen stays on
 * while the step shows a running download, and [onCheckModels] checks the model files again when a download ends. A voice
 * that the owner chooses while [models] says that its files are on the phone becomes Kestrel's voice at once; otherwise
 * the download that brings its files makes it so.
 */
@Composable
internal fun ModelDownloadStep(data: SettingsData, models: ModelCheck, needsAction: Boolean, onCheckModels: () -> Unit) {
    val stored by data.downloadChoice.collectAsState(initial = null)
    val voiceOptions by data.voiceOptions.collectAsState(initial = null)
    val download by data.modelDownload.collectAsState(initial = DownloadState.Idle)
    val scope = rememberCoroutineScope()
    // The choice as this step wrote it last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<DownloadChoice?>(null) }
    var writtenOptions by remember { mutableStateOf<VoiceOptionsSetting?>(null) }
    val running = download is DownloadState.Running
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(download) { if (download == DownloadState.Done) onCheckModels() }
    val choice = written ?: stored ?: return
    val small = MaterialTheme.typography.bodySmall

    fun change(next: DownloadChoice) {
        written = next
        scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setDownloadChoice(next) }
    }

    fun choose(voice: VoiceChoice) {
        change(choice.copy(voice = voice))
        val options = writtenOptions ?: voiceOptions ?: return
        if (options.voice != voice && SetupChecklist.voiceReady(models, voice)) {
            val next = options.copy(voice = voice)
            writtenOptions = next
            scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setVoiceOptions(next) }
        }
    }

    Text(stringResource(R.string.voice_choice), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
    VoiceRow(stringResource(R.string.voice_kokoro), choice.voice == VoiceChoice.KOKORO, enabled = !running) {
        choose(VoiceChoice.KOKORO)
    }
    VoiceRow(stringResource(R.string.voice_piper), choice.voice == VoiceChoice.PIPER, enabled = !running) {
        choose(VoiceChoice.PIPER)
    }
    val suggestion = if (choice.suggested == VoiceChoice.KOKORO) R.string.setup_models_suggest_kokoro else R.string.setup_models_suggest_piper
    Text(stringResource(suggestion), style = small)
    // The whole row is one switch, so that its label names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = choice.mobileData, enabled = !running, role = Role.Switch) { change(choice.copy(mobileData = it)) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setup_models_mobile_data), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = choice.mobileData, onCheckedChange = null, enabled = !running)
    }
    when (val state = download) {
        is DownloadState.Running -> {
            Text(stringResource(R.string.setup_models_progress, state.doneBytes / MB, state.totalBytes / MB), style = small)
            LinearProgressIndicator(
                progress = { if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Button(onClick = data::cancelModelDownload) { Text(stringResource(R.string.setup_models_cancel)) }
        }
        else -> {
            stoppedText(state)?.let { Text(it, style = small) }
            if (needsAction) {
                Button(onClick = { scope.launch { data.downloadModels(choice) } }) {
                    Text(stringResource(R.string.setup_models_download))
                }
            }
        }
    }
}

/** Why the last download stopped early, or null when none did. */
@Composable
private fun stoppedText(state: DownloadState): String? = when (state) {
    DownloadState.Cancelled -> stringResource(R.string.setup_models_cancelled)
    is DownloadState.Failed -> when (val failure = state.failure) {
        DownloadFailure.NoNetwork -> stringResource(R.string.setup_models_no_network)
        is DownloadFailure.MeteredNetwork ->
            stringResource(if (failure.wifi) R.string.setup_models_metered_wifi else R.string.setup_models_metered)
        is DownloadFailure.NotEnoughSpace -> stringResource(R.string.setup_models_no_space, failure.neededBytes / MB, failure.freeBytes / MB)
        is DownloadFailure.Server -> stringResource(R.string.setup_models_server, failure.file, failure.status)
        is DownloadFailure.Refused -> stringResource(R.string.setup_models_refused, failure.file)
        is DownloadFailure.Connection -> stringResource(R.string.setup_models_connection, failure.file)
        is DownloadFailure.Storage -> stringResource(R.string.setup_models_storage, failure.file)
        is DownloadFailure.Damaged -> stringResource(R.string.setup_models_damaged_download, failure.file)
        DownloadFailure.Unexpected -> stringResource(R.string.setup_models_failed)
    }
    else -> null
}
