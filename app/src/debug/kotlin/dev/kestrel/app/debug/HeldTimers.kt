package dev.kestrel.app.debug

import dev.kestrel.conversation.engine.TimerEnd
import dev.kestrel.conversation.engine.TimerSource
import dev.kestrel.conversation.machine.Timer
import kotlinx.coroutines.CompletableDeferred

/**
 * Device tests only: the conversation's timer source (`AppGraph.conversationTimers`; plan 7G) that holds every timer of
 * the [kinds] that it is given until the test ends it, and lets every other timer run its own length. So a test checks
 * what shows while a question waits, with no race against the question's wait, then ends the wait itself, and the
 * conversation's machine ends it as at its deadline. [release] ends every timer that it still holds.
 */
internal class HeldTimers(vararg kinds: Timer) : TimerSource {
    /** One held timer of kind [timer], with the length that the machine gave it. */
    class HeldTimer internal constructor(val timer: Timer, val millis: Long) {
        internal val ended = CompletableDeferred<Unit>()

        /** Ends the timer, as its deadline would. */
        fun end() {
            ended.complete(Unit)
        }
    }

    private val kinds = kinds.toSet()
    private val lock = Any()
    private val held = mutableListOf<HeldTimer>()

    override fun start(timer: Timer, millis: Long): TimerEnd {
        if (timer !in kinds) return TimerEnd.After(millis)
        val started = HeldTimer(timer, millis)
        synchronized(lock) { held += started }
        return TimerEnd.Held(started.ended)
    }

    /** The newest timer of [kind] that this source holds and that has not ended; null when there is none. */
    fun newest(kind: Timer): HeldTimer? = synchronized(lock) { held.lastOrNull { it.timer == kind && !it.ended.isCompleted } }

    /** Ends every timer that this source still holds. */
    fun release() {
        synchronized(lock) { held.toList() }.forEach(HeldTimer::end)
    }
}
