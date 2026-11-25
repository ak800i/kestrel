package dev.kestrel.conversation.machine

/**
 * The quiet-time clock (design 6.2 of sub-project 7): off without an open conversation, held while something holds it,
 * and running since [Running.sinceWall], a wall-clock time, so that a restart can tell whether the conversation is still
 * open.
 */
sealed interface QuietClock {
    data object Off : QuietClock

    data class Held(val why: HoldReason) : QuietClock

    data class Running(val number: Int, val sinceWall: Long) : QuietClock
}

/** What holds the clock: a goal in any phase, a question, a local request, a final reply, typing, or a wait for a command. */
enum class HoldReason { GOAL, QUESTION, WORKING, REPLYING, COMPOSING, WAITING_FOR_COMMAND }

/**
 * The clock's rules (design 6.2 of sub-project 7), which the machine applies after every event: the clock runs only while
 * a conversation is open and nothing holds it, and starts from zero when a hold ends. Locks, calls, listening and the end
 * of the process do not touch it; instant commands never reach the conversation (D15).
 */
internal object QuietRules {
    fun holdOf(state: ConversationState): HoldReason? = when {
        state.goal != null -> HoldReason.GOAL
        state.question != null -> HoldReason.QUESTION
        state.local != null -> HoldReason.WORKING
        state.replying != null -> HoldReason.REPLYING
        state.composing -> HoldReason.COMPOSING
        state.awaiting -> HoldReason.WAITING_FOR_COMMAND
        else -> null
    }

    fun settle(step: MachineStep, now: Long): MachineStep {
        val state = step.state
        if (state.id == null) return if (state.quiet == QuietClock.Off) step else step.copy(state = state.copy(quiet = QuietClock.Off))
        val hold = holdOf(state)
        if (hold != null) return step.copy(state = state.copy(quiet = QuietClock.Held(hold)))
        if (state.quiet is QuietClock.Running) return step
        return start(step, since = now, millis = state.settings.quietMillis)
    }

    /** Runs the clock since [since] with [millis] left, under the next number. */
    fun start(step: MachineStep, since: Long, millis: Long): MachineStep {
        val number = step.state.timers.quiet + 1
        val state = step.state.copy(quiet = QuietClock.Running(number, since), timers = step.state.timers.copy(quiet = number))
        return MachineStep(state, step.effects + ConversationEffect.StartTimer(Timer.QUIET, number, millis))
    }
}
