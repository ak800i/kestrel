package dev.kestrel.app.conversation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import dev.kestrel.conversation.model.InputIntent

/**
 * The conversation screen's host (design 10.1 of sub-project 7): it draws [ConversationScreen] from [screen]'s model, the
 * conversation's view and the open card, and holds only the field's words; it gives what it draws the screen's visit
 * ([LocalScreenVisit]). [replyTo] is the question that "Reply…" opened
 * the screen for: the keyboard goes up on the field while that question is open.
 */
@Composable
internal fun ConversationApp(
    screen: ConversationScreenViewModel,
    setupNeeded: Boolean,
    replyTo: Int?,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    onShowApp: () -> Unit,
) {
    val state by screen.model.state.collectAsState()
    val view by screen.view.collectAsState()
    val card by screen.card.collectAsState()
    val visit by screen.visits.collectAsState()
    var field by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(field.isNotBlank()) { screen.field(field.isNotBlank()) }
    LaunchedEffect(replyTo, card) {
        if (replyTo != null && card?.question == replyTo) focus.requestFocus()
    }

    val actions = ConversationActions(
        send = { if (screen.send(field)) field = "" },
        speak = screen::listen,
        stop = { screen.input(InputIntent.Stop) },
        continueGoal = { screen.input(InputIntent.Continue) },
        cancel = { screen.input(InputIntent.Stop) },
        newConversation = screen::newConversation,
        settings = onSettings,
        setup = onSetup,
        showApp = onShowApp,
        choose = screen::choose,
    )
    // The card's buttons arm again on each of the screen's visits in front (review of Task 7).
    CompositionLocalProvider(LocalScreenVisit provides visit) {
        ConversationScreen(ConversationScreenRules.ui(state, view, card), field, { field = it }, setupNeeded, screen.zone, focus, actions)
    }
}
