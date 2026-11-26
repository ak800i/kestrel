package dev.kestrel.app.listen

import dev.kestrel.conversation.channel.QuestionKind
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice channel's way to the Orchestrator (design 11 of sub-project 7), which [Listening] gives: the Orchestrator
 * still decides every word that Kestrel speaks, and runs the answer waits of a question that the voice asks.
 */
internal interface VoicePath {
    /** Whether a voice session is open, so that Kestrel can speak and hear. */
    val ready: StateFlow<Boolean>

    /**
     * How many times the Orchestrator has stopped Kestrel's speech (`Effect.StopSaying`, as the owner's stop does): a
     * spoken output that waited for its turn across a stop is dropped (decision 10 of sub-project 7).
     */
    val speechStops: Long

    /**
     * Says [text] and shows it on the bar; returns once it has been spoken, stopped or refused, or only shown while Kestrel
     * is off. A [final] reply's end opens the follow-up window (design 6.6 of sub-project 7). [question]: the words are the
     * spoken copy of the conversation's question with this number, so that its [withdraw] stops them (plan 7F).
     */
    suspend fun say(text: String, final: Boolean = false, question: Int? = null)

    /** Shows [text] on the bar without speaking it. */
    fun show(text: String)

    /** Asks the conversation's question number [question] of [kind] aloud as [text]; with [voice] false only its card answers it. */
    fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean)

    /** Says a hand-over's [text], which the bar keeps until the channel releases it. */
    fun handOver(text: String)

    /**
     * The conversation's question number [question] ended, however the owner answered it: the voice no longer waits for its
     * answer, and Kestrel stops speaking it, as it asked it ([ask]) or as its spoken copy ([say]), while the other words go
     * on (plan 7F).
     */
    fun withdraw(question: Int)

    /**
     * The owner's Stop that did not come by voice (plan 7F): the Orchestrator stops Kestrel's speech as for a spoken stop,
     * which counts in [speechStops].
     */
    fun stopSpeaking()
}
