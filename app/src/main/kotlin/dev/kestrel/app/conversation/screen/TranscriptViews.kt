package dev.kestrel.app.conversation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.app.R
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.InputChannel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A transcript's lines (design 10.1 and 9.5 of sub-project 7), the newest at the bottom, which it scrolls to when a line
 * comes. Without lines it says what the screen is for.
 */
@Composable
internal fun TranscriptList(lines: List<TranscriptLine>, zone: ZoneId, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.conversation_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines) { line -> Line(line, zone) }
    }
}

/** "18:04": a command's time and a conversation's end. */
internal fun timeOf(wall: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(wall).atZone(zone))

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

@Composable
private fun Line(line: TranscriptLine, zone: ZoneId) {
    when (line) {
        is TranscriptLine.Owner -> OwnerWords(line, zone)
        is TranscriptLine.Kestrel -> Column(Modifier.fillMaxWidth()) {
            if (line.asked) Text(stringResource(R.string.conversation_kestrel_asked), style = MaterialTheme.typography.labelMedium)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
        is TranscriptLine.Answer -> Text(line.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        is TranscriptLine.Checklist -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.conversation_checklist, line.done, line.items.size), style = MaterialTheme.typography.titleSmall)
                for (item in line.items) Text("${stringResource(stateLabel(item.state))} · ${item.text}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is TranscriptLine.Note -> Text(line.text, style = MaterialTheme.typography.bodySmall)
        is TranscriptLine.LeftOut -> Text(stringResource(R.string.conversation_left_out, line.lines, line.data), style = MaterialTheme.typography.bodySmall)
        is TranscriptLine.Continued ->
            Text(stringResource(R.string.conversation_continued, ConversationTexts.date(line.atWall, zone)), style = MaterialTheme.typography.labelLarge)
        is TranscriptLine.Ended -> Text(stringResource(R.string.conversation_ended, timeOf(line.atWall, zone)), style = MaterialTheme.typography.labelLarge)
    }
}

// The owner's words on the right, with what they were, whether he typed or spoke them, and a command's time.
@Composable
private fun OwnerWords(line: TranscriptLine.Owner, zone: ZoneId) {
    val kind = when (line.kind) {
        OwnerLine.COMMAND -> null
        OwnerLine.ANSWER -> R.string.conversation_you_answered
        OwnerLine.STEERING -> R.string.conversation_you_added
        OwnerLine.TOO_LATE -> R.string.conversation_you_added_too_late
        OwnerLine.CONTINUE -> R.string.conversation_you_continued
    }
    val channel = line.channel?.let { if (it == InputChannel.TEXT) R.string.conversation_typed else R.string.conversation_spoken }
    val label = listOfNotNull(kind?.let { stringResource(it) }, channel?.let { stringResource(it) }, line.atWall?.let { timeOf(it, zone) }).joinToString(" · ")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium)
        Card { Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
    }
}

private fun stateLabel(state: ItemState): Int = when (state) {
    ItemState.TODO -> R.string.conversation_item_todo
    ItemState.DOING -> R.string.conversation_item_doing
    ItemState.DONE -> R.string.conversation_item_done
    ItemState.SKIPPED -> R.string.conversation_item_skipped
    ItemState.FAILED -> R.string.conversation_item_failed
}
