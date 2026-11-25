package dev.kestrel.conversation.machine

import dev.kestrel.agent.TaskReport
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PhoneSignal
import dev.kestrel.conversation.model.ProcessEnd

/** What happens to the conversation (design 6.3 of sub-project 7). toString prints no words. */
sealed interface ConversationEvent {
    /** The owner's input, from either channel. */
    data class Input(val input: OwnerInput) : ConversationEvent

    /** A question or a hand-over of the owner's opened or closed. */
    data class Dialogue(val event: DialogueEvent) : ConversationEvent

    /**
     * The round of the goal of [command] ended with [report]. [answer] is its final words, which its record keeps, and
     * [reply] what is still to say of them: null when nothing is, as after a routine offer that said the report first.
     */
    data class GoalEnded(val command: Int, val report: TaskReport, val answer: String?, val reply: String?) : ConversationEvent {
        override fun toString(): String = "GoalEnded(command=$command, report=$report, answer=${answer?.length}, reply=${reply?.length})"
    }

    /** The touch watcher saw the owner's touch while a goal ran (D13). */
    data object Touched : ConversationEvent

    /** The final reply of [command] has been spoken or shown. */
    data class ReplyShown(val command: Int) : ConversationEvent

    data class TimerEnded(val timer: Timer, val number: Int) : ConversationEvent

    /** The conversation screen's field holds text while the screen is in front, or no longer does. */
    data class Composing(val active: Boolean) : ConversationEvent

    /** Kestrel waits for a command after "Kestrel" alone, the side key or the microphone button, or no longer does. */
    data class Awaiting(val active: Boolean) : ConversationEvent

    /**
     * Kestrel is on (listening or paused) or off (decision 10 of plan 7B); only "turn off" and turning on again change it,
     * not "stop listening".
     */
    data class Voice(val on: Boolean) : ConversationEvent

    /** The owner tapped "Reply…" on question [question]'s card (design 10.3). */
    data class ReplyOpened(val question: Int) : ConversationEvent

    data class Signal(val signal: PhoneSignal) : ConversationEvent

    /** The Conversations page, or the Safety page's test mode, as they are now. */
    data class SettingsChanged(val settings: ConversationSettings, val testMode: Boolean) : ConversationEvent

    /**
     * At process start: the open conversation that the store held, or null, and how the previous process ended
     * ([previous]; plan 7E), which ends a restored goal when the owner stopped Kestrel after the snapshot.
     */
    data class Restored(val snapshot: ConversationSnapshot?, val previous: ProcessEnd = ProcessEnd.Other) : ConversationEvent

    /** The owner's answer to "Continue the goal?". */
    data class ContinueAnswered(val answer: ChannelAnswer) : ConversationEvent

    data class LocalEnded(val command: Int, val outcome: LocalOutcome) : ConversationEvent

    /** The store made [snapshot] the open conversation, or it was gone (null); [sentence] is the request's final reply. */
    data class Switched(val snapshot: ConversationSnapshot?, val sentence: String) : ConversationEvent {
        override fun toString(): String = "Switched(snapshot=$snapshot, sentence=${sentence.length})"
    }

    /** The hook `conversation-control do=expire` ends the running quiet time at once (design 14.4). */
    data object EndQuietTime : ConversationEvent
}
