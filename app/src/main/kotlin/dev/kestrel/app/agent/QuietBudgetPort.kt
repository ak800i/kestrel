package dev.kestrel.app.agent

import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.screen.read.withQuietBudget

/**
 * The agent's screen with the quiet budget of design spec, section 9.3: each action is the agent's command, so its waits
 * for a quiet screen (the snapshot that it checks the element against, the settle wait, a verified press's second look)
 * share one budget. The observation after it, [snapshot], and [waitForFront] wait with budgets of their own, because the
 * agent judges the step on that screen, and a budget that the action had spent would hand the model a screen in the
 * middle of the action's own animation.
 */
internal class QuietBudgetPort(private val screen: ScreenPort) : ScreenPort by screen {
    override suspend fun act(action: ScreenAction): ActionOutcome = withQuietBudget { screen.act(action) }

    override fun toString(): String = "QuietBudgetPort($screen)"
}
