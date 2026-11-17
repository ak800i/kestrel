package dev.kestrel.app.commands

import dev.kestrel.app.listen.Session
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Voice sessions for the command device tests (design D15 of sub-project 3): a session needs no speech models, never
 * records, finishes each reply at once and remembers what Kestrel said. The tests send their transcripts as events.
 */
internal class TextSessions : VoiceSessions {
    private val said = mutableListOf<String>()

    /** What Kestrel said, in order. */
    val spoken: List<String> get() = synchronized(said) { said.toList() }

    override suspend fun open(microphone: Microphone, listener: SessionListener): Session = object : Session {
        override var recognition: Recognition = Recognition.ALL

        override fun startMicrophone() = Unit

        override fun stopMicrophone() = Unit

        override fun speak(text: String): Deferred<Spoke> {
            synchronized(said) { said += text }
            return CompletableDeferred(Spoke.FINISHED)
        }

        override fun stopSpeaking() = Unit

        override suspend fun close() = Unit
    }
}
