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
    val pages by screen.pastPages.collectAsState()
    val visit by screen.visits.collectAsState()
    var field by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(field.isNotBlank()) { screen.field(field.isNotBlank()) }
    // "Reply…" brings the conversation back from the past conversations, and then puts the keyboard on its field.
    LaunchedEffect(replyTo, card) {
        if (replyTo != null && card?.question == replyTo) screen.closePast()
    }
    var replyField by remember { mutableStateOf(false) }
    LaunchedEffect(replyTo, card, pages, replyField) {
        if (pages == null && (replyField || (replyTo != null && card?.question == replyTo))) {
            focus.requestFocus()
            replyField = false
        }
    }

    val actions = ConversationActions(
        send = { if (screen.send(field)) field = "" },
        speak = screen::listen,
        stop = { screen.input(InputIntent.Stop) },
        continueGoal = { screen.input(InputIntent.Continue) },
        cancel = { screen.input(InputIntent.Stop) },
        newConversation = screen::newConversation,
        settings = onSettings,
        past = screen::openPast,
        setup = onSetup,
        showApp = onShowApp,
        choose = screen::choose,
    )
    // The card's buttons arm again on each of the screen's visits in front (review of Task 7), on the conversation and on
    // the past conversations' pages alike.
    CompositionLocalProvider(LocalScreenVisit provides visit) {
        val shown = pages
        if (shown != null) {
            val past = PastActions(
                query = { text -> screen.past { it.copy(query = text) } },
                open = { id -> screen.past { PastPagesRules.opened(it, id) } },
                back = { screen.past(PastPagesRules::back) },
                askDelete = { id -> screen.past { it.copy(deleting = id) } },
                dismissDelete = { screen.past { it.copy(deleting = null) } },
                confirmDelete = screen::confirmDelete,
                continueConversation = screen::continuePast,
            )
            // "Reply…" on the card closes the pages and then puts the keyboard on the conversation's field.
            PastPages(shown, screen.pastConversations, screen.zone, past) {
                card?.let { open ->
                    CardRow(open, onReply = {
                        screen.closePast()
                        replyField = true
                    }, actions = actions)
                }
            }
        } else {
            ConversationScreen(ConversationScreenRules.ui(state, view, card), field, { field = it }, setupNeeded, screen.zone, focus, actions)
        }
    }
}
