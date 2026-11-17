package dev.kestrel.app.debug

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.MicCapture
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hooks of the A71 session (plan 2D, design D8), which scripts\a71-session.ps1 uses. VOICE_COUNTS answers how many
 * waits for a task and how many tasks Kestrel has started in its process, and how many words the last slow task had, so
 * that the session counts Kestrel's reactions, also false ones, without reading what Kestrel heard. VOICE_TASK makes each
 * task take some seconds and then say a given reply, in place of the task that ends at once, so that the owner can
 * interrupt a long reply or say "stop" while a task runs; a task that Kestrel cancelled meanwhile ends without its reply.
 * VOICE_ECHO plays a text in one of Kestrel's voices through the speaker while the real microphone feeds the keyword
 * spotter, and counts the keyword hits and those that the echo filter takes for Kestrel's own voice (design spec, section
 * 8.4); it runs in the background, longer than a hook may run, and `result` asks for its answer. Their answers hold only
 * counts and lengths.
 */
internal class SessionHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE_COUNTS" -> graph.listening.state.value.let {
            "waits=${it.wait} tasks=${it.task} lastTaskWords=$lastTaskWords"
        }
        "VOICE_TASK" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clearTask() else task(intent)
        "VOICE_ECHO" -> if (intent.getBooleanExtra(EXTRA_RESULT, false)) echoResult() else startEcho(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private fun task(intent: Intent): String {
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        if (seconds !in 1..MAX_TASK_SECONDS) throw HookError("add --ei seconds with 1 to $MAX_TASK_SECONDS")
        val reply = intent.getStringExtra(EXTRA_REPLY)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es reply <text>")
        val listening = graph.listening
        synchronized(lock) {
            if (replacedTask == null) replacedTask = listening.runTask
            listening.runTask = { task, text ->
                // A count, never the words, so that the session can tell a dictated task that was split.
                lastTaskWords = text.split(Regex("\\s+")).count { it.isNotEmpty() }
                graph.scope.launch {
                    delay(seconds * 1_000L)
                    // A task that Kestrel cancelled meanwhile ends in silence, so that the next try starts quiet.
                    val state = listening.state.value
                    val running = state.task == task && state.work == Work.RUNNING_TASK
                    listening.send(Event.TaskEnded(task, reply.takeIf { running }))
                }
            }
        }
        return "each task now takes $seconds s and then says a reply of ${reply.length} characters, until voice-task -Clear " +
            "or the end of Kestrel's process"
    }

    private fun clearTask(): String {
        synchronized(lock) {
            replacedTask?.let { graph.listening.runTask = it }
            replacedTask = null
        }
        return "each task ends at once again"
    }

    private fun startEcho(intent: Intent): String {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text <text>")
        val choice = when (intent.getStringExtra(EXTRA_VOICE) ?: "piper") {
            "piper" -> VoiceChoice.PIPER
            "kokoro" -> VoiceChoice.KOKORO
            else -> throw HookError("add --es voice with piper or kokoro")
        }
        if (graph.listening.sessionOpen.value) throw HookError("turn Kestrel off first, so that its microphone is free")
        synchronized(lock) {
            if (echo === Echo.Playing) throw HookError("an echo check runs; ask for its answer with --ez result true")
            echo = Echo.Playing
            graph.scope.launch {
                val ended = try {
                    Echo.Ended(echo(text, choice), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HookError) {
                    Echo.Ended(null, e.message)
                } catch (e: Exception) {
                    // Only the class, never a message (section 13.5).
                    Echo.Ended(null, "the echo check failed (${e.javaClass.simpleName})")
                }
                synchronized(lock) { echo = ended }
            }
        }
        return "playing"
    }

    private fun echoResult(): String {
        val current = synchronized(lock) { echo.also { if (it is Echo.Ended) echo = null } }
        return when (current) {
            null -> throw HookError("no echo check; start one with --es text")
            Echo.Playing -> "playing"
            is Echo.Ended -> current.answer ?: throw HookError(current.failure ?: "the echo check failed")
        }
    }

    private suspend fun echo(text: String, choice: VoiceChoice): String {
        val voice = try {
            graph.voice.synthesizer(VoiceOptions(voice = choice))
        } catch (e: ModelsNotReadyException) {
            throw HookError("the voice's model is missing; run scripts\\push-models.ps1 -Kokoro")
        }
        val hits = AtomicInteger()
        val own = AtomicInteger()
        val result = voice.use {
            graph.voice.keywordSpotter().use { spotter ->
                val player = VoicePlayer(context, voice)
                val filter = EchoFilter()
                val microphone = MicCapture(context)
                try {
                    // The spotter serves only the microphone's thread until the microphone stops.
                    microphone.start(
                        onFrame = { frame ->
                            for (hit in spotter.accept(frame)) {
                                hits.incrementAndGet()
                                if (filter.isOwnVoice(hit.keyword, SystemClock.elapsedRealtime(), player.recent())) own.incrementAndGet()
                            }
                        },
                        onError = {},
                    )
                    val spoke = player.speak(text).await()
                    // The spotter reports a keyword up to about 0.55 s after the keyword ends (plan 2A).
                    delay(1_500)
                    spoke.name.lowercase()
                } catch (e: SecurityException) {
                    throw HookError("Kestrel may not record; allow the microphone")
                } finally {
                    microphone.stop()
                    withContext(NonCancellable) { player.close() }
                }
            }
        }
        return "result=$result hits=${hits.get()} own=${own.get()}"
    }

    private sealed interface Echo {
        object Playing : Echo

        class Ended(val answer: String?, val failure: String?) : Echo
    }

    companion object {
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TASK", "VOICE_ECHO")

        private val lock = Any()

        // The task that VOICE_TASK replaced, which VOICE_TASK --ez clear true puts back.
        private var replacedTask: ((Int, String) -> Unit)? = null
        private var echo: Echo? = null

        @Volatile
        private var lastTaskWords = 0

        private const val MAX_TASK_SECONDS = 120
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_REPLY = "reply"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_RESULT = "result"
    }
}
