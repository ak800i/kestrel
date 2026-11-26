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
     * Words came to the voice channel now: returns their arrival number, which the Orchestrator learns in its own order,
     * after every stop that was sent before. A stop of Kestrel's speech after that, by the Orchestrator, as the owner's
     * spoken stop does, or by the owner's [stopSpeaking], also while Kestrel does not speak, keeps them unspoken ([say];
     * decision 10 of sub-project 7; plan 7F).
     */
    fun came(): Long

    /**
     * Says [text], which came as [arrival] ([came]), and shows it on the bar; returns once it has been spoken, stopped or
     * refused, only shown while Kestrel is off, or dropped because a stop came after it. A [final] reply's end opens the
     * follow-up window (design 6.6 of sub-project 7). [question]: the words are the spoken copy of the conversation's
     * question with this number, so that its [withdraw] stops them (plan 7F).
     */
    suspend fun say(text: String, arrival: Long, final: Boolean = false, question: Int? = null)

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
     * The owner's Stop, however it came (plan 7F): the Orchestrator stops Kestrel's speech, and the stop keeps the words
     * that came before it unspoken ([came]), also while Kestrel does not speak.
     */
    fun stopSpeaking()

    /**
     * Sets [action], which runs each time the Orchestrator stops Kestrel's speech (its `Effect.StopSaying`, as for the wake
     * word or the assistant gesture), on its thread; it must return at once. The voice channel stops Kestrel's own voice
     * with it, so that every stop of Kestrel's speech counts for both voices (plan 7F).
     */
    fun onSpeechStopped(action: () -> Unit)

    /**
     * Sets [action], which runs each time Kestrel turns on (`Effect.EndOwnVoice`), on the Orchestrator's thread; it must
     * return at once. The voice channel ends Kestrel's own voice's speech with it: a switch of voice, not a stop (plan 7F).
     */
    fun onKestrelOn(action: () -> Unit)

    /** Whether Kestrel is on now (its mode is not OFF), so that a typed conversation's words go to the voice session. */
    val kestrelOn: Boolean
}
