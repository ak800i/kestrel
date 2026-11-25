package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOver
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val audio: OtherAudio,
    private val handOvers: HandOverSignals,
    private val permissions: PermissionDialog,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
) : OwnerChannel {
    override val kind: InputChannel = InputChannel.VOICE

    // Spoken outputs take turns in the order in which they came: each starts once the one before it has ended, so that a
    // sentence that the engine says at once, as "I got 'X' too late", never plays over a goal's final words. Whatever
    // stops Kestrel's speech, as the owner's stop does (decision 10), also drops the outputs that wait for their turn, as
    // it drops the voice session's queued speech: only the outputs that come after it play.
    private val speaking = Mutex()

    override suspend fun show(output: ChannelOutput) {
        when (output) {
            is ChannelOutput.Said -> inTurn { voice.say(output.text) }
            // A spoken final reply's end opens the follow-up window (design 6.6 of sub-project 7).
            is ChannelOutput.Report -> inTurn { voice.say(output.text, final = true) }
            is ChannelOutput.Shown -> voice.show(output.text)
        }
    }

    private suspend fun inTurn(speak: suspend () -> Unit) {
        val came = voice.speechStops
        speaking.withLock { if (voice.speechStops == came) speak() }
    }

    override suspend fun ask(question: ChannelQuestion): ChannelAnswer = try {
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

    override suspend fun handOver(handOver: HandOver): HandOverEnd = try {
        voice.handOver(handOver.text)
        handOvers.opened(handOver.kind)
        handOvers.endedByItself(handOver.kind)
        HandOverEnd.DONE_BY_ITSELF
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

    // Nothing moves Kestrel's own screen away until plan 7F, so the goal waits for the owner to leave it (design 10.2 of
    // sub-project 7).
    override suspend fun leaveKestrel(): Boolean =
        withTimeoutOrNull(OwnerPort.KESTREL_LEAVE_MILLIS) {
            while (screen.kestrelInFront()) delay(KESTREL_POLL_MILLIS)
            true
        } ?: false

    // The card and the spoken question together: the card's button answers here, the voice as an input (D32 item 18).
    private suspend fun confirm(question: ChannelQuestion): ChannelAnswer {
        val guarded = question.confirmation?.category.let { it == Category.SENDING || it == Category.DELETING }
        val playing = guarded && audio.playing()
        val voiced = !guarded || voiceAllowed()
        val spoken = if (playing && voiced) "$AUDIO_PAUSED ${question.spoken}" else question.spoken
        return withCard(question, spoken, voiced)
    }

    private suspend fun screenshot(question: ChannelQuestion): ChannelAnswer {
        if (!audio.playing()) return spoken(question, question.spoken, voiceAnswers = true)
        if (voiceAllowed()) return spoken(question, "$AUDIO_PAUSED ${question.spoken}", voiceAnswers = true)
        return withCard(question, question.spoken, voiceAnswers = false)
    }

    // Without a screen session there is no card, and only the voice answers.
    private suspend fun withCard(question: ChannelQuestion, spoken: String, voiceAnswers: Boolean): ChannelAnswer = coroutineScope {
        val asking = launch { spoken(question, spoken, voiceAnswers) }
        val yes = screen.card(question.text) ?: awaitCancellation()
        asking.cancel()
        if (yes) ChannelAnswer.Yes else ChannelAnswer.No
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

        /** How often [leaveKestrel] reads whether Kestrel's own screen is still in front. */
        const val KESTREL_POLL_MILLIS = 200L
    }
}
