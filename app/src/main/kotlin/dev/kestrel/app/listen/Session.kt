package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.Deferred

/**
 * An open voice session: the speech engines, the microphone, the listening pipeline and the voice player (design spec,
 * sections 7.2 and 8.1–8.4). The listening controller sets [recognition] and calls [startMicrophone], [stopMicrophone]
 * and [close] from its own thread, one at a time; [speak] and [stopSpeaking] may be called from any thread.
 */
internal interface Session {
    /** Which utterances the pipeline transcribes from the next frame on. */
    var recognition: Recognition

    /**
     * Starts the microphone. Throws when it does not start, for example without the microphone permission; then
     * [stopMicrophone] releases whatever the start had opened. Throws [IllegalStateException] after [close].
     */
    fun startMicrophone()

    /** Stops the microphone, and then drops the audio that the pipeline still holds. After [close] it does nothing. */
    fun stopMicrophone()

    /** The options of the voice that the session speaks with, or null when they are not known. */
    val voice: VoiceOptions? get() = null

    /** Speaks [text] in Kestrel's voice. The result completes as `VoicePlayer.speak`'s does. */
    fun speak(text: String): Deferred<Spoke>

    /** Stops the reply that plays and the replies that wait. */
    fun stopSpeaking()

    /**
     * Stops the microphone, then closes the pipeline, the player and the engines, in this order, each step also when an
     * earlier one failed, and then throws the first failure, with the later ones as suppressed exceptions. The session
     * cannot be used afterwards, and a later call does nothing. When it returns or throws, nothing more reaches the
     * listener: it waits for a call of the listener in progress, so the listener must not wait for it.
     */
    suspend fun close()
}

/** Where a session reports. */
internal interface SessionListener {
    /**
     * An event for the Orchestrator. It is called from the session's coroutines on
     * [kotlinx.coroutines.Dispatchers.Default], also on two threads at once, and must not throw: an exception would end
     * Kestrel's process.
     */
    fun send(event: Event)

    /** The microphone failed. It is called on the microphone's thread, so it must return at once and must not throw. */
    fun microphoneFailed(error: Exception)

    /**
     * Transcribing an utterance failed; the session goes on. It is called on a thread of
     * [kotlinx.coroutines.Dispatchers.Default], and must not throw: an exception would end Kestrel's process.
     */
    fun transcriptionFailed(error: Exception)
}

/** Opens voice sessions. */
internal interface VoiceSessions {
    /**
     * Opens a session that records with [microphone] and reports to [listener]. It loads the speech models, which takes
     * several seconds. Throws `ModelsNotReadyException` when a model is missing or damaged, after closing the engines
     * that it had already loaded.
     */
    suspend fun open(microphone: Microphone, listener: SessionListener): Session
}
