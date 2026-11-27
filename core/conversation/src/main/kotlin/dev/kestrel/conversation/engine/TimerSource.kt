package dev.kestrel.conversation.engine

import dev.kestrel.conversation.machine.Timer
import kotlinx.coroutines.Deferred

/**
 * How each timer that the conversation's machine starts ends (design 6.2 of sub-project 7). [REAL], the engine's own and
 * the only one in release builds, ends it once its length has passed on the clock that counts sleep. A device test's
 * source can hold a timer instead and end it when the test chooses (plan 7G), so that a check of what shows while a
 * question waits never races the question's wait.
 */
fun interface TimerSource {
    /** How [timer], which the machine starts for [millis], ends. */
    fun start(timer: Timer, millis: Long): TimerEnd

    companion object {
        /** Every timer ends after its own length. */
        val REAL = TimerSource { _, millis -> TimerEnd.After(millis) }
    }
}

/** How one timer ends. */
sealed interface TimerEnd {
    /** After [millis] on the clock that counts sleep. */
    data class After(val millis: Long) : TimerEnd

    /** When [ended] completes, however long that takes. A timer that the machine replaced before that changes nothing. */
    class Held(val ended: Deferred<Unit>) : TimerEnd
}
