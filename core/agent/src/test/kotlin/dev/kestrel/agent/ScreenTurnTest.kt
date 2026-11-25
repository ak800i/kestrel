package dev.kestrel.agent

import dev.kestrel.agent.port.AccessKind
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.Tools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The goal's screen turn in the run (design 6.5 of sub-project 7; whole-plan re-review of plan 7D, round 3, R3-1): the
 * owner may use the phone, also with instant commands, only while the run waits for him (a pause, a hand-over, a permission
 * request), so the run takes the turn as each such wait ends, before anything touches the screen again.
 */
class ScreenTurnTest {
    // The access hand-over ends by itself once Android's page has left the front, maybe while the owner's command still
    // acts there; the run opens the task's app again only once the turn is its own.
    @Test
    fun anAccessHandOverThatEndsByItselfOpensTheTasksAppOnlyAfterTheTurn() = runTest {
        val harness = Harness(Screens.chats(), listOf(call(Tools.LIST_NOTIFICATIONS, null, null, null), call("fail", null, null, null, "reason" to "Stop.")))
        harness.data.answer(Tools.LIST_NOTIFICATIONS, DataResult.NeedsAccess(AccessKind.NOTIFICATIONS), DataResult.Ok(emptyList()))
        harness.owner.handOverEnds += HandOverEnd.DONE_BY_ITSELF
        harness.screen.inFront = { false }
        val given = CompletableDeferred<Unit>()
        harness.goal.screenTurn = {
            if (harness.data.calls.size == 1 && !given.isCompleted) {
                given.await()
                true
            } else {
                false
            }
        }

        launch { harness.run("Read my notifications") }
        advanceUntilIdle()
        assertEquals(1, harness.data.calls.size)
        assertTrue(harness.screen.launched.isEmpty())
        given.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.screen.launched.size)
        assertEquals(2, harness.data.calls.size)
    }

    // The observation after a pause closes an app that crashed meanwhile with Back, which is an action on the screen; it
    // waits for the turn, because the owner's command may still act there.
    @Test
    fun theObservationAfterAPauseClosesACrashedAppOnlyAfterTheTurn() = runTest {
        val crashed = ScreenBuilder.screen("android", "Viber keeps stopping") { button("Close app", 300) }
        val harness = Harness(Screens.chats(), listOf(call("fail", null, null, null, "reason" to "Stop.")))
        harness.owner.gates += Gate.RESUMED
        var snapshots = 0
        harness.screen.onSnapshot = { if (++snapshots == 2) harness.screen.current = crashed }
        val given = CompletableDeferred<Unit>()
        harness.goal.screenTurn = {
            if (!given.isCompleted) {
                given.await()
                true
            } else {
                false
            }
        }

        launch { harness.run("Open Ana's chat") }
        advanceUntilIdle()
        assertTrue(harness.screen.actions.none { it is ScreenAction.Global })
        given.complete(Unit)
        advanceUntilIdle()

        assertTrue(harness.screen.actions.any { it is ScreenAction.Global })
    }
}
