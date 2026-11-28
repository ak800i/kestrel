package dev.kestrel.conversation.machine

import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.RecordRef

/**
 * The conversation's state (design 6.1 of sub-project 7). At most one conversation is open: [id] is null when none is, and
 * the next command opens one. [testMode] was fixed when it opened or was continued, from [safetyTestMode], the Safety
 * page's value now (design 12.4). [commands] counts its records; [command] numbers, in this process, each input that
 * started or resumed a goal or ran a local request. [question] waits for the owner; [asksContinue] is set while
 * "Continue the goal?" is asked or about to be. [replying] is the command whose final reply is being spoken or shown;
 * until the owner is there ([PhoneState.present]), [heldReports] are the goals' final replies, oldest first, and
 * [heldWords] a held goal's round report with how to go on, each waiting for him (design 6.4). [local] is a local request
 * or a switch that runs; [waiting] are the commands and Kestrel's own requests, oldest first, that wait for the goal that
 * "stop" ended or for the local request, and each runs in its turn; [closeAfterWork] closes the conversation once its
 * goal or its local request has ended and its record holds the end, after a change of test mode. [continueAnswer] is the owner's input
 * that answered "Continue the goal?", for the round that it resumes, and [continueTap] the number of his tap on the
 * question's card that answered it, which the dialogue's answer gives and the later `ContinueAnswered` names again; both
 * go with the question ([withoutContinue]). [voiceOn] is whether Kestrel is on (its listening
 * mode is not `OFF`, so the voice can speak a question and hear its answer, also in the paused mode); while it is off, a
 * spoken conversation's questions wait as typed ones do (decision 10 of plan 7B). The engine sets it before it restores.
 * toString prints no words.
 */
data class ConversationState(
    val id: ConversationId? = null,
    val openedAtWall: Long = 0,
    val testMode: Boolean = true,
    val continuedAtWall: Long? = null,
    val commands: Int = 0,
    val command: Int = 0,
    val goal: GoalState? = null,
    val question: OpenQuestion? = null,
    val asksContinue: Boolean = false,
    val continueAnswer: OwnerInput? = null,
    val continueTap: Long? = null,
    val replyChannel: InputChannel = InputChannel.VOICE,
    val voiceOn: Boolean = true,
    val quiet: QuietClock = QuietClock.Off,
    val composing: Boolean = false,
    val awaiting: Boolean = false,
    val replying: Int? = null,
    val heldReports: List<ChannelOutput.Report> = emptyList(),
    val heldWords: ChannelOutput.Said? = null,
    val local: LocalRun? = null,
    val waiting: List<OwnerInput> = emptyList(),
    val closeAfterWork: Boolean = false,
    val phone: PhoneState = PhoneState(),
    val timers: Timers = Timers(),
    val settings: ConversationSettings = ConversationSettings(),
    val safetyTestMode: Boolean = true,
) {
    /**
     * The owner's inputs whose work is still to come: the commands and Kestrel's own requests that wait ([waiting]), and the
     * answer to "Continue the goal?" ([continueAnswer]), whose round resumes on the later `ContinueAnswered`. A later step
     * starts each one's work, as the `StartGoal`, `ResumeGoal`, `RunLocal` or `SwitchTo` that names it, or drops it.
     */
    val pendingInputs: List<OwnerInput> get() = waiting + listOfNotNull(continueAnswer)

    /**
     * The numbers of the owner's acts that a later step names again: the ids of [pendingInputs], and [continueTap], the tap
     * on "Continue the goal?"'s card, which reaches the conversation first as the dialogue's answer and then with the
     * `ContinueAnswered` that resumes or ends the goal. The debug hooks keep their claim of such an act until that step
     * (plan 7G); release builds read nothing of it.
     */
    val pendingActs: Set<Long> get() = pendingInputs.mapTo(LinkedHashSet()) { it.id } + listOfNotNull(continueTap)

    /** The state without "Continue the goal?": not asked, and no answer or tap held for the round that it would resume. */
    internal fun withoutContinue(): ConversationState = copy(asksContinue = false, continueAnswer = null, continueTap = null)
}

/** The open conversation's id: a goal and its records belong to the open conversation only. */
internal val ConversationState.openId: ConversationId get() = checkNotNull(id) { "No conversation is open" }

/** The question that waits for the owner; [replyOpen] once he tapped the card's "Reply…" (design 10.3). */
data class OpenQuestion(val number: Int, val kind: QuestionKind, val replyOpen: Boolean = false)

/**
 * A local request or a switch that runs as [command] for the owner's [input]; [record] is a memory request's record, named
 * with its conversation, so that its end is recorded there whatever is open when the request ends. [cancelled] once
 * "stop" cancelled it.
 */
data class LocalRun(val command: Int, val record: RecordRef?, val input: OwnerInput, val cancelled: Boolean = false)

/**
 * The phone as `PhoneSignals` reports it. [known] once a lock signal (`LOCKED` or `UNLOCKED`) has said whether the phone is
 * locked: `PhoneSignals` gives the phone's state when it starts, so at process start the machine does not know it yet.
 * The owner is [away] while the phone is locked or a call rings or lasts, and [present] once the phone is known to be
 * neither: Kestrel's words for him wait until then.
 */
data class PhoneState(val locked: Boolean = false, val ringing: Boolean = false, val inCall: Boolean = false, val known: Boolean = false) {
    val away: Boolean get() = locked || ringing || inCall

    val present: Boolean get() = known && !away
}

/** Each timer's newest number, so that the end of an older timer changes nothing, as the Orchestrator's timers do. */
data class Timers(val quiet: Int = 0, val hold: Int = 0, val handOver: Int = 0, val question: Int = 0)

/** The machine's timers: the quiet time (D18), a held goal's 30 minutes (D17), a hand-over's 3 minutes and a typed wait (D21). */
enum class Timer { QUIET, HOLD, HAND_OVER, QUESTION }
