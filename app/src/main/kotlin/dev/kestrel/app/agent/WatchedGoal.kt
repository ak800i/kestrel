package dev.kestrel.app.agent

import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.app.conversation.TouchWatch

/**
 * A goal's round with its touch watcher around its screen turn (design D19 of sub-project 5; design 6.5 of sub-project 7):
 * a turn that waited for an instant command starts the watcher again before the round acts, because the command stopped
 * it, as [GoalOwner] does after a pause.
 */
internal class WatchedGoal(private val goal: GoalPort, private val watcher: TouchWatch) : GoalPort by goal {
    override suspend fun takeScreen(): Boolean = goal.takeScreen().also { waited -> if (waited) watcher.watch() }

    override fun toString(): String = "WatchedGoal($goal)"
}
