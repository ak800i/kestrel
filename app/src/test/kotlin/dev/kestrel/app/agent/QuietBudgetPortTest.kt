package dev.kestrel.app.agent

import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.GlobalKind
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.read.QuietBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class QuietBudgetPortTest {
    // The app's screen, which notes the quiet budget of each call.
    private class Screen : ScreenPort {
        val budgets = mutableMapOf<String, MutableList<QuietBudget?>>()

        private suspend fun note(call: String) {
            budgets.getOrPut(call) { mutableListOf() } += currentCoroutineContext()[QuietBudget]
        }

        override suspend fun snapshot(): ScreenSnapshot? = null.also { note("snapshot") }

        override suspend fun act(action: ScreenAction): ActionOutcome = ActionOutcome(done = true).also { note("act") }

        override suspend fun resolve(intent: IntentSpec): String? = null

        override suspend fun handlers(intent: IntentSpec): Set<String> = emptySet()

        override suspend fun showBadges(badges: List<Badge>) = Unit

        override suspend fun hideBadges() = Unit

        override suspend fun waitForFront(packageName: String?, millis: Long, before: String?): Boolean = true.also { note("front") }
    }

    @Test
    fun eachActionHasABudgetOfItsOwnAndTheObservationWaitsOutsideIt() = runTest {
        val screen = Screen()
        val port = QuietBudgetPort(screen)
        val back = ScreenAction.Global(GlobalKind.BACK)

        assertEquals(ActionOutcome(done = true), port.act(back))
        port.snapshot()
        port.waitForFront("com.example", 1_000)
        port.act(back)

        val acts = screen.budgets.getValue("act")
        assertNotNull(acts[0])
        assertNotNull(acts[1])
        assertNotSame(acts[0], acts[1])
        assertNull(screen.budgets.getValue("snapshot").single())
        assertNull(screen.budgets.getValue("front").single())
    }
}
