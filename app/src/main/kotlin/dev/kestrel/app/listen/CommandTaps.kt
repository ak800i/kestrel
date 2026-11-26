package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.model.InputIntent

/**
 * How the listening that the owner's assistant taps [taps] asked for ended (section 8.6; plan 7F): with the [input]
 * that his words after "Yes?" gave the conversation, or with none, because the wait ran out, Kestrel turned off, the
 * screen turned off, his words asked for something else, or the taps started no wait at all. The conversation screen's
 * microphone follows the end of its own tap only. toString prints no words.
 */
internal data class CommandWaitEnd(val taps: List<Long>, val input: InputIntent?)

/**
 * Which assistant taps each wait for the owner's command after "Yes?" answers (plan 7F, review M1 of Task 8), so that a
 * tap learns the end of the wait that it started and of no other. The Assist that carries a tap either opens a wait, or
 * joins the one that runs, and the wait's end then carries the tap, also when the owner's "Kestrel" began a new wait in
 * the same stretch; an Assist that leaves Kestrel without a wait, as during a call, an instant command's countdown, a
 * goal that acts, asks or is paused, or while Kestrel is off, ends its taps at once, and so does a start that the taps
 * waited for and that never opened a voice session. Only listening's thread uses it, as the Orchestrator does.
 */
internal class CommandTaps {
    // The taps of the wait that runs, and of the taps that wait for a voice session to open.
    private var waiting = emptyList<Long>()
    private val untilOpen = mutableListOf<Long>()

    /** [tap] waits for the voice session that a start opens; [opened] or [closed] decides its fate. */
    fun awaitSession(tap: Long) {
        untilOpen += tap
    }

    /** The voice session opened: the taps that waited for it, which now need their Assist. */
    fun opened(): List<Long> = untilOpen.toList().also { untilOpen.clear() }

    /** The start or the session ended: the taps that waited for it end without a wait, or null when none did. */
    fun closed(): CommandWaitEnd? = ended(opened(), input = null)

    /** The Orchestrator's [step] from [before] for [event]: the end that it brought to taps, or null. */
    fun stepped(event: Event, before: State, step: Step): CommandWaitEnd? {
        val assisted = (event as? Event.Assist)?.taps.orEmpty()
        val ran = if (before.work == Work.AWAITING_TASK) waiting else emptyList()
        if (step.state.work == Work.AWAITING_TASK) {
            waiting = ran + assisted
            return null
        }
        waiting = emptyList()
        return ended(ran + assisted, step.effects.firstNotNullOfOrNull { (it as? Effect.Converse)?.intent })
    }

    private fun ended(taps: List<Long>, input: InputIntent?): CommandWaitEnd? = if (taps.isEmpty()) null else CommandWaitEnd(taps, input)

    override fun toString(): String = "CommandTaps(waiting=$waiting, untilOpen=$untilOpen)"
}
