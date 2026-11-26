package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.model.InputIntent

/**
 * How a wait for the owner's command after "Yes?" ended (section 8.3): with the [input] that his words gave the
 * conversation, or with none, because the wait ran out, Kestrel turned off, the screen turned off, or his words asked for
 * something else. The conversation screen's microphone follows it (plan 7F): its tap's act is over once the wait that the
 * tap started ended without a command. toString prints no words.
 */
internal data class CommandWaitEnd(val input: InputIntent?) {
    companion object {
        /** The end of the wait that the step from [before] to [step] brought, or null when no wait ended in it. */
        fun of(before: State, step: Step): CommandWaitEnd? {
            if (before.work != Work.AWAITING_TASK || step.state.work == Work.AWAITING_TASK) return null
            return CommandWaitEnd(step.effects.firstNotNullOfOrNull { (it as? Effect.Converse)?.intent })
        }
    }
}
