package dev.kestrel.conversation.channel

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The owner as a goal and the conversation talk to him (design 5.3, 10.3 and 11 of sub-project 7). It takes over the
 * parts of `:app`'s `AppOwnerPort` that are not Android: one question at a time, the waits and wakes, and the pause gate.
 *
 * Kestrel's words go to the channel of the owner's latest input ([routing]); with "Speak replies to typed commands" on, a
 * typed conversation's words and questions are spoken too, and a typed final reply reaches the voice as words only, so
 * that it opens no follow-up window (design 11). A question goes to that channel, and in a spoken conversation
 * "Continue the goal?" shows its card as well. While Kestrel is off ([voiceOn] is false: its listening mode is `OFF`, so
 * the voice hears nothing and speaks only a typed conversation's words with the setting on, design 11), a spoken
 * conversation's words are only shown, and every question, hand-over and permission request also gets the text channel's
 * card (a permission request's is a hand-over's card, because Android's dialog is shown once), from the moment Kestrel
 * turns off; a card stays until what it shows has ended, also when Kestrel is on again, so that the owner never loses a
 * way to answer. The first answer wins, from a channel's card or from an input that the engine hands over with [answer],
 * and the others are withdrawn. [events] tells the engine when a question or a hand-over opens and closes. A pause
 * ([pause]) withdraws a goal's open question, whose answer is then `Interrupted`, and closes the gate until [resume]; the
 * conversation's own questions ([question]) are never withdrawn by it. [startWork] clears what earlier work left: a pause
 * that came after its round had ended, and a fresh look. After an answer that the owner gave on Kestrel's own screen,
 * whatever its kind, the gate returns `RESUMED` once, so that the run looks at the screen afresh (design 10.3). A
 * hand-over and a permission request that a pause meets wait for the resume before they take their turn, so that the
 * conversation can ask "Continue the goal?" meanwhile; they end by the channel, or by the engine's [endHandOver]; after
 * "Kestrel, continue" a permission request asks [permissions] whether Android granted them.
 */
class ConversationOwner(
    private val channels: Map<InputChannel, OwnerChannel>,
    private val permissions: (List<String>) -> PermissionEnd,
    private val voiceOn: StateFlow<Boolean>,
    private val routing: () -> Routing,
    private val events: (DialogueEvent) -> Unit,
) : OwnerPort, Dialogue {
    /** The owner's latest input's channel, and whether typed replies are spoken too (design 11). */
    data class Routing(val channel: InputChannel, val speakTyped: Boolean)

    private class Waiting<T>(val number: Int, val goal: Boolean) {
        val done = CompletableDeferred<T>()
    }

    /** An answer as it arrived: [leftScreen] when the owner gave it on Kestrel's own screen. */
    private class Given(val answer: ChannelAnswer, val leftScreen: Boolean)

    private class Draft(
        val text: String,
        val kind: QuestionKind,
        val spoken: String = text,
        val confirmation: Confirmation? = null,
    )

    private val turns = Mutex()
    private val numbers = AtomicInteger()
    private val lock = Any()
    private var openQuestion: Waiting<Given>? = null
    private var openHandOver: Waiting<HandOverEnd>? = null
    private var pause: CompletableDeferred<Unit>? = null
    private var fresh = false

    /** The open question's kind, for the hook `agent-state`. */
    @Volatile
    var asking: QuestionKind? = null
        private set

    /** The open confirmation's category, for the hook `agent-state`. */
    @Volatile
    var confirming: Category? = null
        private set

    /** The open hand-over's kind, for the hook `agent-state`. */
    @Volatile
    var handOverKind: HandOverKind? = null
        private set

    override suspend fun say(text: String) = show(ChannelOutput.Said(text))

    /**
     * Says or shows [output] on the owner's latest channel, and speaks it too when a typed reply is spoken. While Kestrel
     * is off, a spoken conversation's words are only shown, so that no follow-up window opens (design 10.6 and 11).
     */
    suspend fun show(output: ChannelOutput) {
        val route = routing()
        when {
            route.channel == InputChannel.TEXT -> {
                channels[InputChannel.TEXT]?.show(output)
                if (route.speakTyped) channels[InputChannel.VOICE]?.show(spokenCopy(output))
            }
            voiceOn.value -> channels[InputChannel.VOICE]?.show(output)
            else -> channels[InputChannel.VOICE]?.show(ChannelOutput.Shown(textOf(output)))
        }
    }

    override suspend fun question(text: String, kind: QuestionKind, spoken: String): ChannelAnswer =
        dialogue(Draft(text, kind, spoken), goal = false)

    override suspend fun ask(question: String, kind: AnswerKind): Answer = answerOf(dialogue(Draft(question, kindOf(kind)), goal = true))

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd =
        when (dialogue(Draft(confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation), goal = true)) {
            ChannelAnswer.Yes -> ConfirmEnd.YES
            ChannelAnswer.Silence -> ConfirmEnd.TIMEOUT
            ChannelAnswer.Interrupted -> ConfirmEnd.INTERRUPTED
            else -> ConfirmEnd.NO
        }

    override suspend fun handOver(handOver: HandOver): HandOverEnd =
        handedOver(handOver.kind, { it.handOver(handOver) }, { it.handOver(handOver) }) { it }

    // Android's dialog is shown once, by the owner's channel. The card that a spoken conversation gets once Kestrel is off
    // is a hand-over's card, whose Continue checks what Android granted, so the dialog never shows a second time.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        val ended = { end: HandOverEnd -> if (end == HandOverEnd.CONTINUED) this.permissions(permissions) else PermissionEnd.REFUSED }
        return handedOver(HandOverKind.PERMISSION, { it.askPermission(permissions, why) }, { ended(it.handOver(HandOver.of(HandOverKind.PERMISSION))) }, ended)
    }

    override suspend fun gate(): Gate {
        val paused = synchronized(lock) { pause }
        if (paused != null) {
            paused.await()
            // The resume's fresh look is also the one that an answer on Kestrel's own screen left.
            synchronized(lock) { fresh = false }
            return Gate.RESUMED
        }
        return if (synchronized(lock) { fresh.also { fresh = false } }) Gate.RESUMED else Gate.GO
    }

    // With no channel for the owner's latest input, nothing can take Kestrel's own screen away.
    override suspend fun leaveKestrel(): Boolean = channels[routing().channel]?.leaveKestrel() ?: false

    /**
     * The owner's [answer] to question [number], from an input; an older question's answer changes nothing. [leftScreen] is
     * true when he gave it on Kestrel's own screen, which leaves before the goal looks again (design 10.3).
     */
    fun answer(number: Int, answer: ChannelAnswer, leftScreen: Boolean = false) {
        synchronized(lock) { openQuestion?.takeIf { it.number == number } }?.done?.complete(Given(answer, leftScreen))
    }

    /** Ends hand-over [number] with [end]: "Kestrel, continue", "cancel" or its 3 minutes. */
    fun endHandOver(number: Int, end: HandOverEnd) {
        synchronized(lock) { openHandOver?.takeIf { it.number == number } }?.done?.complete(end)
    }

    /** A lock or a call paused the goal: its open question is withdrawn, and the gate closes. */
    fun pause() {
        val open = synchronized(lock) {
            if (pause == null) pause = CompletableDeferred()
            openQuestion?.takeIf { it.goal }
        }
        open?.done?.complete(Given(ChannelAnswer.Interrupted, leftScreen = false))
    }

    /** The goal goes on: the gate opens and returns `RESUMED` to the waiting run. */
    fun resume() {
        synchronized(lock) { pause.also { pause = null } }?.complete(Unit)
    }

    /** A goal's round or Kestrel's own request starts: no pause and no fresh look of earlier work is left over. */
    fun startWork() {
        resume()
        synchronized(lock) { fresh = false }
    }

    private suspend fun dialogue(draft: Draft, goal: Boolean): ChannelAnswer = turns.withLock {
        val waiting = Waiting<Given>(numbers.incrementAndGet(), goal)
        synchronized(lock) {
            if (goal && pause != null) return@withLock ChannelAnswer.Interrupted
            openQuestion = waiting
        }
        val asked = ChannelQuestion(waiting.number, draft.text, draft.kind, draft.spoken, confirmation = draft.confirmation)
        asking = draft.kind
        confirming = draft.confirmation?.category
        events(DialogueEvent.Asked(waiting.number, draft.kind))
        try {
            val given = race(asked, waiting.done)
            if (given.leftScreen) synchronized(lock) { fresh = true }
            given.answer
        } finally {
            synchronized(lock) { if (openQuestion === waiting) openQuestion = null }
            asking = null
            confirming = null
            events(DialogueEvent.Answered(waiting.number))
        }
    }

    // The question on the latest channel; in a spoken conversation its card too, at once for "Continue the goal?" and for
    // any other question from the moment Kestrel is off; and its words spoken for a typed one with the setting on. The
    // first answer wins and the others are withdrawn. A card shows over the app, so its answer leaves no screen of Kestrel's.
    private suspend fun race(asked: ChannelQuestion, answered: Deferred<Given>): Given = coroutineScope {
        val route = routing()
        val text = channels[InputChannel.TEXT]
        val voice = channels[InputChannel.VOICE]
        val askers = mutableListOf<Deferred<ChannelAnswer>>()
        channels[route.channel]?.let { primary -> askers += async { primary.ask(asked) } }
        if (route.channel == InputChannel.VOICE && text != null) {
            askers += async {
                if (asked.kind != QuestionKind.CONTINUE_GOAL) voiceOn.first { on -> !on }
                text.ask(asked)
            }
        }
        if (route.channel == InputChannel.TEXT && route.speakTyped && voice != null) {
            askers += async {
                voice.show(ChannelOutput.Said(asked.spoken))
                awaitCancellation()
            }
        }
        select<Given> {
            answered.onAwait { it }
            for (asker in askers) asker.onAwait { Given(it, leftScreen = false) }
        }.also { currentCoroutineContext().cancelChildren() }
    }

    private suspend fun <T> handedOver(
        kind: HandOverKind,
        itself: suspend (OwnerChannel) -> T,
        card: suspend (OwnerChannel) -> T,
        ended: (HandOverEnd) -> T,
    ): T = inTurnAfterPause {
        val waiting = Waiting<HandOverEnd>(numbers.incrementAndGet(), goal = true)
        synchronized(lock) { openHandOver = waiting }
        handOverKind = kind
        events(DialogueEvent.HandedOver(waiting.number))
        try {
            coroutineScope {
                val route = routing()
                val own = channels[route.channel]?.let { channel -> async { itself(channel) } }
                // A spoken conversation's hand-over gets its card with Continue and Cancel once Kestrel is off (design 10.3).
                val card = channels[InputChannel.TEXT]?.takeIf { route.channel == InputChannel.VOICE }?.let { text ->
                    async {
                        voiceOn.first { on -> !on }
                        card(text)
                    }
                }
                select<T> {
                    waiting.done.onAwait { ended(it) }
                    if (own != null) own.onAwait { it }
                    if (card != null) card.onAwait { it }
                }.also { currentCoroutineContext().cancelChildren() }
            }
        } finally {
            synchronized(lock) { if (openHandOver === waiting) openHandOver = null }
            handOverKind = null
            events(DialogueEvent.HandOverDone(waiting.number))
        }
    }

    // A hand-over that a pause met before it began begins after the resume, as today (design D2 of sub-project 5). It waits
    // for the resume before it takes its turn, so that the conversation can ask "Continue the goal?" meanwhile, and looks
    // again once it has the turn, because a pause may have come while it waited for the turn.
    private suspend fun <T> inTurnAfterPause(block: suspend () -> T): T {
        while (true) {
            synchronized(lock) { pause }?.await()
            turns.lock()
            if (synchronized(lock) { pause } == null) break
            turns.unlock()
        }
        try {
            return block()
        } finally {
            turns.unlock()
        }
    }

    // The voice's copy of a typed conversation's output: a final reply becomes words, because the owner typed (design 11).
    private fun spokenCopy(output: ChannelOutput): ChannelOutput = if (output is ChannelOutput.Report) ChannelOutput.Said(output.text) else output

    private fun textOf(output: ChannelOutput): String = when (output) {
        is ChannelOutput.Said -> output.text
        is ChannelOutput.Shown -> output.text
        is ChannelOutput.Report -> output.text
    }

    override fun toString(): String = "ConversationOwner(asking=$asking, handOver=$handOverKind)"

    companion object {
        /** The card's and the transcript's kind of the agent's question. */
        fun kindOf(kind: AnswerKind): QuestionKind = when (kind) {
            AnswerKind.YES_NO -> QuestionKind.YES_NO
            AnswerKind.FREE -> QuestionKind.FREE
            AnswerKind.PICK_NUMBER -> QuestionKind.PICK
            AnswerKind.SCREENSHOT -> QuestionKind.SCREENSHOT
            AnswerKind.LIMIT -> QuestionKind.LIMIT
        }

        /** The agent's answer for the owner's: his words, a number, yes, no, silence or an interruption. */
        fun answerOf(answer: ChannelAnswer): Answer = when (answer) {
            ChannelAnswer.Yes -> Answer.Yes
            ChannelAnswer.No -> Answer.No
            is ChannelAnswer.Text -> Answer.Text(answer.text)
            is ChannelAnswer.Pick -> Answer.Number(answer.number)
            ChannelAnswer.Silence -> Answer.Silence
            ChannelAnswer.Interrupted -> Answer.Interrupted
        }
    }
}
