package dev.kestrel.app.debug

import dev.kestrel.app.conversation.LoadedVoice
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Device tests only: the playback of Kestrel's own voice with listening off (`AppGraph.ownVoicePlayback`; plan 7F), and the
 * one count of what that voice spoke (the whole-plan review's M4). It counts how each reply ended for Kestrel ([ended]), so
 * that a test tells words that were heard ([Spoke.FINISHED]) from words that were refused, stopped or interrupted, and
 * knows whether Kestrel's own player still plays a reply ([playing]), without reading other apps' players. A reply whose
 * words [holds] picks is held after its audio has played to the end, until [release], which ends it as spoken, or a stop of
 * the voice, which ends it as stopped, so that a test finds it still playing ([holding]) however long its audio is. The
 * other replies end as their player ends them.
 */
internal class OwnVoiceWatch(private val holds: (String) -> Boolean) {
    private class Waiting(val voice: LoadedVoice, val result: CompletableDeferred<Spoke>)

    private val lock = Any()
    private val ends = mutableMapOf<Spoke, Int>()
    private var playingNow = 0
    private var heldNow = 0
    private var released = false
    private var waiting: Waiting? = null

    /** Plays each reply of [voice] under this watch; the value for `AppGraph.ownVoicePlayback`. */
    fun wrap(voice: LoadedVoice): LoadedVoice = Watched(voice)

    /**
     * How many replies ended for Kestrel with [spoke]: a held reply as its release or a stop ended it, another as its
     * player ended it.
     */
    fun ended(spoke: Spoke): Int = synchronized(lock) { ends[spoke] ?: 0 }

    /** Whether Kestrel's own player has a reply that it has not ended yet. */
    val playing: Boolean get() = synchronized(lock) { playingNow > 0 }

    /** Whether a held reply has started and has not ended yet. */
    val holding: Boolean get() = synchronized(lock) { heldNow > 0 }

    /** Ends the held reply that waits, as spoken, and lets every later one end as its player ends it. */
    fun release() {
        val held = synchronized(lock) {
            released = true
            waiting.also { waiting = null }
        }
        held?.result?.complete(Spoke.FINISHED)
    }

    private inner class Watched(private val voice: LoadedVoice) : LoadedVoice {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun speak(text: String): Deferred<Spoke> {
            val hold = holds(text)
            val result = CompletableDeferred<Spoke>()
            if (hold) synchronized(lock) { heldNow++ }
            result.invokeOnCompletion { failure ->
                val spoke = if (failure == null) result.getCompleted() else null
                synchronized(lock) {
                    if (hold) heldNow--
                    if (spoke != null) ends[spoke] = (ends[spoke] ?: 0) + 1
                }
            }
            val played = voice.speak(text)
            synchronized(lock) { playingNow++ }
            played.invokeOnCompletion { failure ->
                val spoke = if (failure == null) played.getCompleted() else null
                val waits = synchronized(lock) {
                    playingNow--
                    (hold && spoke == Spoke.FINISHED && !released).also { if (it) waiting = Waiting(this, result) }
                }
                when {
                    failure != null -> result.completeExceptionally(failure)
                    !waits -> result.complete(checkNotNull(spoke))
                }
            }
            return result
        }

        // A stop ends the reply that plays, and the held reply of this voice that waits.
        override fun stop() {
            voice.stop()
            val held = synchronized(lock) { waiting?.takeIf { it.voice === this }?.also { waiting = null } }
            held?.result?.complete(Spoke.STOPPED)
        }

        override suspend fun close() = voice.close()
    }
}
