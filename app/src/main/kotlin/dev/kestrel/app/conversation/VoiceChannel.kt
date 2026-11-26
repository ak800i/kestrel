package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.OutputSlot
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The conversation's voice (design 11 of sub-project 7): it speaks and shows through the Orchestrator ([voice]), which
 * decides every word that Kestrel speaks and runs the answer waits, and it takes Android's parts from [screen], [audio],
 * [handOvers] and [permissions]. A
 * question is spoken whenever a voice session is open, and again when one opens after Kestrel was off; its spoken answer
 * reaches the conversation as an input, so [ask] returns only a card's answer: a confirmation's, which shows while Kestrel
 * speaks, as today (design D14 of sub-project 5). While another app's audio plays, a confirmation of a send or a delete,
 * and the screenshot question, take the voice only after Kestrel paused that audio; when it still plays, only the card's
 * button counts, and when Kestrel paused it, it says [AUDIO_PAUSED] first (D32 item 17). The bar lets a question's or a
 * hand-over's text go once it has ended.
 */
internal class VoiceChannel(
    private val voice: VoicePath,
    private val screen: OwnerScreen,
    private val cards: OwnerCards,
    private val audio: OtherAudio,
    private val handOvers: HandOverSignals,
    private val permissions: PermissionDialog,
    private val offVoice: OffVoice,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
) : OwnerChannel {
    override val kind: InputChannel = InputChannel.VOICE

    // Spoken outputs take turns in the order in which they came: each starts once the one before it has ended, so that a
    // sentence that the engine says at once, as "I got 'X' too late", never plays over a goal's final words. Whatever
    // stops Kestrel's speech, as the owner's stop does (decision 10), also keeps the outputs that came before it unspoken,
    // also those that wait for their turn here or in the engine: each output takes both voices' marks when the engine
    // emits it (`take`), and the voice that speaks it judges it by its mark when its turn comes (one rule for both voices,
    // plan 7F). The voice session's mark is the output's arrival, which the Orchestrator learns in its own order
    // (`VoicePath.came`); Kestrel's own voice's is the count of its stops (`OffVoice.stops`), which counts every stop of
    // Kestrel's speech: the owner's ([stopSpeech]) and each one of the Orchestrator's (`VoicePath.onSpeechStopped`), as the
    // wake word's. Turning Kestrel on or off is a switch of voice, never a stop: it drops nothing that waits, and which
    // voice speaks a typed conversation's words is decided at their turn (`VoicePath.kestrelOn`); turning on ends what
    // Kestrel's own voice speaks (`VoicePath.onKestrelOn`), so that only one voice speaks.
    private val speaking = Mutex()

    init {
        voice.onSpeechStopped(offVoice::stop)
        voice.onKestrelOn(offVoice::endSpeech)
    }

    // The marks of both voices when an output was emitted: the voice session's arrival and Kestrel's own voice's stops.
    private class Marks(val arrival: Long, val ownStops: Long)

    override suspend fun show(output: ChannelOutput) = take().show(output)

    override fun take(): OutputSlot {
        val marks = Marks(voice.came(), offVoice.stops)
        return OutputSlot { output -> shown(output, marks) }
    }

    private suspend fun shown(output: ChannelOutput, marks: Marks) {
        when (output) {
            is ChannelOutput.Said -> said(output, marks)
            // A spoken final reply's end opens the follow-up window (design 6.6 of sub-project 7).
            is ChannelOutput.Report -> inTurn { voice.say(output.text, marks.arrival, final = true) }
            is ChannelOutput.Shown -> voice.show(output.text)
        }
    }

    // Kestrel's own voice speaks only a typed conversation's words while Kestrel is off at their turn (design 10.6 and 11
    // of sub-project 7); all other words go to the voice session, whose Rules only show them while Kestrel is off. A
    // question's spoken copy ends with the question (plan 7F): the conversation cancels it once the owner answered on
    // another channel, and the question's withdrawal stops Kestrel's speech of it, while the other words go on. Kestrel's
    // own voice stops a cancelled reply itself.
    private suspend fun said(output: ChannelOutput.Said, marks: Marks) {
        try {
            inTurn {
                // The own voice's mark before the mode: a switch of voice between the two keeps the words from the own voice,
                // which then gives them back for the voice session.
                val own = offVoice.mark(marks.ownStops)
                val ownVoiceTook = output.typedCopy && !voice.kestrelOn && offVoice.speak(output.text, own)
                if (!ownVoiceTook) voice.say(output.text, marks.arrival, question = output.question)
            }
        } catch (e: CancellationException) {
            output.question?.let(voice::withdraw)
            throw e
        }
    }

    private suspend fun inTurn(speak: suspend () -> Unit) = speaking.withLock { speak() }

    /**
     * The owner's Stop, however it came: spoken, typed, on the screen or on the notification (plan 7F). The Orchestrator
     * stops the voice session's speech (`VoicePath.stopSpeaking`, its `Effect.StopSaying`), Kestrel's own voice stops the
     * words that it speaks, and the outputs that came before it, also those that wait for their turn, are not spoken.
     */
    fun stopSpeech() {
        voice.stopSpeaking()
        offVoice.stop()
    }

    override suspend fun ask(question: ChannelQuestion): CardAnswer = try {
        when (question.kind) {
            QuestionKind.CONFIRM -> confirm(question)
            QuestionKind.SCREENSHOT -> screenshot(question)
            else -> spoken(question, question.spoken, voiceAnswers = true)
        }
    } finally {
        // The voice's answer wait ends, and a withdrawn question still lets the bar go (D32 item 1 of sub-project 5).
        voice.withdraw(question.number)
        withContext(NonCancellable) { screen.releaseBar() }
    }

    // The voice's hand-over has no button: the owner's "Kestrel, continue" reaches the conversation as an input. The voice
    // is always the hand-over's own channel; only the text channel shows a copy of its card (plan 7F).
    override suspend fun handOver(handOver: HandOver, part: HandOverPart): HandOverAnswer = try {
        check(part == HandOverPart.OWN) { "The voice shows no hand-over's card" }
        voice.handOver(handOver.text)
        handOvers.opened(handOver.kind)
        handOvers.endedByItself(handOver.kind)
        HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null)
    } finally {
        withContext(NonCancellable) { screen.releaseBar() }
    }

    // Kestrel's own permission request is a hand-over of kind PERMISSION (design D12 and D15 of sub-project 5): it ends
    // when Android's dialog answers, or when the owner says "Kestrel, continue" first.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd = try {
        voice.handOver(why)
        this.permissions.ask(permissions)
    } finally {
        withContext(NonCancellable) { screen.releaseBar() }
    }

    override suspend fun leaveKestrel(): Boolean = screen.awaitKestrelLeft()

    // The card and the spoken question together: the card's button answers here, the voice as an input (D32 item 18).
    private suspend fun confirm(question: ChannelQuestion): CardAnswer {
        val guarded = question.confirmation?.category.let { it == Category.SENDING || it == Category.DELETING }
        val playing = guarded && audio.playing()
        val voiced = !guarded || voiceAllowed()
        val spoken = if (playing && voiced) "$AUDIO_PAUSED ${question.spoken}" else question.spoken
        return withCard(question, spoken, voiced)
    }

    private suspend fun screenshot(question: ChannelQuestion): CardAnswer {
        if (!audio.playing()) return spoken(question, question.spoken, voiceAnswers = true)
        if (voiceAllowed()) return spoken(question, "$AUDIO_PAUSED ${question.spoken}", voiceAnswers = true)
        return withCard(question, question.spoken, voiceAnswers = false)
    }

    // The card through the desk, which shares it with the text channel's copy of the question (design 10.3 of sub-project
    // 7); without a screen session there is no card, and only the voice answers. The voice's card has no Reply…, so the
    // desk gives it only answers.
    private suspend fun withCard(question: ChannelQuestion, spoken: String, voiceAnswers: Boolean): CardAnswer = coroutineScope {
        val asking = launch { spoken(question, spoken, voiceAnswers) }
        val tapped = cards.ask(OwnerCard.of(question, reply = false))
        asking.cancel()
        CardAnswer(checkNotNull(tapped.choice.answer) { "The voice's card has no Reply…" }, tapped.act)
    }

    // Asks the question each time a voice session opens, from now on, until the conversation withdraws it.
    private suspend fun spoken(question: ChannelQuestion, text: String, voiceAnswers: Boolean): Nothing {
        var asked = false
        voice.ready.collect { open ->
            if (open && !asked) voice.ask(question.number, text, question.kind, voiceAnswers)
            asked = open
        }
    }

    // Design D32 item 17: the voice counts only when no other app's audio plays, or after Kestrel paused it.
    private suspend fun voiceAllowed(): Boolean {
        if (!audio.playing()) return true
        audio.pause()
        delay(audioSettleMillis)
        return !audio.playing()
    }

    override fun toString(): String = "VoiceChannel"

    companion object {
        const val AUDIO_PAUSED = "I paused the other app's audio."

        /** How long Android takes to report a paused player as stopped. */
        const val AUDIO_SETTLE_MILLIS = 500L
    }
}
