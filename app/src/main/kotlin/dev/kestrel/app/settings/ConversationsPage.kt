package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.conversation.screen.GuardWindow
import dev.kestrel.conversation.engine.AllPastDeletion
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** The test tags of the page's groups of choices, whose labels repeat across groups, such as "30 seconds". */
internal object ConversationsTags {
    const val QUIET = "conversations-quiet"
    const val FOLLOW_UP = "conversations-follow-up"
    const val RETENTION = "conversations-retention"
    const val REPLIES = "conversations-replies"
    const val MINUTES = "conversations-minutes"
    const val CREDITS = "conversations-credits"
}

/** A round's model replies that the page offers, within `ConversationSettings.REPLIES` (design 13 of sub-project 7). */
internal val REPLY_CHOICES = listOf(50, 100, 200, 500, 1_000, 2_000)

/** A round's working minutes that the page offers, within `ConversationSettings.WORK_MINUTES`. */
internal val MINUTE_CHOICES = listOf(5, 15, 30, 60, 120, 240)

/** A round's AI Credits that the page offers, within `ConversationSettings.CREDITS`. */
internal val CREDIT_CHOICES = listOf(10, 100, 250, 500, 1_000, 2_500, 5_000)

/**
 * The page "Conversations" (design 13 of sub-project 7): the quiet time between commands, the follow-up window, whether
 * typed commands get spoken replies, how long past conversations are kept, one round's limits (its AI Credits only while
 * the default profile counts them), and "Delete all past conversations" with a question. Each change counts at once, also
 * when the owner leaves the page right after it, as on the Listening page; a choice shows as chosen by the value that
 * Kestrel reads: a stored value outside the design's range shows as the nearest bound, which is a choice, and one within
 * the range that is not a choice shows no choice selected.
 */
