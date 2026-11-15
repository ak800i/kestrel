package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.voice.models.ModelStatus

/** The setup checklist (design spec, section 15). [models] is null until Kestrel has checked the model files. */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: List<ModelStatus>?,
    onAction: (SetupAction) -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        StepCard(step, states.getValue(step), onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
        }
    }
}

@Composable
private fun StepCard(step: SetupStep, state: StepState, onAction: (SetupAction) -> Unit, details: @Composable () -> Unit) {
    val texts = texts(step)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(texts.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.labelLarge)
            val detail = if (state == StepState.WARNING && texts.warning != null) texts.warning else texts.detail
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
            details()
            if (texts.action != null && texts.actionLabel != null && state in NEEDS_ACTION) {
                Button(onClick = { onAction(texts.action) }) { Text(stringResource(texts.actionLabel)) }
            }
        }
    }
}

/** Names each model file that is missing or damaged (design spec, section 8.10). */
@Composable
private fun ModelProblems(models: List<ModelStatus>?) {
    if (models == null) {
        Text(stringResource(R.string.setup_models_checking), style = MaterialTheme.typography.bodySmall)
        return
    }
    for (status in models) {
        for (path in status.missing) {
            Text(stringResource(R.string.setup_models_missing, path), style = MaterialTheme.typography.bodySmall)
        }
        for (path in status.damaged) {
            Text(stringResource(R.string.setup_models_damaged, path), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class StepTexts(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val action: SetupAction? = null,
    @StringRes val actionLabel: Int? = null,
    @StringRes val warning: Int? = null,
)

private fun texts(step: SetupStep): StepTexts = when (step) {
    SetupStep.INSTALL -> StepTexts(R.string.setup_install_title, R.string.setup_install_detail)
    SetupStep.COPY_MODELS -> StepTexts(R.string.setup_models_title, R.string.setup_models_detail)
    SetupStep.ACCESSIBILITY_SERVICE -> StepTexts(
        R.string.setup_service_title,
        R.string.setup_service_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
    )
    SetupStep.MICROPHONE_AND_NOTIFICATIONS -> StepTexts(
        R.string.setup_permissions_title,
        R.string.setup_permissions_detail,
        SetupAction.PERMISSIONS,
        R.string.setup_allow,
    )
    SetupStep.BATTERY -> StepTexts(
        R.string.setup_battery_title,
        R.string.setup_battery_detail,
        SetupAction.APP_SETTINGS,
        R.string.setup_open_app_settings,
    )
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(R.string.setup_assistant_title, R.string.setup_assistant_detail)
    SetupStep.PROVIDER -> StepTexts(R.string.setup_provider_title, R.string.setup_provider_detail)
    SetupStep.VOICE_ACCESS -> StepTexts(
        R.string.setup_voice_access_title,
        R.string.setup_voice_access_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
        R.string.setup_voice_access_warning,
    )
}

@StringRes
private fun stateLabel(state: StepState): Int = when (state) {
    StepState.DONE -> R.string.setup_state_done
    StepState.TO_DO -> R.string.setup_state_to_do
    StepState.LATER -> R.string.setup_state_later
    StepState.OPTIONAL -> R.string.setup_state_optional
    StepState.WARNING -> R.string.setup_state_warning
}

private val NEEDS_ACTION = setOf(StepState.TO_DO, StepState.OPTIONAL, StepState.WARNING)
