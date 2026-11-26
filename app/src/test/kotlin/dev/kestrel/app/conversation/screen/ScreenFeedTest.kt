package dev.kestrel.app.conversation.screen

import dev.kestrel.app.conversation.SentInput
import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.app.listen.CommandWaitEnd
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What feeds the conversation screen's model, and what its effects reach (plan 7F). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenFeedTest {
    private val id = ConversationId("0".repeat(32))
    private val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
    private val acting = idle.copy(command = 2, goal = GoalPhase.ACTING)
    private val model = ConversationScreenModel { 0L }
    private val view = MutableStateFlow(idle)
    private val foreground = MutableStateFlow(ForegroundState())
    private val sent = MutableSharedFlow<SentInput>()
    private val waitEnds = MutableSharedFlow<CommandWaitEnd>()
    private val submitted = mutableListOf<InputIntent>()
    private val feed = ScreenFeed(model, view, foreground, sent, waitEnds, submitted::add)
    private var leaves = 0

    private fun TestScope.started(collectLeaves: Boolean = true) {
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        feed.start(CoroutineScope(backgroundScope.coroutineContext + unconfined))
        if (collectLeaves) backgroundScope.launch(unconfined) { feed.leaves.collect { leaves++ } }
    }

    // Review of Task 6: the model's own inputs reach the conversation while the model lives, also while no screen collects
    // the leaves, so that no "New conversation" waits for a screen that comes back and closes a conversation started since.
    @Test
    fun theModelsInputsReachTheConversationWhileNoScreenCollectsItsLeaves() = runTest {
        started(collectLeaves = false)
        model.inFront(true)

        model.newConversation()
        runCurrent()

        assertEquals(listOf<InputIntent>(InputIntent.NewConversation), submitted)
    }

    private fun TestScope.goalActs() {
        view.value = acting
        foreground.value = ForegroundState(GoalPhase.ACTING, running = true, settled = true)
        runCurrent()
    }

    // Review M1 of Task 6: a tap on the microphone whose listening heard no command is over, so that a goal that the owner
    // starts later by voice without the screen does not move it. Review M1 of Task 8: also a tap that started no wait,
    // whose listening ends at once.
    @Test
    fun aTapOnTheMicrophoneWhoseListeningEndsWithoutACommandLetsNoLaterGoalMoveTheScreen() = runTest {
        started()
        model.inFront(true)
        model.listened { 7 }

        waitEnds.emit(CommandWaitEnd(listOf(7), null))
        goalActs()

        assertEquals(0, leaves)
    }

    // Review M1 of Task 8: the end of an older wait, such as one that "Kestrel" began by voice, that comes after the tap
    // is not the end of the tap's listening, so the tap's command still moves the screen.
    @Test
    fun theEndOfAnotherListeningAfterTheTapKeepsItsArm() = runTest {
        started()
        model.inFront(true)
        model.listened { 7 }

        waitEnds.emit(CommandWaitEnd(listOf(6), null))
        goalActs()

        assertEquals(1, leaves)
    }

    @Test
    fun aTapOnTheMicrophoneWhoseWaitEndsWithACommandLeavesForItsGoal() = runTest {
        started()
        model.inFront(true)
        model.listened { 7 }

        waitEnds.emit(CommandWaitEnd(listOf(7), InputIntent.Command("Open the fixture")))
        goalActs()

        assertEquals(1, leaves)
    }

    // Review of Task 7: each return to the front is a new visit, on which the card's buttons arm again, so that the owner
    // who comes back never hits a button that armed while he was away; a second report of the same stay is no new visit.
    @Test
    fun eachReturnToTheFrontIsANewVisit() {
        feed.inFront(true)
        feed.inFront(true)
        assertEquals(1, feed.visits.value)

        feed.inFront(false)
        feed.inFront(true)

        assertEquals(2, feed.visits.value)
    }
}
