package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.GoalPort

/**
 * A goal's round as its run sees it (design 6.5 and 7.1 of sub-project 7): the goal, whose screen turn goes through
 * [screen], the one rule for who acts on the screen, with the number of the owner's input that lets the round act now
 * (`RunningGoal.takeAct`) at the first turn after that input, and none at the turns after it. While a turn waits behind
 * the owner's instant command, the goal's listener knows it (plan 7E).
 *
 * It is also the one rule for when a pause lets go of the round's turn (plan 7E). From the moment the run asks for the
 * turn ([takeScreen], at the round's start and as every wait for the owner ends) until its next model call starts
 * ([stepStarted]) or its next wait for the owner starts ([releaseScreen]), a step that a gate let through may act on the
 * screen: its action, the observation after it, and Kestrel's speech. A pause ([paused]) that comes then lets go of the
 * turn only when that span ends, so that no instant command starts beside the step; a pause that comes while the round
 * waits for its model lets go at once, so that the owner's instant commands work during it (plan 7D's re-review, R5-1). A
 * resume ([resumed]) before the span ends keeps the turn, because the goal acts again. Any thread may call [paused] and
 * [resumed].
 */
internal class ScreenTakingGoal(private val goal: RunningGoal, private val screen: ScreenTurn) : GoalPort by goal {
    private val lock = Any()

    // Guarded by lock: whether a step of the run may act on the screen now, and whether a pause waits for it to end.
    private var acting = false
    private var pauseWaits = false

    override suspend fun takeScreen(): Boolean {
        // A pause that comes while the turn is being taken waits too: the run acts right after the take.
        synchronized(lock) { acting = true }
        var behind = false
        try {
            return screen.take(goal.takeAct()) {
                if (!behind) {
                    behind = true
                    goal.listener.waitsBehindCommand(true)
                }
            }
        } finally {
            if (behind) goal.listener.waitsBehindCommand(false)
        }
    }

    // The run's model call comes next, and no step acts on the screen before the next gate takes the turn again.
    override fun stepStarted(step: Int) {
        goal.stepStarted(step)
        settle(release = false)
    }

    override fun releaseScreen() = settle(release = true)

    /** A lock or a call paused the goal (design 6.5 of sub-project 7). */
    fun paused() {
        synchronized(lock) {
            if (acting) pauseWaits = true else screen.release()
        }
    }

    /** The goal goes on after its pause: a release that waited for the step's end is no longer wanted. */
    fun resumed() {
        synchronized(lock) { pauseWaits = false }
    }

    // The span in which a step may act ends: the turn goes when the run lets go of it, or when a pause waited for the end.
    private fun settle(release: Boolean) {
        synchronized(lock) {
            if (release || pauseWaits) screen.release()
            acting = false
            pauseWaits = false
        }
    }

    override fun toString(): String = "ScreenTakingGoal($goal)"
}