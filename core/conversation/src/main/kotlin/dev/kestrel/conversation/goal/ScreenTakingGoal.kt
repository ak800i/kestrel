package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.GoalPort

/**
 * A goal's round as its run sees it (design 6.5 and 7.1 of sub-project 7): the goal, whose screen turn goes through
 * [screen], the one rule for who acts on the screen, with the number of the owner's input that lets the round act now
 * (`RunningGoal.takeAct`) at the first turn after that input, and none at the turns after it. While a turn waits behind
 * the owner's instant command, the goal's listener knows it (plan 7E).
 *
 * It is also the one rule for when a pause lets go of the round's turn (plan 7E; plan 7D's re-review, R5-1). Two states
 * decide it, under one lock:
 * - the goal is paused: from [paused], which a lock or a call brings, until [resumed];
 * - a step may act: from the moment the run asks for the turn ([takeScreen], at the round's start and as every wait for
 *   the owner ends) until its next model call starts ([stepStarted]) or its next wait for the owner starts
 *   ([releaseScreen]). In that span the step that a gate let through acts on the screen: its action, the observation
 *   after it, and Kestrel's speech.
 *
 * The rule: while the goal is paused, the round holds the turn only while a step may act, so that no instant command
 * starts beside the step. Every change of either state applies it:
 * - a pause while no step may act (a model call, or a wait for the owner) lets go at once;
 * - a pause while a step may act, also one that came after a gate let the run through and before its take, lets go when
 *   the span ends, at the next model call at the latest; a take while paused holds the turn only for that span;
 * - a resume clears the pause, so a later end of the span keeps the turn; the engine resumes before the owner's gate opens,
 *   so the take after a paused gate is not paused, and a pause and resume within one span keep the turn;
 * - the run's own release ([releaseScreen]) lets go in every state, at every wait for the owner and at the round's end.
 *
 * Its round's runner makes it before the round first runs, so every pause during the round reaches it. Any thread may
 * call [paused] and [resumed].
 */
internal class ScreenTakingGoal(private val goal: RunningGoal, private val screen: ScreenTurn) : GoalPort by goal {
    private val lock = Any()

    // Guarded by lock.
    private var paused = false
    private var acting = false

    override suspend fun takeScreen(): Boolean {
        // A pause that comes while the turn is being taken waits for the span's end too: the run acts right after the take.
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
        synchronized(lock) {
            acting = false
            letGoWhilePaused()
        }
    }

    override fun releaseScreen() {
        synchronized(lock) {
            acting = false
            screen.release()
        }
    }

    /** A lock or a call paused the goal (design 6.5 of sub-project 7). */
    fun paused() {
        synchronized(lock) {
            paused = true
            letGoWhilePaused()
        }
    }

    /** The goal goes on after its pause, so the end of the span keeps the turn again. */
    fun resumed() {
        synchronized(lock) { paused = false }
    }

    // The rule, called under the lock after every change of the two states.
    private fun letGoWhilePaused() {
        if (paused && !acting) screen.release()
    }

    override fun toString(): String = "ScreenTakingGoal($goal)"
}
