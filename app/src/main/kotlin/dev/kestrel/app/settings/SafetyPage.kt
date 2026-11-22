package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The safety page (design spec, sections 12.3, 12.5 and 12.7): test mode, and from sub-project 5 the mode, test mode's
 * names and the agent-free apps (design D26 of sub-project 5), and plan 5C's screenshot approvals.
 */
@Composable
internal fun SafetyPage(data: SettingsData, onBack: () -> Unit) {
    val testMode by data.testMode.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    PageHeader(stringResource(R.string.settings_safety), onBack)
    // The whole row is one switch, so that its label names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = testMode == true, enabled = testMode != null, role = Role.Switch) { on ->
                // At once, so that the write is queued also when the owner leaves the page right after the tap.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setTestMode(on) }
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.safety_test_mode), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = testMode == true, onCheckedChange = null, enabled = testMode != null)
    }
    Text(stringResource(R.string.safety_test_mode_detail), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.safety_later), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
    // sub-project 5B: mode, test mode, agent-free apps
    AgentSafetySettings(data)
    // sub-project 5C: screenshot approvals
    ScreenshotApprovalsSection(data)
}

/** The approvals of the screenshot question, each with its "Revoke" button (design D20 and D26 of sub-project 5). */
@Composable
private fun ScreenshotApprovalsSection(data: SettingsData) {
    val approvals by data.screenshotApprovals.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.safety_screenshots_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    if (approvals.isEmpty()) {
        Text(stringResource(R.string.safety_screenshots_none), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    for (row in approvals) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.safety_screenshots_row, row.app, row.provider), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.TextButton(onClick = {
                // At once, so that the write is queued also when the owner leaves the page right after the tap.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { data.revokeScreenshots(row) }
            }) {
                Text(stringResource(R.string.safety_screenshots_revoke))
            }
        }
    }
}
