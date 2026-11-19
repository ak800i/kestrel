package dev.kestrel.app.debug

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.AndroidListeningConditions
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.KeywordTuning
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.MicCapture
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The hooks of the A71 session (plan 2D, design D8), which scripts\a71-session.ps1 uses. VOICE_COUNTS answers the number
 * of Kestrel's latest wait for a task, which rises with each wait and also when a wait starts again, how many tasks
 * Kestrel has started in its process, how many words the last slow task had, and how many keyword hits its voice sessions
 * reported, so that the session counts Kestrel's reactions and the spotter's hits, also false ones, without reading what
 * Kestrel heard. VOICE_TASK makes each
 * task take some seconds and then say a given reply, in place of the task that ends at once, so that the owner can
 * interrupt a long reply or say "stop" while a task runs; a task that Kestrel cancelled meanwhile ends without its reply.
 * VOICE_ECHO plays a text in one of Kestrel's voices through the speaker while the real microphone feeds the keyword
 * spotter, and counts the keyword hits and those that the echo filter takes for Kestrel's own voice (design spec, section
 * 8.4), with the loudest sample that the microphone heard and whether Android silenced it, so that a run that heard
 * nothing shows; it runs in the background, longer than a hook may run, and `result` asks for its answer. Like
 * VOICE_RECORD, it checks only at its start that no voice session is open, and it does not look at the screen: a session
 * that opens during the check shares the microphone, and Android silences a recording from the background, which the
 * answer shows. VOICE_COUNTS also counts the keyword hits by keyword, the transcribed utterances, those that began with
 * the name, their audio in milliseconds and how Requests read each one, and VOICE_TIMES answers the phone's own times of
 * Kestrel's starts, sessions, ends of speech, reactions, turn-offs and settings, and whether Android runs the listening
 * service in the foreground (plan 2E). VOICE_ROUTE answers the earbud option and the route of the newest recording
 * (MicCapture.routeReport), so that the A71 session sees where the earbuds' recording goes. Their answers hold only
 * counts, lengths, levels, times, device types and the names of kinds. VOICE_SPOTTER sets the keyword spotter's boost and
 * threshold for every keyword spotter that loads afterwards, also the spotter of VOICE_ECHO, until the hook sets them
 * again or Kestrel's process ends, so that the session can try other values without a new build. OWNER_OPTIONS and
 * NOTICES let the A71 sessions put back the owner's options and take away Kestrel's notices at their end.
 */
