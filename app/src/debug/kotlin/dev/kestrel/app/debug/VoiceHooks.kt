package dev.kestrel.app.debug

import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/**
 * The listening hooks (design spec, section 16.1): they send events to Kestrel's Orchestrator, read its state, and say a
 * text into a stand-in microphone. The answers never contain what was said. `scripts/hook.ps1` lists them.
 */
internal class VoiceHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE" -> event(intent)
        "VOICE_STATE" -> state()
        "VOICE_CLIP" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clear() else clip(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private suspend fun event(intent: Intent): String {
        val name = intent.getStringExtra(EXTRA_EVENT)
        val event = when (name) {
            "toggle" -> Event.Toggle
            "pause" -> Event.Pause
            "resume" -> Event.Resume
            "off" -> {
                // No restore may turn Kestrel on in this process from now on. Then Off is stored at once, also while Kestrel
                // is off, as it is in a new process, where TurnOff changes nothing and so stores nothing: Kestrel must not
                // come back at the next start of its process.
                graph.listening.skipRestore()
                graph.settings.set(SettingKeys.LISTENING, ListeningSetting())
                Event.TurnOff
            }
            "screen-on" -> Event.Screen(on = true)
            "screen-off" -> Event.Screen(on = false)
            "heard" -> Event.Heard(text(intent), intent.getBooleanExtra(EXTRA_NAMED, false))
            else -> throw HookError("add --es event with one of ${EVENTS.joinToString()}")
        }
        graph.listening.send(event)
        return "sent $name"
    }

    private fun state(): String {
        val state = graph.listening.state.value
        val session = if (graph.listening.sessionOpen.value) "open" else "closed"
        val clips = if (graph.listening.microphones === clipFactory) "on" else "off"
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "screenOn=${state.screenOn} micBusy=${state.micBusy} session=$session clips=$clips"
    }

    private suspend fun clip(intent: Intent): String {
        val text = text(intent)
        if (text.isBlank()) throw HookError("add a text that is not blank")
        val samples = try {
            // Without variation, the voice says a text the same way every time.
            graph.voice.synthesizer(VoiceOptions(variation = 0f)).use { voice -> ClipMicrophone.speech(voice, text) }
        } catch (e: ModelsNotReadyException) {
            throw HookError("the voice model is missing; run scripts\\push-models.ps1")
        }
        if (samples.size == PADDING) throw HookError("the voice said nothing for the text")
        // The receiver's time limit cannot stop a synthesis, so a hook that ran out of time changes nothing.
        currentCoroutineContext().ensureActive()
        val clips = microphone
        synchronized(lock) {
            val listening = graph.listening
            if (listening.microphones !== clipFactory) {
                replaced = listening.microphones
                listening.microphones = clipFactory
            }
            clips.play(samples)
        }
        val seconds = String.format(Locale.ROOT, "%.1f", samples.size / SAMPLE_RATE.toFloat())
        return if (clips.recording) {
            "playing $seconds s of speech through the clip microphone"
        } else {
            "queued $seconds s of speech: Kestrel hears it when the clip microphone records, which is from Kestrel's " +
                "next start, or when the screen comes back on if Kestrel already listens through it; Kestrel hears only " +
                "clips until voice-clip -Clear or the end of its process"
        }
    }

    private fun clear(): String {
        microphone.clear()
        val restored = synchronized(lock) {
            val previous = replaced
            replaced = null
            val listening = graph.listening
            // Only a factory that VOICE_CLIP set is undone; one that someone set since then stays.
            if (previous != null && listening.microphones === clipFactory) {
                listening.microphones = previous
                true
            } else {
                false
            }
        }
        return if (restored) {
            "dropped the waiting clips; Kestrel uses its own microphone again from its next start"
        } else {
            "dropped the waiting clips; Kestrel's next start does not use the clip microphone"
        }
    }

    private fun text(intent: Intent): String = intent.getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    companion object {
        val COMMANDS = setOf("VOICE", "VOICE_STATE", "VOICE_CLIP")

        /** The microphone that VOICE_CLIP says its texts into, one for the whole process. */
        val microphone: ClipMicrophone by lazy { ClipMicrophone() }

        // One factory object, so that VOICE_STATE and VOICE_CLIP can tell whether Kestrel's next start uses the clips.
        private val clipFactory: () -> Microphone = { microphone }

        private val lock = Any()

        // The factory that VOICE_CLIP replaced with clipFactory, which VOICE_CLIP --ez clear true puts back.
        private var replaced: (() -> Microphone)? = null

        // ClipMicrophone.speech's silence before and after the text.
        private const val PADDING = SAMPLE_RATE / 2 + SAMPLE_RATE

        private val EVENTS = listOf("toggle", "pause", "resume", "off", "screen-on", "screen-off", "heard")
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_NAMED = "named"
        private const val EXTRA_CLEAR = "clear"
    }
}
