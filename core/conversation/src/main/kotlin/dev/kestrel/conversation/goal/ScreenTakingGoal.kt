package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.GoalPort

/**
 * A goal's round as its run sees it (design 6.5 and 7.1 of sub-project 7): the goal, whose screen turn goes through
 * [screen], the one rule for who acts on the screen, with the number of the owner's input that lets the round act now
 * (`RunningGoal.takeAct`) at the first turn after that input, and none at the turns after it.
 */
internal class ScreenTakingGoal(private val goal: RunningGoal, private val screen: ScreenTurn) : GoalPort by goal {
    override suspend fun takeScreen(): Boolean = screen.take(goal.takeAct())

    override fun toString(): String = "ScreenTakingGoal($goal)"
}
