package dev.kestrel.app.conversation

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.agent.HeldModel
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.debug.OwnVoiceWatch
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.machine.Timer
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Questions, held goals and typed speech on kestrel-api37 (design 10.3, 10.4, 6.5, 11 and 15.2 of sub-project 7): "Reply…"
 * opens the screen for the typed answer, D21's waits end a question and decline a confirmation (shortened by the
 * instrumentation argument [WAIT_DIVISOR]), a spoken conversation's question shows its card once Kestrel is off, a touch
 * interrupts a typed goal, which the screen's Continue resumes and its Cancel ends, "Continue the goal?" after a lock shows its
 * card and its copy on the screen and its wait leaves the goal held, a hand-over's card continues and cancels the goal on the
 * overlay, and Kestrel's own voice speaks a typed conversation's words when the setting is on and Kestrel is off, and ends a
 * question's spoken copy once the owner has typed its answer.
 */
@RunWith(AndroidJUnit4::class)
class QuestionFlowTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val keyboard = ScreenKeyboard.rule

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val graph get() = TypedTests.graph
    private val conversations get() = graph.conversations

    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val done = call("say", null, null, null, "text" to "Done.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")
    private val ask = call("ask_user", null, null, null, "question" to QUESTION)
    private val delete = call("tap", "Delete chat", null, null, "reason" to "the next step", "category" to "deleting")
    private val notifications = call("list_notifications")

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listener by lazy { KestrelNotificationListener.component(context).flattenToString() }

    // D21's waits, divided by the instrumentation argument (design 15.2 of sub-project 7).
    private val divisor = InstrumentationRegistry.getArguments().getString(WAIT_DIVISOR)?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_DIVISOR

    @After
    fun stop() {
        try {
            TypedTests.finish()
        } finally {
            AgentTests.clearPresses(screen)
            screen.shell("am force-stop $FIXTURES")
        }
    }

    private fun shortenWaits() {
        graph.conversationTimers = { timer, millis -> if (timer == Timer.QUESTION) millis / divisor else millis }
    }

    private fun awaitButton(label: String): Rect {
        var found: Rect? = null
        check(VoiceTests.waitUntil(15_000) { screen.overlayButton(label)?.also { found = it } != null }) { "The card has no button $label" }
        return checkNotNull(found)
    }

    // A card ignores taps for its first 600 ms (design D32 item 21 of sub-project 5).
    private fun tapCard(label: String) {
        val bounds = awaitButton(label)
        Thread.sleep(ARMED_MILLIS)
        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    // The owner types [words] on the screen over the fixture that [open] opens. The model's first reply waits until the
    // screen has left for the goal, so that the question's card shows over the fixture, not inline on the screen.
    private fun typedGoal(model: ScriptedModel, words: String, open: () -> Unit): Int {
        open()
        TypedTests.bringScreen(screen, compose)
        val held = HeldModel(model)
        TypedTests.prepare(held)
        val command = TypedTests.send(compose, words)
        check(VoiceTests.waitUntil(30_000) { held.calls == 1 && !TypedTests.screenInFront(screen) }) { "The screen did not leave for the goal" }
        repeat(REPLIES) { held.release() }
        return command
    }

    @Test
    fun replyOnATypedQuestionsCardOpensTheScreenForTheAnswerAndTheGoalGoesOn() {
        val model = ScriptedModel(listOf(ask, onIt, fail))
        val command = typedGoal(model, "Set a reminder") { screen.open("ViewsActivity", "Views fixture") }
        tapCard(TypedTests.text(R.string.card_reply))
        check(VoiceTests.waitUntil(10_000) { TypedTests.screenInFront(screen) }) { "Reply… did not open the conversation screen" }
        compose.onNodeWithText(TypedTests.text(R.string.card_reply)).assertIsDisplayed()
        compose.onNodeWithTag(FIELD_TAG).assertIsFocused()
        // A screen that Android makes again, as after a rotation, keeps the question that "Reply…" named.
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(FIELD_TAG).assertIsFocused()
        compose.onNodeWithTag(FIELD_TAG).performTextInput("Half past seven")
        TypedTests.tapSend(compose)

        val ended = AgentTests.ended(command)
        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        assertTrue(AgentTests.texts(model).contains("Half past seven"))
        val answered = conversations.view.value.records.single().lines.single { it.kind == LineKind.OWNER_ANSWERED }
        assertEquals(InputChannel.TEXT, answered.channel)
    }

    @Test
    fun aTypedQuestionThatGetsNoAnswerEndsAfterItsWait() {
        shortenWaits()
        val command = typedGoal(ScriptedModel(listOf(ask, fail)), "Set a reminder") { screen.open("ViewsActivity", "Views fixture") }
        awaitButton(TypedTests.text(R.string.card_reply))
        val shown = SystemClock.elapsedRealtime()
        val ended = AgentTests.ended(command, timeoutMillis = QUESTION_MILLIS / divisor + 60_000)
        val waited = SystemClock.elapsedRealtime() - shown

        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        assertTrue("The question ended after $waited ms", waited >= QUESTION_MILLIS / divisor - SLACK_MILLIS)
        assertNull(screen.overlayButton(TypedTests.text(R.string.card_reply)))
    }

    @Test
    fun aTypedConfirmationThatGetsNoAnswerIsDeclinedAfterItsWait() {
        shortenWaits()
        AgentTests.clearPresses(screen)
        val command = typedGoal(ScriptedModel(listOf(delete, fail)), "Delete my chat with Ana") {
            screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")
        }
        awaitButton(TypedTests.text(R.string.card_confirm))
        val ended = AgentTests.ended(command, timeoutMillis = 60_000)

        assertEquals(EndReason.DECLINED, ended.report.end)
        assertEquals(emptyList<String>(), AgentTests.presses(screen))
    }

    // Plan 7B's decision 10: while Kestrel is off, a spoken conversation's question shows its card too.
    @Test
    fun whileKestrelIsOffASpokenConversationsQuestionShowsItsCard() {
        screen.open("ViewsActivity", "Views fixture")
        AgentTests.start()
        try {
            val command = AgentTests.run("set a reminder", ScriptedModel(listOf(ask, fail)))
            conversations.view.value.id?.let(TypedTests::own)
            AgentTests.awaitAsking(command, QuestionKind.FREE)
            assertNull(screen.overlayButton(TypedTests.text(R.string.card_cancel)))

            AgentTests.turnOff()
            tapCard(TypedTests.text(R.string.card_cancel))

            assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
        } finally {
            AgentTests.stop()
        }
    }

    @Test
    fun aTouchInterruptsATypedGoalAndTheScreensContinueResumesIt() {
        val model = interruptedGoal()

        TypedTests.bringScreen(screen, compose)
        compose.onNodeWithText(TypedTests.text(R.string.conversation_continue)).performClick()

        // The goal goes on from its next step: only now does the model get its second request, and the screen leaves for it.
        check(VoiceTests.waitUntil(30_000) { model.calls == 2 }) { "The goal did not go on" }
        check(VoiceTests.waitUntil(10_000) { !TypedTests.screenInFront(screen) }) { "The screen did not leave for the goal" }
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        assertEquals(EndReason.FAILED_BY_MODEL, conversations.outcome(conversations.view.value.command)?.report?.end)
        assertTrue(conversations.view.value.records.single().lines.any { it.kind == LineKind.OWNER_CONTINUED && it.channel == InputChannel.TEXT })
    }

    @Test
    fun cancelOnTheScreenEndsAHeldGoal() {
        interruptedGoal()

        TypedTests.bringScreen(screen, compose)
        compose.onNodeWithText(TypedTests.text(R.string.conversation_cancel)).performClick()

        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "Cancel did not end the goal" }
        assertTrue(TypedTests.screenInFront(screen))
    }

    // Design 6.5, 10.3 and D21: after a lock, a typed goal's "Continue the goal?" shows its card over the app and its copy on
    // the screen; with no answer its wait ends, and the goal stays held at its next step.
    @Test
    fun continueTheGoalShowsItsCardAndItsCopyAndTheGoalStaysHeldAfterItsWait() {
        shortenWaits()
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen, compose)
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        TypedTests.prepare(model)
        TypedTests.send(compose, "Look at the fixture")
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && !TypedTests.screenInFront(screen) }) { "The goal did not start on the fixture" }
        try {
            screen.shell("input keyevent KEYCODE_SLEEP")
            check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The lock did not pause the goal" }
            model.release()
        } finally {
            wake()
        }
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.asking == QuestionKind.CONTINUE_GOAL }) { "No \"Continue the goal?\" after the unlock" }
        awaitButton(TypedTests.text(R.string.card_continue))

        TypedTests.bringScreen(screen, compose)
        // While the goal is held the screen's own row says Cancel and Continue; Stop is the card's.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(TypedTests.text(R.string.card_stop)).fetchSemanticsNodes().isNotEmpty() }
        check(VoiceTests.waitUntil(QUESTION_MILLIS / divisor + 30_000) { conversations.view.value.asking == null }) { "The question did not end after its wait" }

        compose.waitUntil(5_000) { compose.onAllNodesWithText(TypedTests.text(R.string.card_stop)).fetchSemanticsNodes().isEmpty() }
        assertEquals(GoalPhase.PAUSED, conversations.view.value.goal)
        assertEquals(1, model.calls)
    }

    // Design 10.3: a hand-over's card on the overlay; its Continue lets the goal go on, and its Cancel ends the goal.
    @Test
    fun aHandOversCardContinuesAndCancelsTheGoalOnTheOverlay() {
        val hadAccess = KestrelNotificationListener.hasAccess(context)
        screen.shell("cmd notification disallow_listener $listener")
        try {
            check(VoiceTests.waitUntil(10_000) { !KestrelNotificationListener.connected.value }) { "The listener did not disconnect" }
            val model = ScriptedModel(listOf(notifications, notifications, fail))
            val command = typedGoal(model, "Read my notifications") { screen.open("MessengerActivity", "Chats") }

            tapCard(TypedTests.text(R.string.card_continue))
            // The tool ran again without access, and the model's next reply asked for it once more.
            check(VoiceTests.waitUntil(30_000) { model.requests.size == 2 }) { "The goal did not go on after Continue" }
            tapCard(TypedTests.text(R.string.card_cancel))

            assertEquals(EndReason.CANCELLED, AgentTests.ended(command).report.end)
            assertEquals(2, model.requests.size)
        } finally {
            screen.shell("cmd notification ${if (hadAccess) "allow_listener" else "disallow_listener"} $listener")
        }
    }

    @Test
    fun aTypedConversationsWordsAreSpokenWithKestrelsOwnVoiceWhenTheSettingIsOnAndKestrelIsOff() = runBlocking {
        VoiceTests.assumeModels(Engine.TTS)
        val stored = graph.settings.get(SettingKeys.CONVERSATIONS)
        graph.settings.set(SettingKeys.CONVERSATIONS, stored.copy(speakTypedReplies = true))
        // The one count of what Kestrel's own voice spoke (the whole-plan review's M4): a refused reply is not heard.
        val watch = OwnVoiceWatch { false }
        graph.ownVoicePlayback = watch::wrap
        try {
            screen.open("ViewsActivity", "Views fixture")
            TypedTests.bringScreen(screen, compose)
            TypedTests.prepare(ScriptedModel(listOf(onIt, fail)))

            AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))

            // "On it." (design 11): spoken by Kestrel's own voice, because Kestrel is off.
            check(VoiceTests.waitUntil(60_000) { watch.ended(Spoke.FINISHED) > 0 }) { "Kestrel's own voice did not speak the goal's words" }
        } finally {
            graph.ownVoicePlayback = null
            graph.settings.set(SettingKeys.CONVERSATIONS, stored)
        }
    }

    // Plan 7F: a typed question's spoken copy ends with the question. The owner's typed answer, given while Kestrel's own
    // voice speaks the copy, ends that copy at once (the conversation cancels it, and the voice stops that reply alone,
    // which then does not count as spoken), and the replies after it are still spoken and heard. The watch on Kestrel's own
    // voice holds the copy until a stop ends it, so that it still plays when the answer comes however long its audio is,
    // and it reads only Kestrel's own player. The model's next reply waits, so that nothing else plays meanwhile.
    @Test
    fun aTypedAnswerEndsItsQuestionsSpokenCopyAndTheNextRepliesAreStillSpoken() = runBlocking {
        VoiceTests.assumeModels(Engine.TTS)
        val stored = graph.settings.get(SettingKeys.CONVERSATIONS)
        graph.settings.set(SettingKeys.CONVERSATIONS, stored.copy(speakTypedReplies = true))
        val watch = OwnVoiceWatch { text -> QUESTION in text }
        graph.ownVoicePlayback = watch::wrap
        try {
            screen.open("ViewsActivity", "Views fixture")
            TypedTests.bringScreen(screen, compose)
            val scripted = ScriptedModel(listOf(ask, onIt, done, fail))
            val model = HeldModel(scripted)
            TypedTests.prepare(model)
            val command = TypedTests.send(compose, "Set a reminder")
            check(VoiceTests.waitUntil(30_000) { model.calls == 1 && !TypedTests.screenInFront(screen) }) { "The screen did not leave for the goal" }
            model.release()

            tapCard(TypedTests.text(R.string.card_reply))
            check(VoiceTests.waitUntil(10_000) { TypedTests.screenInFront(screen) }) { "Reply… did not open the conversation screen" }
            compose.onNodeWithTag(FIELD_TAG).performTextInput("Half past seven")
            check(VoiceTests.waitUntil(60_000) { watch.holding }) { "Kestrel's own voice did not speak the question" }
            // Kestrel's own voice speaks one reply at a time, so that every reply before the question's copy has ended.
            val heardBefore = watch.ended(Spoke.FINISHED)
            val stoppedBefore = watch.ended(Spoke.STOPPED)
            TypedTests.tapSend(compose)

            check(VoiceTests.waitUntil(COPY_END_MILLIS) { !watch.holding && !watch.playing }) { "The question's spoken copy went on after the answer" }
            check(VoiceTests.waitUntil(30_000) { model.calls == 2 }) { "The answer did not reach the goal" }
            // The answer ended the copy with a stop: it never counted as heard.
            assertEquals(heardBefore, watch.ended(Spoke.FINISHED))
            assertEquals(stoppedBefore + 1, watch.ended(Spoke.STOPPED))
            val heard = watch.ended(Spoke.FINISHED)

            repeat(REPLIES) { model.release() }
            assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
            assertTrue(AgentTests.texts(scripted).contains("Half past seven"))
            // "On it." and "Done.", which Kestrel's own player played to their end after the copy that the answer ended.
            check(VoiceTests.waitUntil(60_000) { watch.ended(Spoke.FINISHED) >= heard + 2 }) { "Kestrel's own voice did not speak the replies after the answer" }
        } finally {
            graph.ownVoicePlayback = null
            watch.release()
            graph.settings.set(SettingKeys.CONVERSATIONS, stored)
        }
    }

    // A typed goal on the fixture whose first model reply waits, and the owner's touch, which interrupts it (D13; the
    // conversation takes the touch as the touch watcher gives it, as in plan 7E's GoalServiceTest).
    // Once that reply goes, the goal stays held at its next step and asks the model nothing more (plan 7D's GoalPauseTest).
    private fun interruptedGoal(): HeldModel {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen, compose)
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        TypedTests.prepare(model)
        TypedTests.send(compose, "Look at the fixture")
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && !TypedTests.screenInFront(screen) }) { "The goal did not start on the fixture" }

        conversations.touched()

        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.INTERRUPTED }) { "The touch did not interrupt the goal" }
        assertFalse(TypedTests.screenInFront(screen))
        model.release()
        Thread.sleep(HELD_MILLIS)
        assertEquals(GoalPhase.INTERRUPTED, conversations.view.value.goal)
        assertEquals(1, model.calls)
        return model
    }

    private fun wake() {
        screen.shell("input keyevent KEYCODE_WAKEUP")
        screen.shell("wm dismiss-keyguard")
    }

    private companion object {
        /** The instrumentation argument that divides D21's waits; 60 when it is not given. */
        const val WAIT_DIVISOR = "kestrelWaitDivisor"
        const val DEFAULT_DIVISOR = 60L

        /** D21's wait for a question card. */
        const val QUESTION_MILLIS = 2L * 60 * 1000

        /** How much earlier than its wait a question may seem to end, because the card shows a moment after the question opens. */
        const val SLACK_MILLIS = 1_000L

        /** A little more than the 600 ms in which a card ignores taps. */
        const val ARMED_MILLIS = CARD_ARMING_MILLIS + 100

        /** More releases than any test's script has replies: the held model lets every reply go at once. */
        const val REPLIES = 5

        /** How long the test watches a held goal stay held. */
        const val HELD_MILLIS = 3_000L

        /** The question that the scripted model asks. */
        const val QUESTION = "Which time?"

        /** How soon after the tap on Send the question's spoken copy must have ended. */
        const val COPY_END_MILLIS = 1_500L
    }
}
