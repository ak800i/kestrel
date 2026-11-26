package dev.kestrel.app.conversation.screen

import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The conversation screen's one reducer (plan 7F): no order of its inputs keeps the screen in front of a working goal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationScreenModelTest {
    private val id = ConversationId("0".repeat(32))
    private val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
    private val acting = idle.copy(command = 2, goal = GoalPhase.ACTING)
    private val inForeground = ForegroundState(GoalPhase.ACTING, running = true, settled = true)
    private val model = ConversationScreenModel { 0L }
    private val effects = mutableListOf<ScreenEffect>()

    private fun TestScope.collecting() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
    }

    // Review A2: the view and the service changed before the screen heard of the owner's input.
    @Test
    fun theScreenLeavesWhenTheOwnersInputArrivesAfterTheGoalAndItsServiceDid() = runTest {
        collecting()
        model.inFront(true)
        model.viewed(idle)

        model.viewed(acting)
        model.foreground(inForeground)
        assertEquals(emptyList<ScreenEffect>(), effects)
        model.sent(InputIntent.Command("Open Viber"))

        assertEquals(listOf(ScreenEffect.Leave), effects)
        assertNull(model.state.value.armedAt)
    }

    @Test
    fun anInputWhileTheScreenIsNotInFrontArmsNothingAndLeavingTheFrontDisarms() = runTest {
        collecting()
        model.viewed(idle)
        model.sent(InputIntent.Command("Open Viber"))
        assertNull(model.state.value.armedAt)

        model.inFront(true)
        model.sent(InputIntent.Command("Open Viber"))
        assertEquals(1, model.state.value.armedAt)
        model.inFront(false)
        model.viewed(acting)
        model.foreground(inForeground)

        assertEquals(emptyList<ScreenEffect>(), effects)
    }

    @Test
    fun stopAndStartNewStopsTheGoalAndThenStartsANewConversationOnce() = runTest {
        collecting()
        model.inFront(true)
        model.viewed(acting)

        model.newConversation()
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.Stop)), effects)
        model.viewed(acting.copy(goal = null))
        model.viewed(acting.copy(goal = null, step = 1))

        assertEquals(listOf(ScreenEffect.Submit(InputIntent.Stop), ScreenEffect.Submit(InputIntent.NewConversation)), effects)
    }
}
