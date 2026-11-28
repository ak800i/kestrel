package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.RoundEnd

/** The machine's tests' common parts: one conversation id, a clock that a test moves, and inputs and states. */
abstract class MachineTesting {
    protected val id = ConversationId("0123456789abcdef0123456789abcdef")
    protected val other = ConversationId("fedcba9876543210fedcba9876543210")
    protected val machine = ConversationMachine { id }
    protected var now = 1_000_000L
    private var inputs = 0L

    /**
     * No conversation open, and the phone unlocked as `PhoneSignals` reported it; the Safety page's test mode is off, so that
     * the tests can see it switch on.
     */
    protected val idle = ConversationState(phone = PhoneState(known = true), safetyTestMode = false)

    /** At process start: no conversation open, and `PhoneSignals` has not yet said whether the phone is locked. */
    protected val starting = ConversationState(safetyTestMode = false)

    protected fun step(state: ConversationState, event: ConversationEvent): MachineStep = machine.next(state, event, now)

    protected fun input(channel: InputChannel, intent: InputIntent): ConversationEvent.Input =
        ConversationEvent.Input(OwnerInput(++inputs, channel, intent, now))

    protected fun typed(text: String) = input(InputChannel.TEXT, InputIntent.Command(text))

    protected fun typed(intent: InputIntent) = input(InputChannel.TEXT, intent)

    protected fun spoken(text: String) = input(InputChannel.VOICE, InputIntent.Command(text))

    protected fun spoken(intent: InputIntent) = input(InputChannel.VOICE, intent)

    /** A typed command's goal that acts, the conversation's first. */
    protected fun acting(from: ConversationState = idle, text: String = "Turn on Wi-Fi"): ConversationState = step(from, typed(text)).state

    /** [state]'s goal ends with [report] and [sentence], which is still to say. */
    protected fun ended(state: ConversationState, report: TaskReport = TaskReport(EndReason.FINISHED), sentence: String? = "Done."): MachineStep =
        step(state, ConversationEvent.GoalEnded(RoundEnd(state.goal!!.command, report, answer = sentence, reply = sentence)))

    protected fun asked(state: ConversationState, number: Int, kind: QuestionKind): ConversationState =
        step(state, ConversationEvent.Dialogue(DialogueEvent.Asked(number, kind))).state

    /** The dialogue's answer [answer] to question [number], with the card's [tap] when a card gave it. */
    protected fun answered(state: ConversationState, number: Int, answer: ChannelAnswer?, tap: Long? = null): MachineStep =
        step(state, ConversationEvent.Dialogue(DialogueEvent.Answered(number, answer, tap)))

    /** "Continue the goal?" asked on [state] as question [number] and answered with [answer], as the dialogue gives it. */
    protected fun continueAnswered(state: ConversationState, number: Int, answer: ChannelAnswer?, tap: Long? = null): MachineStep =
        answered(asked(state, number, QuestionKind.CONTINUE_GOAL), number, answer, tap)

    /** An open conversation without a goal, whose quiet clock runs: its first command ended and its reply was shown. */
    protected fun idleOpen(): ConversationState {
        val replying = ended(acting()).state
        return step(replying, ConversationEvent.ReplyShown(replying.replying!!)).state
    }

    protected inline fun <reified T : ConversationEffect> MachineStep.effect(): T = effects.filterIsInstance<T>().single()

    protected inline fun <reified T : ConversationEffect> MachineStep.has(): Boolean = effects.any { it is T }

    protected fun MachineStep.outputs(): List<ChannelOutput> = effects.filterIsInstance<ConversationEffect.Output>().map { it.output }
}
