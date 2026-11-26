package dev.kestrel.app.conversation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.goal.GoalTexts
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import kotlinx.coroutines.delay
import java.time.ZoneId

/** The conversation screen's field, by which the device tests find it. */
internal const val FIELD_TAG = "conversation-field"

/** What the conversation screen's buttons do; `ConversationApp` gives them. */
internal class ConversationActions(
    val send: () -> Unit,
    val speak: () -> Unit,
    val stop: () -> Unit,
    val continueGoal: () -> Unit,
    val cancel: () -> Unit,
    val newConversation: () -> Unit,
    val settings: () -> Unit,
    val setup: () -> Unit,
    val showApp: () -> Unit,
    val choose: (OwnerCard, CardChoice) -> Unit,
)

/**
 * The conversation screen (design 10.1 of sub-project 7), drawn from [ui]: the top bar with "New conversation" (or "Stop
 * and start new") and Settings, the banner while the setup checklist is not done, the transcript, the goal's Stop or its
 * hold's way to go on with Continue and Cancel, the open question's or hand-over's card, and the field with Send and Speak.
 * It holds no state of its own but the field's words, which [field] and [onField] carry, and [focus], which "Reply…" asks
 * for. Every button is a Material button with its own label, so that Voice Access and TalkBack can name it.
 */
@Composable
internal fun ConversationScreen(
    ui: ScreenUi,
    field: String,
    onField: (String) -> Unit,
    setupNeeded: Boolean,
    zone: ZoneId,
    focus: FocusRequester,
    actions: ConversationActions,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.conversation_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = actions.newConversation) {
                Text(stringResource(if (ui.stopAndNew) R.string.conversation_stop_and_new else R.string.conversation_new))
            }
            TextButton(onClick = actions.settings) { Text(stringResource(R.string.conversation_settings)) }
        }
        if (setupNeeded) {
            Card(onClick = actions.setup, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(R.string.conversation_setup), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        TranscriptList(ui.lines, zone, Modifier.weight(1f).fillMaxWidth())
        GoalRow(ui.goal, actions)
        ui.card?.let { card -> CardRow(card, onReply = { focus.requestFocus() }, actions = actions) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = field,
                onValueChange = onField,
                modifier = Modifier.weight(1f).focusRequester(focus).testTag(FIELD_TAG),
                placeholder = { Text(stringResource(R.string.conversation_hint)) },
                // Up to four lines; Enter sends, as the field's one action.
                maxLines = MAX_FIELD_LINES,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (field.isNotBlank()) actions.send() }),
            )
            Button(onClick = actions.send, enabled = field.isNotBlank()) { Text(stringResource(R.string.conversation_send)) }
            OutlinedButton(onClick = actions.speak) { Text(stringResource(R.string.conversation_speak)) }
        }
    }
}

/**
 * Stop while the goal works, with plan 7E's words while its round waits behind the owner's instant command, whose own text
 * the bar shows then; while it is held, how to go on, with Continue and Cancel (design 6.4, 6.5 and 10.1).
 */
@Composable
private fun GoalRow(goal: GoalControls, actions: ConversationActions) {
    when (goal) {
        GoalControls.None -> Unit
        is GoalControls.Working -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (goal.behindCommand) Text(GoalTexts.BEHIND_COMMAND, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conversation_stop)) }
        }
        is GoalControls.Held -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(goal.how, style = MaterialTheme.typography.bodyMedium)
            // Cancel, then Continue, as on the goal notification (plan 7E).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.cancel) { Text(stringResource(R.string.conversation_cancel)) }
                Button(onClick = actions.continueGoal) { Text(stringResource(R.string.conversation_continue)) }
            }
        }
    }
}

/**
 * The open question or hand-over (design 10.3), on the conversation and on the past conversations' pages. Its buttons
 * ignore taps for the card's first [CARD_ARMING_MILLIS], as the overlay's do, so that a tap aimed at the card before it
 * never answers it, and each tap names its question (Task A2); "Reply…" calls [onReply], which puts the keyboard on the
 * field. A card that only the overlay answers shows its words and "Show the app", which takes the screen to the back
 * (design 12.2).
 */
@Composable
internal fun CardRow(card: OwnerCard, onReply: () -> Unit, actions: ConversationActions) {
    // Keyed by the card's question number, so that a new question's card starts unarmed.
    var armed by remember(card.key) { mutableStateOf(false) }
    LaunchedEffect(card.key) {
        delay(CARD_ARMING_MILLIS)
        armed = true
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(card.text, style = MaterialTheme.typography.bodyLarge)
            if (!card.answersOnScreen) {
                TextButton(onClick = { if (armed) actions.showApp() }) { Text(stringResource(R.string.conversation_show_app)) }
                return@Column
            }
            for (row in card.choices.chunked(ROW_BUTTONS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in row) {
                        TextButton(onClick = {
                            if (armed) {
                                if (choice == CardChoice.Reply) onReply()
                                actions.choose(card, choice)
                            }
                        }) { Text(labelOf(card, choice)) }
                    }
                }
            }
        }
    }
}

// A pick's button says its choice; every other button says its kind's word, as on the overlay's card.
@Composable
private fun labelOf(card: OwnerCard, choice: CardChoice): String = when (choice) {
    is CardChoice.Pick -> card.picks[choice.number - 1]
    else -> stringResource(checkNotNull(choice.label))
}

private const val ROW_BUTTONS = 3

private const val MAX_FIELD_LINES = 4
