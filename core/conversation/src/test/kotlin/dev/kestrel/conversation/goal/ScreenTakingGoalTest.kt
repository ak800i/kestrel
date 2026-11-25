package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.conversation.model.RoundEnd
import dev.kestrel.conversation.testing.FakeScreenTurn
import dev.kestrel.safety.Category
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a pause lets go of a goal's round's screen turn (design 6.5 of sub-project 7; plan 7E): once no step that a gate let
 * through can act on the screen any more, and at once while the round waits for its model (plan 7D's re-review, R5-1).
 */
class ScreenTakingGoalTest {
    private val screen = FakeScreenTurn()
    private val steps = mutableListOf<Int>()
    private val listener = object : GoalListener {
        override fun stepStarted(step: Int) {
            steps += step
        }

        override suspend fun traced(entry: TraceEntry) = Unit

        override suspend fun checklist(checklist: Checklist) = Unit

        override fun allowed(categories: Set<Category>) = Unit

        override fun steeringChanged() = Unit

        override fun touched() = Unit

        override fun ended(end: RoundEnd) = Unit
    }
    private val taking = ScreenTakingGoal(RunningGoal(1, 1, emptyMap(), emptyList(), listener), screen)

    // The controller's decision of 2026-11-25: a pause while a step that its gate let through may still act keeps the turn,
    // so that no instant command starts beside the step; the turn goes when the step has ended, which is the run's next model
    // call or its next wait for the owner.
    @Test
    fun aPauseWhileAStepMayActLetsGoOfTheTurnOnlyWhenTheStepHasEnded() = runTest {
        taking.takeScreen()
        taking.paused()
        assertTrue(screen.holds)
        taking.stepStarted(2)
        assertFalse(screen.holds)
        assertEquals(listOf(2), steps)

        taking.takeScreen()
        taking.paused()
        assertTrue(screen.holds)
        taking.releaseScreen()
        assertFalse(screen.holds)
        assertEquals(2, screen.releases)
    }

    // Plan 7D's re-review, R5-1: a pause while the round waits for its model lets go at once, so that the owner's instant
    // commands work during it. A resume before the step's end keeps the turn, because the goal acts again.
    @Test
    fun aPauseDuringAModelCallLetsGoAtOnceAndAResumeBeforeTheStepsEndKeepsTheTurn() = runTest {
        taking.takeScreen()
        taking.stepStarted(1)
        assertTrue(screen.holds)
        taking.paused()
        assertFalse(screen.holds)

        taking.takeScreen()
        taking.paused()
        taking.resumed()
        taking.stepStarted(2)
        assertTrue(screen.holds)
    }
}
