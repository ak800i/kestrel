package dev.kestrel.app.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.conversation.engine.HistoryDeletion
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.logs.NetworkOutcome
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The activity log, newest entry first (design spec, section 13.5). */
@Composable
internal fun ActivityLogPage(data: SettingsData, onExport: () -> Unit, onBack: () -> Unit) {
    LogPage(R.string.settings_activity_log, data::activityLog, data, onExport, onBack) { entry ->
        val event = entry.event
        EntryCard(
            heading = stringResource(kindLabel(event.kind)),
            timeMillis = entry.timestampMillis,
            lines = listOfNotNull(event.text, event.taskId?.let { stringResource(R.string.log_task, it) }),
        )
    }
}

/** The network log, newest entry first (design spec, section 13.5). */
@Composable
internal fun NetworkLogPage(data: SettingsData, onExport: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    LogPage(R.string.settings_network_log, data::networkLog, data, onExport, onBack) { entry ->
        val event = entry.event
        EntryCard(
            heading = stringResource(outcomeLabel(event.outcome)),
            timeMillis = entry.timestampMillis,
            lines = listOfNotNull(
                "${event.method} ${event.host}${event.path}",
                stringResource(
                    R.string.log_network_sizes,
                    Formatter.formatShortFileSize(context, event.requestBytes),
                    Formatter.formatShortFileSize(context, event.responseBytes),
                ),
                event.status?.let { stringResource(R.string.log_network_status, it) },
                event.detail,
                event.taskId?.let { stringResource(R.string.log_task, it) },
            ),
        )
    }
}

/**
 * A log page: the Back button, buttons that export both logs and delete the history (design spec, section 13.5; design 9.7
 * of sub-project 7), why a deletion did not happen, and the entries.
 */
@Composable
private fun <T> LogPage(
    @StringRes title: Int,
    load: suspend () -> List<LogEntry<T>>,
    data: SettingsData,
    onExport: () -> Unit,
    onBack: () -> Unit,
    entry: @Composable (LogEntry<T>) -> Unit,
) {
    var entries by remember { mutableStateOf<List<LogEntry<T>>?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var deletion by rememberSaveable { mutableStateOf<HistoryDeletion?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(loads) { entries = load() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { PageHeader(stringResource(title), onBack) }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Button(onClick = onExport) { Text(stringResource(R.string.logs_export)) }
                OutlinedButton(onClick = { confirmingDelete = true }) { Text(stringResource(R.string.logs_delete)) }
            }
        }
        when (deletion) {
            HistoryDeletion.REFUSED -> item { Text(stringResource(R.string.logs_delete_refused)) }
            HistoryDeletion.FAILED -> item { Text(stringResource(R.string.logs_delete_failed)) }
            HistoryDeletion.DELETED, null -> Unit
        }
        // The page reads only the newest entries; the export holds them all (design D32 item 47 of sub-project 5).
        item { Text(stringResource(R.string.logs_newest, LogStore.DEFAULT_LIMIT)) }
        val shown = entries
        when {
            shown == null -> item { Text(stringResource(R.string.logs_loading)) }
            shown.isEmpty() -> item { Text(stringResource(R.string.logs_empty)) }
            else -> items(shown, key = { it.id }) { entry(it) }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            text = { Text(stringResource(R.string.logs_delete_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            deletion = data.deleteHistory()
                            loads++
                        }
                    },
                ) { Text(stringResource(R.string.logs_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.logs_delete_cancel)) }
            },
        )
    }
}

@Composable
private fun EntryCard(heading: String, timeMillis: Long, lines: List<String>) {
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()))
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall)
            Text(time, style = MaterialTheme.typography.labelMedium)
            for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@StringRes
private fun kindLabel(kind: ActivityKind): Int = when (kind) {
    ActivityKind.ACTION -> R.string.log_kind_action
    ActivityKind.NOTE -> R.string.log_kind_note
    ActivityKind.ERROR -> R.string.log_kind_error
    ActivityKind.PROMPT -> R.string.log_kind_prompt
    ActivityKind.REPLY -> R.string.log_kind_reply
    ActivityKind.SAFETY -> R.string.log_kind_safety
}

@StringRes
private fun outcomeLabel(outcome: NetworkOutcome): Int = when (outcome) {
    NetworkOutcome.COMPLETED -> R.string.log_outcome_completed
    NetworkOutcome.FAILED -> R.string.log_outcome_failed
    NetworkOutcome.BLOCKED -> R.string.log_outcome_blocked
}
