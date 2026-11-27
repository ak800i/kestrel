package dev.kestrel.app.goal

import dev.kestrel.app.conversation.InputIds

/**
 * Takes the number among the owner's inputs of a tap on the goal notification's button [GoalAction] for the notice of
 * [NoticeGoal], as the tap's broadcast arrives (`GoalActionReceiver`; design 5.3 of sub-project 7). Kestrel's own form is
 * [PLAIN], the next number. The debug builds install a form that also notes a number as the debug hooks' when their own
 * press in the shade sent the broadcast (plan 7G), and then still returns the next number.
 */
internal fun interface ButtonActs {
    fun take(action: GoalAction?, goal: NoticeGoal?): Long

    companion object {
        val PLAIN = ButtonActs { _, _ -> InputIds.next() }
    }
}