@Composable
internal fun ConversationsPage(data: SettingsData, onBack: () -> Unit) {
    val stored by data.conversations.collectAsState(initial = null)
    val countsCredits by data.countsCredits.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    // The settings as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<ConversationSettings?>(null) }
    val settings = written ?: stored
    var asking by remember { mutableStateOf(false) }
    // The engine holds the deletion's state, so a deletion that runs shows also after the owner left the page (review B-M4).
    val deletion by data.allPastDeletion.collectAsState(initial = AllPastDeletion.Idle)
    val deleting = deletion == AllPastDeletion.Running
    // An outcome shows only for a deletion that this page started or saw running, so that an earlier one, which the engine
    // keeps for the process's life, is not shown and read out again on a later visit (review B-N1).
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(deleting) { if (deleting) watched = true }
    val change: ((ConversationSettings) -> ConversationSettings) -> Unit = { edit ->
        (written ?: stored)?.let { current ->
            val next = edit(current)
            written = next
            scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setConversations(next) }
        }
    }
    PageHeader(stringResource(R.string.settings_conversations), onBack)
    Choices(
        R.string.conversations_quiet,
        R.string.conversations_quiet_detail,
        ConversationsTags.QUIET,
        ConversationSettings.QUIET_CHOICES,
        settings?.let { (it.quietMillis / 1_000).toInt() },
        { duration(it) },
    ) { seconds -> change { it.copy(quietSeconds = seconds) } }
    Choices(
        R.string.conversations_follow_up,
        R.string.conversations_follow_up_detail,
        ConversationsTags.FOLLOW_UP,
        ConversationSettings.FOLLOW_UP_CHOICES,
        settings?.let { (it.followUpMillis / 1_000).toInt() },
        { duration(it) },
    ) { seconds -> change { it.copy(followUpSeconds = seconds) } }
    OptionSwitch(R.string.conversations_speak_typed, R.string.conversations_speak_typed_detail, settings?.speakTypedReplies) { on ->
        change { it.copy(speakTypedReplies = on) }
    }
    Choices(
        R.string.conversations_retention,
        null,
        ConversationsTags.RETENTION,
        ConversationSettings.RETENTION_CHOICES,
        settings?.keptDays,
        { pluralStringResource(R.plurals.conversations_days, it, it) },
    ) { days -> change { it.copy(retentionDays = days) } }
    Text(stringResource(R.string.conversations_round), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleLarge)
    val limits = settings?.roundLimits
    Choices(
        R.string.conversations_replies_title,
        null,
        ConversationsTags.REPLIES,
        REPLY_CHOICES,
        limits?.replies,
        { stringResource(R.string.conversations_replies, grouped(it)) },
    ) { replies -> change { it.copy(limits = it.limits.copy(replies = replies)) } }
    Choices(
        R.string.conversations_minutes_title,
        null,
        ConversationsTags.MINUTES,
        MINUTE_CHOICES,
        limits?.workMinutes,
        { duration(it * 60) },
    ) { minutes -> change { it.copy(limits = it.limits.copy(workMinutes = minutes)) } }
    if (countsCredits) {
        Choices(
            R.string.conversations_credits_title,
            null,
            ConversationsTags.CREDITS,
            CREDIT_CHOICES,
            limits?.credits,
            { stringResource(R.string.conversations_credits, grouped(it)) },
        ) { credits -> change { it.copy(limits = it.limits.copy(credits = credits)) } }
    }
    Button(onClick = { asking = true }, Modifier.padding(top = 24.dp), enabled = !deleting) { Text(stringResource(R.string.conversations_delete_all)) }
    // The deletion may wait for the index of past conversations (review N2); its outcome is read out when it comes.
    val result = (deletion as? AllPastDeletion.Ended)?.outcome?.takeIf { watched }
    if (deleting) {
        Text(stringResource(R.string.conversations_deleting), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    } else if (result != null) {
        val said = when (result) {
            PastDeletion.DELETED, PastDeletion.OPEN -> R.string.conversations_deleted
            // Design 9.2: while the index cannot be read, nothing is deleted, and the store tries again by itself later.
            PastDeletion.UNAVAILABLE -> R.string.conversations_delete_unavailable
            PastDeletion.FAILED -> R.string.conversations_delete_failed
        }
        Text(
            stringResource(said),
            Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            // The question is a window of its own, which drops a touch that passed through another app's window (design 12.2).
            text = {
                GuardWindow()
                Text(stringResource(R.string.conversations_delete_all_question))
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    watched = true
                    // The engine runs the deletion in its own scope, so leaving the page stops nothing; the page shows its state.
                    scope.launch { data.deleteAllPast() }
                }) { Text(stringResource(R.string.conversations_delete_all_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.conversations_cancel)) } },
        )
    }
}

/**
 * One setting's choices, with its [title], an optional [detail] and the group's [tag]: each row is one choice, so that its
 * label names it for Voice Access and TalkBack. Nothing can be chosen until the stored settings have been read ([chosen]
 * null).
 */
@Composable
private fun Choices(
    @StringRes title: Int,
    @StringRes detail: Int?,
    tag: String,
    choices: List<Int>,
    chosen: Int?,
    label: @Composable (Int) -> String,
    onChoose: (Int) -> Unit,
) {
    Text(stringResource(title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    Column(Modifier.selectableGroup().testTag(tag)) {
        for (choice in choices) {
            val selected = chosen == choice
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, enabled = chosen != null, role = Role.RadioButton) { onChoose(choice) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = chosen != null)
                Text(label(choice), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// "Off", "30 seconds", "2 minutes" or "4 hours".
@Composable
private fun duration(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.conversations_off)
    seconds < 60 -> pluralStringResource(R.plurals.conversations_seconds, seconds, seconds)
    seconds % 3_600 == 0 -> pluralStringResource(R.plurals.conversations_hours, seconds / 3_600, seconds / 3_600)
    else -> pluralStringResource(R.plurals.conversations_minutes, seconds / 60, seconds / 60)
}

// "1,000": Kestrel's texts are English.
private fun grouped(number: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(number)
