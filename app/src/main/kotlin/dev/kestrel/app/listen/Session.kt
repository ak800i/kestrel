package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
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
     * [stopMicrophone] releases whatever the start had opened.
     */
    fun startMicrophone()

    /** Stops the microphone, and then drops the audio that the pipeline still holds. */
    fun stopMicrophone()

    /** Speaks [text] in Kestrel's voice. The result completes as `VoicePlayer.speak`'s does. */
    fun speak(text: String): Deferred<Spoke>

    /** Stops the reply that plays and the replies that wait. */
    fun stopSpeaking()

    /**
     * Stops the microphone, then closes the pipeline, the player and the engines, in this order, each step also when an
     * earlier one failed, and then throws the first failure. The session cannot be used afterwards.
     */
    suspend fun close()
}

/** Where a session reports. */
internal interface SessionListener {
    /** An event for the Orchestrator. Any thread may call it. */
    fun send(event: Event)

    /** The microphone failed. It is called on the microphone's thread, so it must return at once and must not throw. */
    fun microphoneFailed(error: Exception)

    /** Transcribing an utterance failed; the session goes on. */
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
