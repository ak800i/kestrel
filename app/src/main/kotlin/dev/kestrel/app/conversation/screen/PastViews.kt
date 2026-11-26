package dev.kestrel.app.conversation.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.conversation.model.ConversationId
import kotlinx.coroutines.delay
import java.time.ZoneId

/** The list's search field, by which the device tests find it. */
internal const val PAST_SEARCH_TAG = "past-search"

/** A row of the list, by which the device tests find a conversation's row. */
internal fun pastRowTag(id: ConversationId): String = "past-row-${id.value}"

/** What the owner does on the past conversations' pages; the screen's view model changes their state. */
internal class PastActions(
    val query: (String) -> Unit,
    val open: (ConversationId) -> Unit,
    val back: () -> Unit,
    val askDelete: (ConversationId) -> Unit,
    val dismissDelete: () -> Unit,
    val confirmDelete: () -> Unit,
    val continueConversation: (ConversationId) -> Unit,
)

/**
 * "Past conversations" (design 9.5 of sub-project 7), as [state] says: the list with its search field, or one
 * conversation's transcript, read-only, with "Continue this conversation" and Delete. Each deletion asks first, in a
 * dialog that drops a touch that passed through another app's window (design 12.2). Back goes from the transcript to the
 * list, and from the list back to the conversation. [openCard] draws the open question's card under each page's title, as
 * the conversation does, because the card desk gives the card to the screen while it is in front (review N6).
 */
@Composable
internal fun PastPages(state: PastPagesState, past: PastConversations, zone: ZoneId, actions: PastActions, openCard: @Composable () -> Unit) {
    BackHandler(onBack = actions.back)

    val notice = when (state.notice) {
        PastNotice.OPEN_NOW -> R.string.past_open_now
        PastNotice.UNAVAILABLE -> R.string.past_unavailable
        PastNotice.DELETE_FAILED -> R.string.past_delete_failed
        null -> null
    }
    val shown = state.opened
    if (shown == null) {
        PastListPage(past, state.query, actions.query, state.deletions, notice, openCard, onOpen = actions.open, onDelete = actions.askDelete, onBack = actions.back)
    } else {
        PastTranscriptPage(
            past, shown, zone, notice, openCard,
            onContinue = { actions.continueConversation(shown) },
            onDelete = { actions.askDelete(shown) },
            onBack = actions.back,
        )
    }
    if (state.deleting != null) {
        AlertDialog(
            onDismissRequest = actions.dismissDelete,
            text = {
                GuardWindow()
                Text(stringResource(R.string.past_delete_question))
            },
            confirmButton = { TextButton(onClick = actions.confirmDelete) { Text(stringResource(R.string.past_delete)) } },
            dismissButton = { TextButton(onClick = actions.dismissDelete) { Text(stringResource(R.string.past_cancel)) } },
        )
    }
}
/** What a page has read: nothing yet, or [Done.value]. */
private sealed interface Loaded<out T> {
    data object Waiting : Loaded<Nothing>

    data class Done<T>(val value: T) : Loaded<T>
}

// The list, read again after each deletion ([deletions]) and, while the owner types, once he stops for a moment, because
// each search ranks the whole index. It says "Loading past conversations…" until its first read ends; a later read keeps the
// rows that are shown, under a thin progress line and without their Delete, until its own rows come (review B-M3 and B-N2).
@Composable
private fun PastListPage(
    past: PastConversations,
    query: String,
    onQuery: (String) -> Unit,
    deletions: Int,
    @StringRes notice: Int?,
    openCard: @Composable () -> Unit,
    onOpen: (ConversationId) -> Unit,
    onDelete: (ConversationId) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember { mutableStateOf<Loaded<PastListing>>(Loaded.Waiting) }
    var reading by remember { mutableStateOf(true) }
    LaunchedEffect(query, deletions) {
        reading = true
        if (query.isNotBlank()) delay(SEARCH_DELAY_MILLIS)
        rows = Loaded.Done(past.rows(query))
        reading = false
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        TopRow(stringResource(R.string.past_title), onBack)
        openCard()
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().testTag(PAST_SEARCH_TAG),
            placeholder = { Text(stringResource(R.string.past_search)) },
            singleLine = true,
        )
        notice?.let { Note(it) }
        val loaded = rows
        if (loaded is Loaded.Waiting) {
            Note(R.string.past_loading)
        } else if (loaded is Loaded.Done) {
            if (reading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            when (val listing = loaded.value) {
                PastListing.Unavailable -> Note(R.string.past_unavailable)
                PastListing.Failed -> Note(R.string.past_failed)
                is PastListing.Rows -> if (listing.rows.isEmpty()) {
                    Note(if (query.isBlank()) R.string.past_empty else R.string.past_none_found)
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listing.rows, key = { it.id.value }) { row -> PastRowCard(row, deletable = !reading, onOpen, onDelete) }
                    }
                }
            }
        }
    }
}

// A row's Delete waits while the list is read again (review B-N2 of plan 7F): a row that stays on the screen during the read
// may be one that the owner has just deleted.
@Composable
private fun PastRowCard(row: PastRow, deletable: Boolean, onOpen: (ConversationId) -> Unit, onDelete: (ConversationId) -> Unit) {
    Card(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth().testTag(pastRowTag(row.id))) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(row.date, style = MaterialTheme.typography.labelMedium)
            Text(row.request, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.past_commands, row.commands, row.commands), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onDelete(row.id) }, enabled = deletable) { Text(stringResource(R.string.past_delete)) }
            }
        }
    }
}

@Composable
private fun PastTranscriptPage(
    past: PastConversations,
    id: ConversationId,
    zone: ZoneId,
    @StringRes notice: Int?,
    openCard: @Composable () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var transcript by remember(id) { mutableStateOf<Loaded<PastTranscript?>>(Loaded.Waiting) }
    LaunchedEffect(id) { transcript = Loaded.Done(past.transcript(id)) }
    val loaded = transcript
    val shown = (loaded as? Loaded.Done)?.value
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        TopRow(shown?.date ?: stringResource(R.string.past_title), onBack)
        openCard()
        notice?.let { Note(it) }
        when {
            loaded is Loaded.Waiting -> Note(R.string.past_loading)
            shown == null -> Note(R.string.past_gone)
            else -> {
                TranscriptList(shown.lines, zone, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onContinue) { Text(stringResource(R.string.past_continue)) }
                    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.past_delete)) }
                }
            }
        }
    }
}

@Composable
private fun TopRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.past_back)) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
}

/** How long the owner's typing pauses before the search runs. */
private const val SEARCH_DELAY_MILLIS = 300L
