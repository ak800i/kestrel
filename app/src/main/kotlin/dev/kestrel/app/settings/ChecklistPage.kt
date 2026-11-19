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

/**
 * The setup checklist (design spec, section 15). [models] is what Kestrel knows about the model files. [samsung]: the
 * battery step also names Samsung's "Never sleeping apps". [onOpenProviders] opens the page "Providers and profiles"
 * from the provider step (design D18 of sub-project 4).
 */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: ModelCheck,
    samsung: Boolean,
    onAction: (SetupAction) -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        val state = states.getValue(step)
        StepCard(step, state, samsung, onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
            if (step == SetupStep.PROVIDER && state in NEEDS_ACTION) {
                Button(onClick = onOpenProviders) { Text(stringResource(R.string.setup_provider_open)) }
            }
        }
    }
}

@Composable
private fun StepCard(
    step: SetupStep,
    state: StepState,
    samsung: Boolean,
    onAction: (SetupAction) -> Unit,
    details: @Composable () -> Unit,
) {
    val texts = texts(step, samsung)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(texts.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.labelLarge)
            val warning = texts.warning.takeIf { state == StepState.WARNING }
            Text(stringResource(warning ?: texts.detail), style = MaterialTheme.typography.bodyMedium)
            // A part of the step that the warning does not cover, such as Samsung's "Never sleeping apps".
            if (warning != null && texts.alsoWithWarning != null) {
                Text(stringResource(texts.alsoWithWarning), style = MaterialTheme.typography.bodyMedium)
            }
            details()
            if (texts.action != null && texts.actionLabel != null && state in NEEDS_ACTION) {
                Button(onClick = { onAction(texts.action) }) { Text(stringResource(texts.actionLabel)) }
            }
        }
    }
}

/** Names each missing or damaged model file, or says that Kestrel is checking them or could not (section 8.10). */
@Composable
private fun ModelProblems(models: ModelCheck) {
    val small = MaterialTheme.typography.bodySmall
    when (models) {
        ModelCheck.Checking -> Text(stringResource(R.string.setup_models_checking), style = small)
        ModelCheck.Failed -> Text(stringResource(R.string.setup_models_check_failed), style = small)
        // An optional model that the owner did not copy is no problem (section 8.10).
        is ModelCheck.Done -> for (status in models.statuses.filterNot { it.absent }) {
            for (path in status.missing) Text(stringResource(R.string.setup_models_missing, path), style = small)
            for (path in status.damaged) Text(stringResource(R.string.setup_models_damaged, path), style = small)
        }
    }
}

private class StepTexts(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val action: SetupAction? = null,
    @StringRes val actionLabel: Int? = null,
    @StringRes val warning: Int? = null,
    @StringRes val alsoWithWarning: Int? = null,
)

private fun texts(step: SetupStep, samsung: Boolean): StepTexts = when (step) {
    SetupStep.INSTALL -> StepTexts(R.string.setup_install_title, R.string.setup_install_detail)
    SetupStep.COPY_MODELS -> StepTexts(R.string.setup_models_title, R.string.setup_models_detail)
    SetupStep.ACCESSIBILITY_SERVICE -> StepTexts(
        R.string.setup_service_title,
        R.string.setup_service_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
        R.string.setup_service_warning,
    )
    SetupStep.MICROPHONE_AND_NOTIFICATIONS -> StepTexts(
        R.string.setup_permissions_title,
        R.string.setup_permissions_detail,
        SetupAction.PERMISSIONS,
        R.string.setup_allow,
    )
    SetupStep.BATTERY -> StepTexts(
        R.string.setup_battery_title,
        // Only Samsung's battery settings have "Never sleeping apps" (section 14.6).
        if (samsung) R.string.setup_battery_detail_samsung else R.string.setup_battery_detail,
        SetupAction.APP_SETTINGS,
        R.string.setup_open_app_settings,
        R.string.setup_battery_warning,
        if (samsung) R.string.setup_battery_never_sleeping else null,
    )
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(
        R.string.setup_assistant_title,
        R.string.setup_assistant_detail,
        SetupAction.DEFAULT_APPS,
        R.string.setup_open_default_apps,
    )
    SetupStep.CALLS -> StepTexts(
        R.string.setup_calls_title,
        R.string.setup_calls_detail,
        SetupAction.CALL_PERMISSIONS,
        R.string.setup_allow_calls,
    )
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
