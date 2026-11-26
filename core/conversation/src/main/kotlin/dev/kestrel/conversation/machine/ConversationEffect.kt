package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput

/** What the engine does for the machine, in order (design 6.3 of sub-project 7). toString prints no words. */
sealed interface ConversationEffect {
    /** Creates record [record] for [text] and runs the goal's first round as [command]. */
    data class StartGoal(val command: Int, val record: Int, val input: OwnerInput, val text: String) : ConversationEffect {
        override fun toString(): String = "StartGoal(command=$command, record=$record, input=$input, text=${text.length} chars)"
    }

    /**
     * Runs a held goal's next round as [command], after [cause], with [steer] as its first steering. [input] is the owner's
     * input that resumed it, whose options the round takes; null when he answered "Continue the goal?" on its card, and
     * [tap] is then the number of his tap (plan 7F).
     */
    data class ResumeGoal(val command: Int, val cause: ResumeCause, val steer: Steering?, val input: OwnerInput?, val tap: Long? = null) : ConversationEffect

    /**
     * The owner's steering of the acting goal (design 7.3). The engine says "Got it." once the goal's inbox took it, and at
     * once that it came too late when the goal had given its final words already.
     */
    data class SteerGoal(val text: String, val channel: InputChannel) : ConversationEffect {
        override fun toString(): String = "SteerGoal(${text.length} chars, channel=$channel)"
    }

    /** A lock or a call: the owner port withdraws the goal's question and closes its gate. */
    data object PauseGoal : ConversationEffect

    /**
     * The paused round goes on with a fresh look: for the owner's [act], or by itself (null), as when a hand-over that the
     * round opened while a lock or a call paused it wins.
     */
    data class ResumePausedGoal(val act: OwnerAct?) : ConversationEffect

    /** Cancels the round's job as interrupted: the goal stays open and held (D13). */
    data class InterruptGoal(val cause: Interruption) : ConversationEffect

    /** Cancels the round's job with [reason]; [sentence] replaces the report's reason, as the call backstop's does. */
    data class CancelGoal(val reason: EndReason, val sentence: String? = null) : ConversationEffect {
        override fun toString(): String = "CancelGoal(reason=$reason, sentence=${sentence?.length})"
    }

    /** Ends a held goal, whose round has ended already, with [reason] and its partial report; [sentence] as [CancelGoal]'s. */
    data class EndHeldGoal(val reason: EndReason, val sentence: String? = null) : ConversationEffect {
        override fun toString(): String = "EndHeldGoal(reason=$reason, sentence=${sentence?.length})"
    }

    /** Answers question [number]; [leftScreen] when the owner gave the answer on Kestrel's own screen, a typed input (design 10.3). */
    data class AnswerQuestion(val number: Int, val answer: ChannelAnswer, val leftScreen: Boolean = false) : ConversationEffect

    /** Ends hand-over [number] with [end]: for the owner's [act] ("Kestrel, continue"), or by itself (null), as at its 3 minutes. */
    data class EndHandOver(val number: Int, val end: HandOverEnd, val act: OwnerAct? = null) : ConversationEffect

    /** Asks "Continue the goal?" (design 6.5 and 6.7). */
    data object AskContinueGoal : ConversationEffect

    /** Withdraws "Continue the goal?". */
    data object CancelContinueQuestion : ConversationEffect

    data class Output(val output: ChannelOutput) : ConversationEffect

    /**
     * Adds a line to record [record] of conversation [conversation]; an owner's line is redacted into its record's form
     * first. The record is named with its conversation, because the step that adds the line may also close it.
     */
    data class AddLine(val conversation: ConversationId, val record: Int, val kind: LineKind, val text: String, val channel: InputChannel?) : ConversationEffect {
        override fun toString(): String = "AddLine(record=$record, kind=$kind, text=${text.length} chars, channel=$channel)"
    }

    /**
     * Record [record] of conversation [conversation]: its goal or request ended with [end] and its final words [answer]. A
     * step that ends a goal and closes its conversation gives this before the close, which stores the record ended.
     */
    data class EndRecord(val conversation: ConversationId, val record: Int, val end: EndReason, val answer: String?) : ConversationEffect {
        override fun toString(): String = "EndRecord(record=$record, end=$end, answer=${answer?.length})"
    }

    /** Runs Kestrel's own [request] as [command] for the owner's [input]; [record] is a memory request's new record. */
    data class RunLocal(val command: Int, val record: Int?, val request: LocalRequest, val input: OwnerInput) : ConversationEffect

    data object CancelLocal : ConversationEffect

    /**
     * Kestrel's own request [command] for the owner's [input] (a local request, a switch or "new conversation") ended with
     * [end] and its final words [answer], for the hooks' outcome (design 14.4).
     */
    data class EndLocal(val command: Int, val end: EndReason, val answer: String?, val input: OwnerInput) : ConversationEffect {
        override fun toString(): String = "EndLocal(command=$command, end=$end, answer=${answer?.length}, input=$input)"
    }

    /** Makes the closed conversation [id] the open one as [command] for the owner's [input], closing the open one (D23). */
    data class SwitchTo(val command: Int, val id: ConversationId, val input: OwnerInput) : ConversationEffect

    /** The switch made [snapshot] the open conversation: its records are the open conversation's now. */
    data class OpenSwitched(val snapshot: ConversationSnapshot) : ConversationEffect

    /**
     * A "stop" came while the store switched: the store puts back what the switch changed, the conversation that it opened
     * closed as it was and the one that was open open again, so that the stop leaves both as they were.
     */
    data object UndoSwitch : ConversationEffect

    data class StartTimer(val timer: Timer, val number: Int, val millis: Long) : ConversationEffect

    /** Closes conversation [id]: stored closed when [keep], else forgotten, because "delete history" deleted it. */
    data class Close(val id: ConversationId, val keep: Boolean = true) : ConversationEffect

    /** Saves the open conversation's snapshot. */
    data object Persist : ConversationEffect

    /** A steering message that resumes a held goal. */
    data class Steering(val text: String, val channel: InputChannel) {
        override fun toString(): String = "Steering(${text.length} chars, channel=$channel)"
    }
}

/** One step of the machine: the new state and the effects that the engine performs in order. */
data class MachineStep(val state: ConversationState, val effects: List<ConversationEffect> = emptyList()) {
    /** This step followed by what [next] makes of its state. */
    fun then(next: (ConversationState) -> MachineStep): MachineStep {
        val after = next(state)
        return MachineStep(after.state, effects + after.effects)
    }
}

/**
 * The owner's act that lets a goal's round act now (design 6.5 of sub-project 7): his [input], or his [tap] on a card, the
 * number among his inputs that `:app` took when the tap reached Kestrel (plan 7F). The round may then have an instant
 * command that he started before it cancelled; a round that goes on without such an act waits for the command instead.
 */
data class OwnerAct(val input: OwnerInput?, val tap: Long? = null) {
    /** The act's number among the owner's inputs; null when no act of his came with it. */
    val number: Long? get() = input?.id ?: tap
}
