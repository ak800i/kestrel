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

    /** Says [text] and shows it on the bar; returns once it has been spoken, stopped or refused, or only shown while Kestrel is off. */
    suspend fun say(text: String)

    /** Shows [text] on the bar without speaking it. */
    fun show(text: String)

    /** Asks the conversation's question number [question] of [kind] aloud as [text]; with [voice] false only its card answers it. */
    fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean)

    /** Says a hand-over's [text], which the bar keeps until the channel releases it. */
    fun handOver(text: String)

    /** The conversation's question number [question] ended: the voice no longer waits for its answer. */
    fun withdraw(question: Int)
}
