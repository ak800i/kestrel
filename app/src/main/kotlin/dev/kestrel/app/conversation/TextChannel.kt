package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.safety.HandOver
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicInteger

/** What the text channel needs of the conversation (design 10.1 of sub-project 7); the app graph gives the engine's. */
internal interface TypedConversation {
    /** The conversation's view now, whose open question typed words answer. */
    fun view(): ConversationView

    fun submit(input: OwnerInput)

    /** The owner tapped "Reply…" on question [question]'s card (design 10.3). */
    fun replyOpened(question: Int)

    /** The screen's field holds words while the screen is in front (design 6.2). */
    fun composing(active: Boolean)
}

/** Opens the conversation screen with question [question] in focus and the keyboard up ("Reply…", design 10.3). */
internal fun interface ReplyScreen {
    fun open(question: Int)
}

/**
 * The conversation's text channel (design 10, 11 and 12.2 of sub-project 7). Kestrel's words and final replies go to the
 * bar ([bar]), and the transcript draws them from the records; nothing is spoken here. Every question and hand-over asks
 * through the card desk ([cards]), which shares the card with the voice's copy of the question. "Reply…" on the overlay
 * holds the question for 5 minutes and opens the conversation screen with the question in focus; the typed answer then
 * reaches the conversation as an input, which withdraws the question.
 *
 * The owner's typed words and the screen's buttons become inputs only here: [read] reads typed words against the open
 * question, [input] makes the screen's own inputs (Stop, Continue, New conversation, a past conversation's Continue),
 * [choose] makes a button of the screen's copy of a card the owner's answer, and [submit] gives each to the conversation
 * and to [sent], from which the screen learns that the owner acted on it. The debug hook `conversation-type` types through
 * [read] and [submit] too.
 */
internal class TextChannel(
    private val bar: OwnedBar,
    private val screen: OwnerScreen,
    private val cards: SharedCards,
    private val handOvers: HandOverSignals,
    private val permissions: PermissionDialog,
    private val replies: ReplyScreen,
    private val conversation: TypedConversation,
    private val clock: () -> Long = System::currentTimeMillis,
) : OwnerChannel {
    override val kind: InputChannel = InputChannel.TEXT

    private val sentIntents = MutableSharedFlow<InputIntent>(extraBufferCapacity = SENT_BUFFER)
    private val handOverKeys = AtomicInteger()

    /** Every input that the owner's typing or the screen's buttons gave the conversation, in order. */
    val sent: SharedFlow<InputIntent> = sentIntents.asSharedFlow()

    /**
     * The owner tapped [choice] on the screen's copy of the open card [key] (design 10.3): its answer reaches the
     * conversation as his input, so that the goal looks afresh; "Reply…" holds the question while he types. A card that
     * only the overlay answers (a confirmation, a hand-over or a pick), a card that is no longer open, and a choice that the
     * card does not have take nothing, and no tap is kept for a later card (design 12.2).
     */
    fun choose(key: Int, choice: CardChoice) {
        val card = cards.open.value?.takeIf { it.key == key && it.answersOnScreen && choice in it.choices } ?: return
        when (val answer = choice.answer) {
            null -> conversation.replyOpened(checkNotNull(card.question))
            // The answer names its question, so that a tap that comes late answers no later one (Task A2).
            else -> submit(input(InputIntent.Answer(answer, question = card.question)))
        }
    }

    /** The owner's typed [text] as an input, read against the open question (design 12.2); null for a text without words. */
    fun read(text: String): OwnerInput? = TypedInputs.read(text, conversation.view())?.let(::input)

    /** An input of the conversation screen's own, such as its Stop or its Continue. */
    fun input(intent: InputIntent): OwnerInput = OwnerInput(InputIds.next(), InputChannel.TEXT, intent, clock())

    /** Gives [input] to the conversation and tells [sent]. */
    fun submit(input: OwnerInput) {
        conversation.submit(input)
        sentIntents.tryEmit(input.intent)
    }

    fun composing(active: Boolean) = conversation.composing(active)

    override suspend fun show(output: ChannelOutput) {
        val text = when (output) {
            is ChannelOutput.Said -> output.text
            is ChannelOutput.Shown -> output.text
            is ChannelOutput.Report -> output.text
        }
        bar.show(text)
    }

    // The desk returns only a button that answers, with the number of the owner's tap; "Reply…" opens the screen for the
    // owner's words, which come as an input.
    override suspend fun ask(question: ChannelQuestion): CardAnswer {
        val tapped = cards.ask(OwnerCard.of(question, reply = true)) {
            conversation.replyOpened(question.number)
            replies.open(question.number)
        }
        return CardAnswer(checkNotNull(tapped.choice.answer) { "Reply… is no answer" }, tapped.act)
    }

    // A tap on the card's Continue or Cancel carries the number that the desk took at the tap, so that the round that goes on
    // names the owner's act of that moment (plan 7F). As the hand-over's own channel, the text channel opens what the
    // hand-over opens and watches for its end by itself; as the copy of a spoken conversation's hand-over, it shows only the
    // card, because the voice owns that hand-over, so that its page opens once (plan 7F).
    override suspend fun handOver(handOver: HandOver, part: HandOverPart): HandOverAnswer = coroutineScope {
        val card = OwnerCard.handOver(-handOverKeys.incrementAndGet(), handOver.text)
        val tapped = async {
            val tap = cards.ask(card)
            HandOverAnswer(if (tap.choice == CardChoice.Continue) HandOverEnd.CONTINUED else HandOverEnd.CANCELLED, tap.act)
        }
        if (part == HandOverPart.CARD) return@coroutineScope tapped.await()
        handOvers.opened(handOver.kind)
        val byItself = async {
            handOvers.endedByItself(handOver.kind)
            HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null)
        }
        select<HandOverAnswer> {
            byItself.onAwait { it }
            tapped.onAwait { it }
        }.also { currentCoroutineContext().cancelChildren() }
    }

    // Design 10.6: a typed conversation's permission request says why on the bar and shows Android's dialog, once.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        bar.show(why)
        return this.permissions.ask(permissions)
    }

    override suspend fun leaveKestrel(): Boolean = screen.awaitKestrelLeft()

    override fun toString(): String = "TextChannel($cards)"

    private companion object {
        const val SENT_BUFFER = 16
    }
}
