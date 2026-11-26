package dev.kestrel.app.conversation

import android.content.Context
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
    /**
     * Speaks [text] and returns once it has been spoken, stopped or refused, or could not be spoken: a voice that fails is
     * reported, and the words stay on the bar. A [stop] after this call ends the words, also before they start, and so does
     * the caller's cancellation, as when the owner answers the question that they ask.
     */
    suspend fun speak(text: String)

    /** The owner's Stop (plan 7F): ends the words that it speaks, or would speak, now. */
    fun stop()
}

/** A voice that [AppOffVoice] loaded: it speaks one reply at a time, and stops and closes as [VoicePlayer] does. */
internal interface LoadedVoice {
    fun speak(text: String): Deferred<Spoke>

    fun stop()

    suspend fun close()
}

/** Loads Kestrel's voice with the owner's [VoiceOptions] from [engines], and plays it on the assistant stream. */
internal fun playerVoices(context: Context, engines: VoiceEngines): suspend (VoiceOptions) -> LoadedVoice = { options ->
    val synthesizer = withContext(Dispatchers.Default) { engines.synthesizer(options) }
    try {
        PlayerVoice(VoicePlayer(context, synthesizer), synthesizer)
    } catch (e: Exception) {
        synthesizer.close()
        throw e
    }
}

private class PlayerVoice(private val player: VoicePlayer, private val synthesizer: Synthesizer) : LoadedVoice {
    override fun speak(text: String): Deferred<Spoke> = player.speak(text)

    override fun stop() = player.stop()

    // As VoiceSession.close does: the player stops using the voice before the voice closes.
    override suspend fun close() {
        player.close()
        synthesizer.close()
    }
}

/**
 * Kestrel's voice with listening off (design 10.6 and 11 of sub-project 7; plan 7F). With "Speak replies to typed commands"
 * on, a typed conversation's words reach the voice channel while Kestrel is off, and this speaks them with the owner's voice
 * and speed ([options]), on the assistant stream as Kestrel always speaks (spec 8.4), inside [foreground], which adds
 * mediaPlayback to the goal's service as Android 17 asks of audio in the background (plan 7E). The voice loads ([load]) for
 * the first reply and closes [idleMillis] after the last one has ended in any way, so that a goal's words and its report
 * load it once. A voice that does not load or does not speak is reported by its class, and the words stay on the bar. One
 * reply at a time, in order.
 *
 * The one stop rule: the owner's [stop] ends the reply that it speaks, and a reply that was asked for before the stop never
 * starts, whether it waits for its turn, for the voice to load or for the service's mediaPlayback. A reply whose caller is
 * cancelled ends as well.
 */
internal class AppOffVoice(
    private val load: suspend (VoiceOptions) -> LoadedVoice,
    private val options: suspend () -> VoiceOptions,
    private val foreground: suspend (suspend () -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val report: (String, Throwable) -> Unit,
    private val idleMillis: Long = IDLE_MILLIS,
) : OffVoice {
    private class Loaded(val options: VoiceOptions, val voice: LoadedVoice)

    private val mutex = Mutex()

    // Changed under the mutex; read by stop() without it.
    @Volatile
    private var loaded: Loaded? = null
    private var closing: Job? = null
    private val spoken = AtomicInteger()

    // How many times the owner stopped the voice. A reply starts only under [stopLock] and only while the count is the one
    // that it saw when it was asked for, and a stop counts and stops the voice under the same lock, so that no reply starts
    // after a stop that came after it was asked for.
    private val stopLock = Any()
    private var stops = 0L

    /** How many replies it has spoken in this process, for the device tests. */
    val replies: Int get() = spoken.get()

    override suspend fun speak(text: String) {
        val asked = synchronized(stopLock) { stops }
        mutex.withLock {
            closing?.cancel()
            try {
                say(text, asked)
            } finally {
                closing = scope.launch {
                    delay(idleMillis)
                    mutex.withLock { unload() }
                }
            }
        }
    }

    private suspend fun say(text: String, asked: Long) {
        val voice = try {
            voiceFor(options())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Loading Kestrel's voice with listening off", e)
            return
        }
        if (stoppedSince(asked)) return
        try {
            foreground { play(voice, text, asked) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Speaking with Kestrel's voice with listening off", e)
        }
    }

    private suspend fun play(voice: LoadedVoice, text: String, asked: Long) {
        val reply = synchronized(stopLock) { if (stops == asked) voice.speak(text) else null } ?: return
        try {
            reply.await()
        } finally {
            if (!reply.isCompleted) voice.stop()
        }
        spoken.incrementAndGet()
    }

    private fun stoppedSince(asked: Long): Boolean = synchronized(stopLock) { stops != asked }

    private suspend fun voiceFor(wanted: VoiceOptions): LoadedVoice {
        loaded?.takeIf { it.options == wanted }?.let { return it.voice }
        unload()
        // A voice that loaded is always kept, also when the caller is cancelled meanwhile, so that it closes with the idle
        // unload and never leaves its native memory behind.
        val voice = withContext(NonCancellable) { load(wanted) }
        loaded = Loaded(wanted, voice)
        return voice
    }

    private suspend fun unload() {
        val current = loaded ?: return
        loaded = null
        withContext(NonCancellable) { current.voice.close() }
    }

    override fun stop() {
        synchronized(stopLock) {
            stops++
            loaded?.voice?.stop()
        }
    }

    override fun toString(): String = "AppOffVoice(loaded=${loaded != null}, replies=$replies)"

    companion object {
        /** How long the voice stays loaded after the last reply. */
        const val IDLE_MILLIS = 30_000L
    }
}
