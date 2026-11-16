package dev.kestrel.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R

/** The pages of the settings app (design spec, section 15). Later sub-projects add their own pages. */
enum class SettingsPage { HOME, CHECKLIST, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }

/** What the button of a checklist step does. */
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS }

/**
 * Kestrel's settings app. Every page has a Back button, because a voice user cannot easily use the back gesture.
 * [onCheckModels] checks the model files again, which happens whenever the checklist or the About page opens, and when
 * the About page shows again after a licence text (design spec, 8.10). [onExportLogs] asks the owner for a file to
 * export both logs to.
 */
@Composable
fun SettingsApp(
    status: SetupStatus,
    data: SettingsData,
    onAction: (SetupAction) -> Unit,
    onCheckModels: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    // A licence text opens on top of the About page, and Back returns there.
    var licence by rememberSaveable { mutableStateOf<Licence?>(null) }
    // Keyed on the licence too, so that the About page checks again when the owner comes back from a licence text.
    LaunchedEffect(page, licence) {
        if (licence == null && (page == SettingsPage.CHECKLIST || page == SettingsPage.ABOUT)) onCheckModels()
    }
    BackHandler(enabled = page != SettingsPage.HOME) { if (licence != null) licence = null else page = SettingsPage.HOME }
    val states = SetupChecklist.states(status)
    val home = { page = SettingsPage.HOME }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (page) {
            SettingsPage.HOME -> ScrollingPage { HomePage(states, data) { page = it } }
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, status.models, onAction, home) }
            SettingsPage.SAFETY -> ScrollingPage { SafetyPage(data, home) }
            SettingsPage.ACTIVITY_LOG -> ActivityLogPage(data, onExportLogs, home)
            SettingsPage.NETWORK_LOG -> NetworkLogPage(data, onExportLogs, home)
            SettingsPage.ABOUT -> when (val shown = licence) {
                null -> ScrollingPage { AboutPage(data, status.models, { licence = it }, home) }
                else -> LicencePage(shown, data) { licence = null }
            }
        }
    }
}

/** A page that scrolls as a whole. The log pages scroll their lists themselves. */
@Composable
private fun ScrollingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
}

@Composable
private fun HomePage(states: Map<SetupStep, StepState>, data: SettingsData, onOpen: (SettingsPage) -> Unit) {
    val done = states.values.count { it == StepState.DONE }
    val testMode by data.testMode.collectAsState(initial = null)
    val logsSummary = stringResource(R.string.settings_logs_summary)
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
    PageEntry(
        stringResource(R.string.settings_checklist),
        pluralStringResource(R.plurals.settings_checklist_progress, states.size, done, states.size),
    ) { onOpen(SettingsPage.CHECKLIST) }
    PageEntry(stringResource(R.string.settings_safety), testModeSummary(testMode)) { onOpen(SettingsPage.SAFETY) }
    PageEntry(stringResource(R.string.settings_activity_log), logsSummary) { onOpen(SettingsPage.ACTIVITY_LOG) }
    PageEntry(stringResource(R.string.settings_network_log), logsSummary) { onOpen(SettingsPage.NETWORK_LOG) }
    PageEntry(stringResource(R.string.settings_about), stringResource(R.string.settings_about_summary, data.version)) {
        onOpen(SettingsPage.ABOUT)
    }
}

@Composable
private fun testModeSummary(on: Boolean?): String = when (on) {
    true -> stringResource(R.string.settings_test_mode_on)
    false -> stringResource(R.string.settings_test_mode_off)
    null -> ""
}

@Composable
internal fun PageEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The Back button and the title at the top of every page except the home page. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
