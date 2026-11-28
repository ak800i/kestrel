package dev.kestrel.app.debug

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.AndroidListeningConditions
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.listen.StopReason
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The hooks of the A71 session (plan 2D, design D8), which scripts\a71-session.ps1 uses. VOICE_COUNTS answers the number
 * of Kestrel's latest wait for a task, which rises with each wait and also when a wait starts again, the newest command
 * of the conversation in its process, how many words the newest goal had since the first voice-task or tagged transcript,
 * and how many keyword hits its voice sessions reported, so that the session counts Kestrel's reactions and the spotter's
 * hits, also false ones, without reading what Kestrel heard. VOICE_TASK makes each goal's round a stand-in
 * ([StandInRound]) that takes some seconds and then answers with a given reply, whole, as its final words, in place of a
 * round that ends at once without a script, so that the owner can interrupt a long reply or say "stop" while a goal
 * works; a goal that the owner stopped meanwhile ends with the report of a stop, as any goal does.
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
    suspend fun run(command: String, extras: Bundle): String = when (command) {
        "VOICE_COUNTS" -> graph.listening.state.value.let {
            val counts = graph.listening.voiceCounts.describe()
            "waits=${it.wait} tasks=${graph.conversations.view.value.command} lastTaskWords=${VoiceHooks.tags.lastGoalWords} " +
                "hits=${graph.listening.keywordHits}" + if (counts.isEmpty()) "" else " $counts"
        }
        "VOICE_TIMES" -> times()
        "VOICE_ROUTE" -> "earbud=${if (graph.listening.currentOptions.value.earbudMicrophone) "on" else "off"} " +
            (graph.latestMicrophone?.routeReport() ?: "microphone=none")
        "VOICE_SPOTTER" -> spotter(extras)
        "VOICE_TASK" -> if (extras.getBoolean(EXTRA_CLEAR, false)) clearTask() else task(extras)
        "VOICE_ECHO" -> if (extras.getBoolean(EXTRA_RESULT, false)) echoResult() else startEcho(extras)
        "OWNER_OPTIONS" -> ownerOptions(extras)
        "NOTICES" -> notices(extras)
        "CHECK_NOTICE" -> checkNotice(extras)
        "CLIPBOARD" -> clipboard(extras)
        else -> throw HookError("unknown command '$command'")
    }

    /**
     * The owner's options that the A71 sessions ask him to change, "Require verbs" (CommandOptions) and "Use the earbud
     * microphone" (ListeningOptions), after storing --ez requireVerbs and --ez earbudMicrophone, so that a session puts them
     * back at its end, also after a failure, a skip or Ctrl+C. Kestrel takes a change a moment later, as from its pages.
     */
    private suspend fun ownerOptions(extras: Bundle): String {
        if (extras.containsKey(EXTRA_REQUIRE_VERBS)) {
            val commands = graph.settings.get(SettingKeys.COMMANDS)
            graph.settings.set(SettingKeys.COMMANDS, commands.copy(requireVerbs = extras.getBoolean(EXTRA_REQUIRE_VERBS, false)))
        }
        if (extras.containsKey(EXTRA_EARBUD_MICROPHONE)) {
            val listening = graph.settings.get(SettingKeys.LISTENING_OPTIONS)
            graph.settings.set(SettingKeys.LISTENING_OPTIONS, listening.copy(earbudMicrophone = extras.getBoolean(EXTRA_EARBUD_MICROPHONE, false)))
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
    private suspend fun notices(extras: Bundle): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: throw HookError("the notification service is missing")
        val notices = setOf(ProblemNotifications.STOPPED_ID, ProblemNotifications.ACCESSIBILITY_ID, ProblemNotifications.SETTINGS_ID)
        if (extras.getBoolean(EXTRA_CLEAR, false)) {
            notices.forEach(manager::cancel)
            // Android takes them away a moment later, so the answer waits up to 2 s for that.
            withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                while (manager.activeNotifications.any { it.id in notices }) delay(POLL_MILLIS)
            }
        }
        // The kinds come from the IDs, and for the stop notice from its fixed text, read on the phone (plan 4D, H6).
        val active = manager.activeNotifications.filter { it.id in notices }
        val kinds = noticeKinds(active.map { it.id to it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() })
        return "notices=${active.size} kinds=$kinds"
    }

    /**
     * Posts (--ez on true) or takes away (--ez on false) Kestrel's own notification "Kestrel check", which the A71 checks
     * expand, collapse and dismiss by voice in place of the shell's notification, which no shell command can take away
     * (plan 4D, H6). Its text has two lines, so that One UI offers to expand it. Answers whether it is in the shade, after
     * waiting up to 2 s for Android to post or take it away.
     */
    private suspend fun checkNotice(extras: Bundle): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: throw HookError("the notification service is missing")
        fun shown() = manager.activeNotifications.any { it.id == CHECK_ID }
        if (extras.containsKey(EXTRA_ON)) {
            val on = extras.getBoolean(EXTRA_ON, false)
            if (on) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    throw HookError("Kestrel may not post notifications; allow them first")
                }
                val channel = NotificationChannel(CHECK_CHANNEL, CHECK_TITLE, NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) }
                manager.createNotificationChannel(channel)
                val notification = Notification.Builder(context, CHECK_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(CHECK_TITLE)
                    .setContentText(CHECK_TEXT)
                    .setStyle(Notification.BigTextStyle().bigText(CHECK_TEXT))
                    .build()
                manager.notify(CHECK_ID, notification)
            } else {
                manager.cancel(CHECK_ID)
                // So that the check leaves no channel of its own in Kestrel's notification settings.
                manager.deleteNotificationChannel(CHECK_CHANNEL)
            }
            withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                while (shown() != on) delay(POLL_MILLIS)
            }
        }
        return "checkNotice=${if (shown()) "shown" else "gone"}"
    }

    /**
     * The owner's clipboard around a check that copies (plan 4D). --ez save true keeps a copy of the clipboard in this
     * process's memory and answers its kind, text or other. Android 13 lets Kestrel read the clipboard only while its
     * window has the input focus, which comes a moment after Kestrel's settings come to the front, so a save waits up to
     * 2 s for that focus while the settings are in front. Android answers null both for an empty clipboard and for a
     * refused read, so null, or Kestrel's settings without the focus, is "unknown": nothing is saved, and a later restore
     * changes nothing. While a copy from an earlier save waits, a save keeps it and answers clip=held. --ez restore true
     * puts the copy back, which Android allows
     * from the background, and forgets it only once that worked; when Android refuses, as for a picture whose read access
     * ended with its clip, the copy is forgotten too, because Android never gives that access back, and the answer is
     * restored=failed. A restore never empties the clipboard.
     * --ez clear true empties the clipboard, for the emulator's tests, and is refused on every other device, so that it
     * never empties the owner's clipboard on a phone. The answers hold kinds only, never the content, and nothing is
     * written anywhere.
     */
    private suspend fun clipboard(extras: Bundle): String = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: throw HookError("the clipboard service is missing")
        when {
            extras.getBoolean(EXTRA_SAVE, false) -> {
                if (saved != null) return@withContext "clip=held saved=false"
                val timeline = graph.timeline
                val focused = withTimeoutOrNull(CHECK_WAIT_MILLIS) {
                    while (timeline.settingsInFront && !timeline.settingsFocused) delay(POLL_MILLIS)
                    timeline.settingsInFront && timeline.settingsFocused
                } ?: false
                if (!focused) return@withContext "clip=unknown saved=false"
                val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return@withContext "clip=unknown saved=false"
                saved = clip
                "clip=${kindOf(clip)} saved=true"
            }
            extras.getBoolean(EXTRA_RESTORE, false) -> {
                val held = saved ?: return@withContext "restored=false"
                try {
                    manager.setPrimaryClip(held)
                } catch (e: SecurityException) {
                    // Android never gives that access back, so the copy is forgotten, and a later save takes the owner's
                    // newer clip.
                    saved = null
                    return@withContext "restored=failed"
                }
                saved = null
                "restored=true"
            }
            extras.getBoolean(EXTRA_CLEAR, false) -> {
                if (clearRefused(Build.HARDWARE)) throw HookError(CLEAR_REFUSED)
                manager.clearPrimaryClip()
                "cleared=true"
            }
            else -> throw HookError("add --ez save true, --ez restore true or --ez clear true")
        }
    }

    private fun kindOf(clip: ClipData): String =
        if (clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
            "text"
        } else {
            "other"
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
    private fun spotter(extras: Bundle): String {
        if (extras.containsKey(EXTRA_BOOST) || extras.containsKey(EXTRA_THRESHOLD)) {
            val now = graph.voice.keywordTuning
            graph.voice.keywordTuning = try {
                val boost = extras.getFloat(EXTRA_BOOST, now.boost)
                KeywordTuning(boost, extras.getFloat(EXTRA_THRESHOLD, now.threshold))
            } catch (e: IllegalArgumentException) {
                throw HookError("add --ef boost from 0.5 to 10 and --ef threshold from 0.01 to 0.5")
            }
        }
        val tuning = graph.voice.keywordTuning
        return "boost=${tuning.boost} threshold=${tuning.threshold}"
    }

    private fun task(extras: Bundle): String {
        val seconds = extras.getInt(EXTRA_SECONDS, 0)
        if (seconds !in 1..MAX_TASK_SECONDS) throw HookError("add --ei seconds with 1 to $MAX_TASK_SECONDS")
        val reply = extras.getString(EXTRA_REPLY)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es reply <text>")
        // The goal's words are counted as a count, never kept, so that the session can tell a dictated goal that was split.
        VoiceHooks.install(graph)
        // --ez handover true: the round first hands the owner a payment step (plan 7F's Task 14, Step 8a).
        val handOver = extras.getBoolean(EXTRA_HANDOVER, false)
        graph.goalEnvironment.standIn = StandInRound(seconds, reply, handOver)
        return StandInRound.answer(seconds, reply.length, handOver)
    }

    private fun clearTask(): String {
        graph.goalEnvironment.standIn = null
        return "each goal's round runs on the phone again"
    }

    private fun startEcho(extras: Bundle): String {
        val text = extras.getString(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text <text>")
        val choice = when (extras.getString(EXTRA_VOICE) ?: "piper") {
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
        val COMMANDS = setOf(
            "VOICE_COUNTS", "VOICE_TIMES", "VOICE_ROUTE", "VOICE_SPOTTER", "VOICE_TASK", "VOICE_ECHO", "OWNER_OPTIONS", "NOTICES",
            "CHECK_NOTICE", "CLIPBOARD",
        )

        // The clip that CLIPBOARD saved, only in this process's memory and only on the main thread; null when none waits.
        private var saved: ClipData? = null

        const val CLEAR_REFUSED = "clipboard -Clear was refused: only the emulator may empty the clipboard"

        /** Whether CLIPBOARD --ez clear true is refused on a device whose [hardware] is not an emulator's. */
        fun clearRefused(hardware: String): Boolean = !VoiceHooks.emulator(hardware)

        /**
         * The kinds of Kestrel's notices about problems, from their IDs and the stop notice's text, sorted and joined with
         * commas, or none: battery (it stopped because its battery use is restricted), stopped (another reason),
         * accessibility and settings (plan 4D, H6).
         */
        fun noticeKinds(notices: List<Pair<Int, String?>>): String = notices.mapNotNull { (id, text) ->
            when (id) {
                ProblemNotifications.STOPPED_ID -> if (text == StopReason.BATTERY_RESTRICTED.text) "battery" else "stopped"
                ProblemNotifications.ACCESSIBILITY_ID -> "accessibility"
                ProblemNotifications.SETTINGS_ID -> "settings"
                else -> null
            }
        }.distinct().sorted().joinToString(",").ifEmpty { "none" }

        // The notification "Kestrel check" of plan 4D's checks.
        private const val CHECK_ID = 7_190
        private const val CHECK_CHANNEL = "kestrel-check"
        private const val CHECK_TITLE = "Kestrel check"
        private const val CHECK_TEXT = "A notification of Kestrel's own checks.\nIt has a second line, so that it can expand."
        private const val CHECK_WAIT_MILLIS = 2_000L
        private const val POLL_MILLIS = 100L

        private val lock = Any()

        private var echo: Echo? = null

        private const val MAX_TASK_SECONDS = 120
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_REPLY = "reply"
        private const val EXTRA_HANDOVER = "handover"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_BOOST = "boost"
        private const val EXTRA_THRESHOLD = "threshold"
        private const val EXTRA_REQUIRE_VERBS = "requireVerbs"
        private const val EXTRA_EARBUD_MICROPHONE = "earbudMicrophone"
        private const val EXTRA_ON = "on"
        private const val EXTRA_SAVE = "save"
        private const val EXTRA_RESTORE = "restore"
    }
}
