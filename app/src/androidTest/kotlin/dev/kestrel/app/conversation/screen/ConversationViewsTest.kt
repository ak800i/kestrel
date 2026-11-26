package dev.kestrel.app.conversation.screen

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.app.R
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.goal.GoalTexts
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The conversation screen's views with Compose tests on kestrel-api37 (design 10.1 and 15.2 of sub-project 7). */
class ConversationViewsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Europe/Belgrade")
    private val done = mutableListOf<String>()
    private val actions = ConversationActions(
        send = { done += "send" },
        speak = { done += "speak" },
        stop = { done += "stop" },
        continueGoal = { done += "continue" },
        cancel = { done += "cancel" },
        newConversation = { done += "new" },
        settings = { done += "settings" },
        past = { done += "past" },
        setup = { done += "setup" },
        showApp = { done += "show app" },
        choose = { card, choice -> done += "choose ${card.key} $choice" },
    )

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun show(ui: MutableState<ScreenUi>, setupNeeded: Boolean = false, visit: MutableState<Int> = mutableStateOf(0)) {
        compose.setContent {
            var words by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            CompositionLocalProvider(LocalScreenVisit provides visit.value) {
                MaterialTheme { ConversationScreen(ui.value, words, { words = it }, setupNeeded, zone, focus, actions) }
            }
        }
    }

    @Test
    fun theTranscriptShowsEachCommandWithItsChannelAndTimeKestrelsWordsAndTheEnd() {
        val at = ZonedDateTime.of(2026, 11, 20, 18, 4, 0, 0, zone).toInstant().toEpochMilli()
        val lines = listOf(
            TranscriptLine.Owner(OwnerLine.COMMAND, "Read my latest SMS", InputChannel.TEXT, at),
            TranscriptLine.Kestrel("Which one?", asked = true),
            TranscriptLine.Owner(OwnerLine.ANSWER, "The newest", InputChannel.VOICE),
            TranscriptLine.Checklist(listOf(ChecklistRow("Open Messages", ItemState.DONE)), done = 1),
            TranscriptLine.Answer("Mila wrote: Ambar at 8."),
            TranscriptLine.Ended(at + 60_000),
        )
        show(mutableStateOf(ScreenUi(lines, GoalControls.None, stopAndNew = false, card = null)))

        compose.onNodeWithText("Read my latest SMS").assertIsDisplayed()
        compose.onNodeWithText("${text(R.string.conversation_typed)} · 18:04").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_kestrel_asked)).assertIsDisplayed()
        compose.onNodeWithText("${text(R.string.conversation_you_answered)} · ${text(R.string.conversation_spoken)}").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_checklist, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText("Mila wrote: Ambar at 8.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_ended, "18:05")).assertIsDisplayed()
    }

    @Test
    fun aWorkingGoalHasStopAndAHeldOneSaysHowToGoOnWithContinueAndCancel() {
        val ui = mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(behindCommand = true), stopAndNew = true, card = null))
        show(ui)

        compose.onNodeWithText(GoalTexts.BEHIND_COMMAND).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.conversation_stop_and_new)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_stop)).performClick()
        ui.value = ScreenUi(emptyList(), GoalControls.Held(ConversationTexts.LEAVE_KESTREL), stopAndNew = true, card = null)
        compose.onNodeWithText(ConversationTexts.LEAVE_KESTREL).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.conversation_stop)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.conversation_continue)).performClick()
        compose.onNodeWithText(text(R.string.conversation_cancel)).performClick()
        ui.value = ScreenUi(emptyList(), GoalControls.None, stopAndNew = false, card = null)
        compose.onNodeWithText(text(R.string.conversation_new)).performClick()

        assertEquals(listOf("stop", "continue", "cancel", "new"), done)
    }

    @Test
    fun theOpenQuestionShowsItsButtonsAndReplyPutsTheKeyboardOnTheField() {
        val card = OwnerCard(4, "Send it to Mila?", listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), question = 4, kind = QuestionKind.YES_NO)
        // A tap in the card's first 600 ms may have been aimed at the card before it (design D32 item 21 of sub-project 5),
        // so the test's clock stands still until that tap is done.
        compose.mainClock.autoAdvance = false
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(), stopAndNew = true, card = card)))
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Send it to Mila?").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.card_no)).performClick()
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(text(R.string.card_yes)).performClick()
        compose.onNodeWithText(text(R.string.card_reply)).performClick()

        compose.onNodeWithTag(FIELD_TAG).assertIsFocused()
        assertEquals(listOf("choose 4 Yes", "choose 4 Reply"), done)
    }

    // Review M4 of Task 8: the card's buttons arm again on each visit of the screen, so that a tap that the owner aimed at
    // the app that was in front, as the screen comes back, answers nothing.
    @Test
    fun theCardsButtonsArmAgainOnEachVisit() {
        val card = OwnerCard(7, "Send it to Mila?", listOf(CardChoice.Yes, CardChoice.No), question = 7, kind = QuestionKind.YES_NO)
        val visit = mutableStateOf(1)
        compose.mainClock.autoAdvance = false
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(), stopAndNew = true, card = card)), visit = visit)
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)
        compose.mainClock.advanceTimeByFrame()

        visit.value = 2
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.card_yes)).performClick()
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.card_no)).performClick()

        assertEquals(listOf("choose 7 No"), done)
    }

    @Test
    fun aHeldGoalsCardHasStopThenContinue() {
        val card = OwnerCard(5, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 5, kind = QuestionKind.CONTINUE_GOAL)
        val view = ConversationView(goal = GoalPhase.ACTING, asking = QuestionKind.CONTINUE_GOAL)
        show(mutableStateOf(ConversationScreenRules.ui(ScreenState(), view, card)))
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)

        // The card carries the goal's buttons (review I1 of Task 7): one Stop, then one Continue.
        compose.onAllNodesWithText(text(R.string.card_stop)).assertCountEquals(1)
        compose.onAllNodesWithText(text(R.string.card_continue)).assertCountEquals(1)
        val stop = compose.onNodeWithText(text(R.string.card_stop)).assertIsDisplayed().getBoundsInRoot()
        val goOn = compose.onNodeWithText(text(R.string.card_continue)).assertIsDisplayed().getBoundsInRoot()
        assertTrue(stop.right <= goOn.left)
        compose.onNodeWithText(text(R.string.card_continue)).performClick()

        assertEquals(listOf("choose 5 Continue"), done)
    }

    // Design 12.2 and 12.3: only the card over the app answers a confirmation; the screen offers to show the app.
    @Test
    fun aConfirmationsCopyOnlyShowsTheApp() {
        val card = OwnerCard(6, "Send it to Mila?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 6, kind = QuestionKind.CONFIRM)
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(), stopAndNew = true, card = card)))
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)

        compose.onNodeWithText("Send it to Mila?").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.card_confirm)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.conversation_show_app)).performClick()

        assertEquals(listOf("show app"), done)
    }

    @Test
    fun sendWaitsForWordsAndTheBannerOpensTheChecklist() {
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.None, stopAndNew = false, card = null)), setupNeeded = true)

        compose.onNodeWithText(text(R.string.conversation_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_send)).assertIsNotEnabled()
        compose.onNodeWithTag(FIELD_TAG).performTextInput("Open Viber")
        compose.onNodeWithText(text(R.string.conversation_send)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.conversation_speak)).performClick()
        compose.onNodeWithText(text(R.string.conversation_setup)).performClick()
        compose.onNodeWithText(text(R.string.conversation_settings)).performClick()

        assertEquals(listOf("send", "speak", "setup", "settings"), done)
    }

    // Design 9.5 of sub-project 7: the top bar's menu opens "Past conversations".
    @Test
    fun theMenuOpensThePastConversations() {
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.None, stopAndNew = false, card = null)))

        compose.onNodeWithText(text(R.string.conversation_more)).performClick()
        compose.onNodeWithText(text(R.string.past_title)).performClick()

        assertEquals(listOf("past"), done)
    }
}
