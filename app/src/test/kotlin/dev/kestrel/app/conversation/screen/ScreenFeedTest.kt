package dev.kestrel.app.conversation.screen

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
    private val sent = MutableSharedFlow<InputIntent>()
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

    // Review M1 of Task 6: a tap on the microphone whose listening heard no command is over, so that a goal that the owner
    // starts later by voice without the screen does not move it.
    @Test
    fun aTapOnTheMicrophoneWhoseWaitEndsWithoutACommandLetsNoLaterGoalMoveTheScreen() = runTest {
        started()
        model.inFront(true)
        model.listened()

        waitEnds.emit(CommandWaitEnd(null))
        view.value = acting
        foreground.value = ForegroundState(GoalPhase.ACTING, running = true, settled = true)
        runCurrent()

        assertEquals(0, leaves)
    }

    @Test
    fun aTapOnTheMicrophoneWhoseWaitEndsWithACommandLeavesForItsGoal() = runTest {
        started()
        model.inFront(true)
        model.listened()

        waitEnds.emit(CommandWaitEnd(InputIntent.Command("Open the fixture")))
        view.value = acting
        foreground.value = ForegroundState(GoalPhase.ACTING, running = true, settled = true)
        runCurrent()

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
