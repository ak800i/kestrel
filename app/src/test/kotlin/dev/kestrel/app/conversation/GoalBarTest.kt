package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The feedback bar's line for a goal (design 10.5 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalBarTest {
    @Test
    fun aWorkingGoalShowsItsStepItsChecklistAndItsCurrentItem() {
        val view = ConversationView(goal = GoalPhase.ACTING, step = 12, checklist = ChecklistSummary(3, 7, "Reply to Mila"))

        assertEquals("Step 12 · 3 of 7 · Reply to Mila", GoalBar.text(view))
        assertEquals("Step 2", GoalBar.text(ConversationView(goal = GoalPhase.ACTING, step = 2)))
        assertNull(GoalBar.text(ConversationView(goal = GoalPhase.ACTING)))
    }

    @Test
    fun aHeldGoalSaysHowToGoOnAndQuestionsLeaveTheBarToTheirChannel() {
        assertEquals(GoalBar.ON_HOLD, GoalBar.text(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)))
        assertEquals(GoalBar.ON_HOLD, GoalBar.text(ConversationView(goal = GoalPhase.PAUSED, hold = GoalHold.LOCK)))
        assertNull(GoalBar.text(ConversationView(goal = GoalPhase.ASKING, step = 3, asking = QuestionKind.FREE)))
        assertNull(GoalBar.text(ConversationView(goal = GoalPhase.HANDED_OVER, step = 3)))
        assertNull(GoalBar.text(ConversationView()))
    }

    @Test
    fun theBarShowsEachNewLineOnce() = runTest {
        val views = MutableStateFlow(ConversationView())
        val shown = mutableListOf<String?>()
        val following = launch(UnconfinedTestDispatcher(testScheduler)) { GoalBar.follow(views) { shown += it } }

        views.value = ConversationView(goal = GoalPhase.ACTING, step = 1)
        views.value = ConversationView(goal = GoalPhase.ACTING, step = 1, testMode = false)
        views.value = ConversationView(goal = GoalPhase.ACTING, step = 2)
        views.value = ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)
        views.value = ConversationView()

        // The goal's end passes null on, so that a line that still waits for the bar never shows (review L1).
        assertEquals(listOf(null, "Step 1", "Step 2", GoalBar.ON_HOLD, null), shown)
        following.cancel()
    }
}
