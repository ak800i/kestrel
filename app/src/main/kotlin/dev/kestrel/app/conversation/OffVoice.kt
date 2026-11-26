package dev.kestrel.app.conversation

import android.content.Context
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Kestrel's own voice while no voice session is open (design 10.6 and 11 of sub-project 7). */
internal interface OffVoice {
    /** Speaks [text] and returns once it has been spoken, stopped or refused. */
    suspend fun speak(text: String)

    /** Stops the words that it speaks now, as the owner's Stop does (plan 7F). */
    fun stop()
}

/**
 * Kestrel's voice with listening off (design 10.6 and 11 of sub-project 7; plan 7F). With "Speak replies to typed commands"
 * on, a typed conversation's words reach the voice channel while Kestrel is off, and this speaks them with the owner's voice
 * and speed ([options]), on the assistant stream as Kestrel always speaks (spec 8.4), inside [foreground], which adds
 * mediaPlayback to the goal's service as Android 17 asks of audio in the background (plan 7E). The voice loads for the
 * first reply and closes [idleMillis] after the last one, so that a goal's words and its report load it once. A voice
 * that does not load is reported by its class, and the words stay on the bar. One reply at a time, in order.
 */
internal class AppOffVoice(
    private val context: Context,
    private val engines: VoiceEngines,
    private val options: suspend () -> VoiceOptions,
    private val foreground: suspend (suspend () -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val report: (String, Throwable) -> Unit,
    private val idleMillis: Long = IDLE_MILLIS,
) : OffVoice {
    private class Loaded(val options: VoiceOptions, val synthesizer: Synthesizer, val player: VoicePlayer)

    private val mutex = Mutex()

    // Changed under the mutex; read by stop() without it.
    @Volatile
    private var loaded: Loaded? = null
    private var closing: Job? = null
    private val spoken = AtomicInteger()

    /** How many replies it has spoken in this process, for the device tests. */
    val replies: Int get() = spoken.get()

    override suspend fun speak(text: String) {
        mutex.withLock {
            closing?.cancel()
            val voice = try {
                voiceFor(options())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Loading Kestrel's voice with listening off", e)
                return
            }
            foreground { voice.player.speak(text).await() }
            spoken.incrementAndGet()
            closing = scope.launch {
                delay(idleMillis)
                mutex.withLock { unload() }
            }
        }
    }

    private suspend fun voiceFor(wanted: VoiceOptions): Loaded {
        loaded?.takeIf { it.options == wanted }?.let { return it }
        unload()
        val synthesizer = withContext(Dispatchers.Default) { engines.synthesizer(wanted) }
        return Loaded(wanted, synthesizer, VoicePlayer(context, synthesizer)).also { loaded = it }
    }

    // As VoiceSession.close does: the player stops using the voice before the voice closes.
    private suspend fun unload() {
        val current = loaded ?: return
        loaded = null
        withContext(NonCancellable) {
            current.player.close()
            current.synthesizer.close()
        }
    }

    override fun stop() {
        loaded?.player?.stop()
    }

    override fun toString(): String = "AppOffVoice(loaded=${loaded != null}, replies=$replies)"

    companion object {
        /** How long the voice stays loaded after the last reply. */
        const val IDLE_MILLIS = 30_000L
    }
}
