package dev.kestrel.app.debug

import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.VoiceOptions
import java.util.Locale

/**
 * The listening hooks (design spec, section 16.1): they send events to Kestrel's Orchestrator, read its state, and say a
 * text into a stand-in microphone. The answers never contain what was said. `scripts/hook.ps1` lists them.
 */
internal class VoiceHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE" -> event(intent)
        "VOICE_STATE" -> state()
        else -> clip(intent)
    }

    private fun event(intent: Intent): String {
        val name = intent.getStringExtra(EXTRA_EVENT)
        val event = when (name) {
            "toggle" -> Event.Toggle
            "pause" -> Event.Pause
            "resume" -> Event.Resume
            "off" -> Event.TurnOff
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
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "screenOn=${state.screenOn} micBusy=${state.micBusy} session=$session"
    }

    private suspend fun clip(intent: Intent): String {
        val text = text(intent)
        // Without variation, the voice says a text the same way every time.
        val samples = graph.voice.synthesizer(VoiceOptions(variation = 0f)).use { voice -> ClipMicrophone.speech(voice, text) }
        val clips = microphone
        graph.listening.microphones = { clips }
        clips.play(samples)
        val seconds = String.format(Locale.ROOT, "%.1f", samples.size / SAMPLE_RATE.toFloat())
        return if (clips.recording) {
            "playing $seconds s of speech through the clip microphone"
        } else {
            "queued $seconds s of speech: Kestrel hears it through the clip microphone from its next start, and only " +
                "clips until its process ends"
        }
    }

    private fun text(intent: Intent): String = intent.getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    companion object {
        val COMMANDS = setOf("VOICE", "VOICE_STATE", "VOICE_CLIP")

        /** The microphone that VOICE_CLIP says its texts into, one for the whole process. */
        val microphone: ClipMicrophone by lazy { ClipMicrophone() }

        private val EVENTS = listOf("toggle", "pause", "resume", "off", "screen-on", "screen-off", "heard")
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_NAMED = "named"
    }
}