internal class SessionHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE_COUNTS" -> graph.listening.state.value.let {
            val counts = graph.listening.voiceCounts.describe()
            "waits=${it.wait} tasks=${it.task} lastTaskWords=$lastTaskWords hits=${graph.listening.keywordHits}" +
                if (counts.isEmpty()) "" else " $counts"
        }
        "VOICE_TIMES" -> times()
        "VOICE_ROUTE" -> "earbud=${if (graph.listening.currentOptions.value.earbudMicrophone) "on" else "off"} " +
            (graph.latestMicrophone?.routeReport() ?: "microphone=none")
        "VOICE_SPOTTER" -> spotter(intent)
        "VOICE_TASK" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clearTask() else task(intent)
        "VOICE_ECHO" -> if (intent.getBooleanExtra(EXTRA_RESULT, false)) echoResult() else startEcho(intent)
        "OWNER_OPTIONS" -> ownerOptions(intent)
        "NOTICES" -> notices(intent)
        else -> throw HookError("unknown command '$command'")
    }

    /**
     * The owner's options that the A71 sessions ask him to change, "Require verbs" (CommandOptions) and "Use the earbud
     * microphone" (ListeningOptions), after storing --ez requireVerbs and --ez earbudMicrophone, so that a session puts them
     * back at its end, also after a failure, a skip or Ctrl+C. Kestrel takes a change a moment later, as from its pages.
     */
    private suspend fun ownerOptions(intent: Intent): String {
        if (intent.hasExtra(EXTRA_REQUIRE_VERBS)) {
            val commands = graph.settings.get(SettingKeys.COMMANDS)
            graph.settings.set(SettingKeys.COMMANDS, commands.copy(requireVerbs = intent.getBooleanExtra(EXTRA_REQUIRE_VERBS, false)))
        }
        if (intent.hasExtra(EXTRA_EARBUD_MICROPHONE)) {
            val listening = graph.settings.get(SettingKeys.LISTENING_OPTIONS)
            graph.settings.set(SettingKeys.LISTENING_OPTIONS, listening.copy(earbudMicrophone = intent.getBooleanExtra(EXTRA_EARBUD_MICROPHONE, false)))
        }
        val requireVerbs = graph.settings.get(SettingKeys.COMMANDS).requireVerbs
        val earbud = graph.settings.get(SettingKeys.LISTENING_OPTIONS).earbudMicrophone
        return "requireVerbs=${if (requireVerbs) "on" else "off"} earbudMicrophone=${if (earbud) "on" else "off"}"
    }

    /**
     * How many of Kestrel's notices about problems (ProblemNotifications: Kestrel stopped listening, its accessibility
     * service is off, "Open Kestrel's settings") are in the shade, after cancelling them with --ez clear, so that a session
     * takes away the notices that its steps caused, at its end. The listening and sign-in notifications stay.
     */
    private fun notices(intent: Intent): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: throw HookError("the notification service is missing")
        val notices = setOf(ProblemNotifications.STOPPED_ID, ProblemNotifications.ACCESSIBILITY_ID, ProblemNotifications.SETTINGS_ID)
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) notices.forEach(manager::cancel)
        return "notices=${manager.activeNotifications.count { it.id in notices }}"
    }

    // The phone's own times (plan 2E), and whether Android runs ListeningService in the foreground: foreground= from
    // Android's list of Kestrel's running services, demoted= Android's reading of whether the service lost its
    // foreground (AndroidListeningConditions.demoted), on which Kestrel acts only while its battery use is also
    // Restricted, and none while no service runs.
    private suspend fun times(): String {
        val conditions = AndroidListeningConditions(context)
        val service = withContext(Dispatchers.Main) { ListeningService.running() }
        val foreground = conditions.listedForeground()?.toString() ?: "none"
        val demoted = service?.let { conditions.demoted(it).toString() } ?: "none"
        return "${graph.timeline.describe()} foreground=$foreground demoted=$demoted"
    }

    // The spotter's boost and threshold, after storing --ef boost and --ef threshold (plan 2E).
    private fun spotter(intent: Intent): String {
        if (intent.hasExtra(EXTRA_BOOST) || intent.hasExtra(EXTRA_THRESHOLD)) {
            val now = graph.voice.keywordTuning
            graph.voice.keywordTuning = try {
                val boost = intent.getFloatExtra(EXTRA_BOOST, now.boost)
                KeywordTuning(boost, intent.getFloatExtra(EXTRA_THRESHOLD, now.threshold))
            } catch (e: IllegalArgumentException) {
                throw HookError("add --ef boost from 0.5 to 10 and --ef threshold from 0.01 to 0.5")
            }
        }
        val tuning = graph.voice.keywordTuning
        return "boost=${tuning.boost} threshold=${tuning.threshold}"
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
            val push = if (choice == VoiceChoice.KOKORO) "scripts\\push-models.ps1 -Kokoro" else "scripts\\push-models.ps1"
            throw HookError("the voice's model is missing; run $push")
        }
        val hits = AtomicInteger()
        val own = AtomicInteger()
        // The loudest sample in percent, whether Android silenced the microphone at any time, and its first error, so that
        // a run that heard nothing does not look like one in which the filter let no hit through.
        val loudest = AtomicInteger()
        val silenced = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>(null)
        val result = voice.use {
            graph.voice.keywordSpotter().use { spotter ->
                val player = VoicePlayer(context, voice)
                val filter = EchoFilter()
                val microphone = MicCapture(context)
                try {
                    // The spotter serves only the microphone's thread until the microphone stops.
                    microphone.start(
                        onFrame = { frame ->
                            val level = ((frame.maxOfOrNull { abs(it) } ?: 0f) * 100).roundToInt()
                            loudest.accumulateAndGet(level) { a, b -> maxOf(a, b) }
                            if (microphone.silenced.value) silenced.set(true)
                            for (hit in spotter.accept(frame)) {
                                hits.incrementAndGet()
                                if (filter.isOwnVoice(hit.keyword, SystemClock.elapsedRealtime(), player.recent())) own.incrementAndGet()
                            }
                        },
                        onError = { error -> failure.compareAndSet(null, error) },
                    )
                    val spoke = player.speak(text).await()
                    // The spotter reports a keyword up to about 0.55 s after the keyword ends (plan 2A).
                    delay(1_500)
                    if (microphone.silenced.value) silenced.set(true)
                    // Only the class, never a message (section 13.5).
                    failure.get()?.let { throw HookError("the microphone failed (${it.javaClass.simpleName})") }
                    spoke.name.lowercase()
                } catch (e: SecurityException) {
                    throw HookError("Kestrel may not record; allow the microphone")
                } finally {
                    microphone.stop()
                    withContext(NonCancellable) { player.close() }
                }
            }
        }
        return "result=$result hits=${hits.get()} own=${own.get()} loudest=${loudest.get()} silenced=${silenced.get()}"
    }

    private sealed interface Echo {
        object Playing : Echo

        class Ended(val answer: String?, val failure: String?) : Echo
    }

    companion object {
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TIMES", "VOICE_ROUTE", "VOICE_SPOTTER", "VOICE_TASK", "VOICE_ECHO", "OWNER_OPTIONS", "NOTICES")

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
        private const val EXTRA_BOOST = "boost"
        private const val EXTRA_THRESHOLD = "threshold"
        private const val EXTRA_REQUIRE_VERBS = "requireVerbs"
        private const val EXTRA_EARBUD_MICROPHONE = "earbudMicrophone"
    }
}
