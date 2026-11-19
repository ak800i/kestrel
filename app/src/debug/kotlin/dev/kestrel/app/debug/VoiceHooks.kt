package dev.kestrel.app.debug

import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.settings.speedText
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/**
 * The listening hooks (design spec, section 16.1): they send events to Kestrel's Orchestrator, read its state, say a
 * text into a stand-in microphone, and read or set the choice "Commands during a call" and the voice. The answers never
 * contain what was said. `scripts/hook.ps1` lists them.
 */
internal class VoiceHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE" -> event(intent)
        "VOICE_STATE" -> state()
        "VOICE_CLIP" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clear() else clip(intent)
        "VOICE_CALL_COMMANDS" -> callCommands(intent)
        "VOICE_CHOICE" -> voiceChoice(intent)
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
        // The latest instant command: its number, its kind and how it ended, never its words.
        val report = graph.listening.lastReport?.takeIf { it.command == state.command }
        val kind = state.running?.javaClass?.simpleName ?: report?.kind ?: "none"
        val outcome = when {
            state.work == Work.RUNNING_COMMAND -> "running"
            report != null -> report.outcome.name.lowercase()
            else -> "none"
        }
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "screenOn=${state.screenOn} micBusy=${state.micBusy} command=${state.command} kind=$kind outcome=$outcome " +
            "session=$session clips=$clips"
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

    // Reads the stored choice "Commands during a call", or stores it first with --ez on, so that listen-check.ps1 can
    // check the call commands during a call and put the choice back (design spec, section 8.7). Kestrel takes a change
    // a moment later, as it takes one from the page "Listening and activation".
    private suspend fun callCommands(intent: Intent): String {
        if (intent.hasExtra(EXTRA_ON)) {
            val on = intent.getBooleanExtra(EXTRA_ON, false)
            val stored = graph.settings.get(SettingKeys.LISTENING_OPTIONS)
            graph.settings.set(SettingKeys.LISTENING_OPTIONS, stored.copy(callCommands = on))
        }
        return "callCommands=${if (graph.settings.get(SettingKeys.LISTENING_OPTIONS).callCommands) "on" else "off"}"
    }

    // Reads the stored voice, or stores it first with --es voice at the stored speed, so that the A71 session can measure
    // Kestrel's memory while it listens with Kokoro and put the owner's choice back (design spec, sections 5.3 and 15). A
    // new voice counts from Kestrel's next start. kokoroFailed: Kokoro was chosen, and Piper spoke instead the last time
    // that a voice session opened.
    private suspend fun voiceChoice(intent: Intent): String {
        intent.getStringExtra(EXTRA_VOICE)?.let { name ->
            val voice = when (name) {
                "piper" -> VoiceChoice.PIPER
                "kokoro" -> VoiceChoice.KOKORO
                else -> throw HookError("add --es voice with piper or kokoro")
            }
            // Plan 2E: choosing Kokoro forgets its stored speed, so that the session measures it at a known moment.
            if (voice == VoiceChoice.KOKORO) graph.settings.set(SettingKeys.KOKORO_SPEED, KokoroSpeed())
            graph.settings.set(SettingKeys.VOICE, graph.settings.get(SettingKeys.VOICE).copy(voice = voice))
        }
        val stored = graph.settings.get(SettingKeys.VOICE)
        // Kokoro's real-time factor on the phone, as Kestrel measured it for this model and install (plan 2E).
        val speed = graph.settings.get(SettingKeys.KOKORO_SPEED)
        val measured = speed.factor.takeIf { speed.measures(graph.voice.kokoroModel, graph.installedAt) }
        val slow = measured?.takeIf { it > 1f }
        return "voice=${stored.voice.name.lowercase()} speed=${speedText(stored.options().speed)} " +
            "kokoroFailed=${graph.kokoroFailed.value} kokoroSlow=${factorText(slow)} kokoroSpeed=${factorText(measured)}"
    }

    private fun factorText(factor: Float?): String {
        return factor?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "none"
    }

    private fun text(intent: Intent): String = intent.getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    companion object {
        val COMMANDS = setOf("VOICE", "VOICE_STATE", "VOICE_CLIP", "VOICE_CALL_COMMANDS", "VOICE_CHOICE")

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
        private const val EXTRA_ON = "on"
        private const val EXTRA_VOICE = "voice"
    }
}
