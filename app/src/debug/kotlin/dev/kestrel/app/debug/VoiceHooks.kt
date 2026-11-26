package dev.kestrel.app.debug

import android.content.Intent
import android.os.Build
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.agent.RoundEvidences
import dev.kestrel.app.commands.EmergencyGuard
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.settings.speedText
import dev.kestrel.conversation.model.OwnerInput
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
        "VOICE_STATE" -> intent.getStringExtra(EXTRA_TAG)?.let { tagState(it) } ?: state(intent)
        "VOICE_CLIP" -> when {
            intent.getBooleanExtra(EXTRA_CLEAR, false) -> clear()
            intent.getBooleanExtra(EXTRA_QUIET, false) -> quiet()
            else -> clip(intent)
        }
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
            "heard" -> heard(intent)
            else -> throw HookError("add --es event with one of ${EVENTS.joinToString()}")
        }
        val given = intent.getStringExtra(EXTRA_TAG)
        if (given != null) {
            if (event !is Event.Heard) throw HookError("only a transcript takes a tag; add --es event heard")
            val live = if (intent.hasExtra(EXTRA_LIVE)) intent.getBooleanExtra(EXTRA_LIVE, false) else null
            tag(graph, given, event, taskOptions(intent.getStringExtra(EXTRA_MODEL), live, intent.getStringExtra(EXTRA_MARKER)))
        } else if (event is Event.Heard) {
            untagged(graph, event)
        }
        graph.listening.send(event)
        return if (given != null) "sent $name tag=$given" else "sent $name"
    }

    // Plan 4D: "yes, call" reaches Kestrel only on an emulator, never on a phone (process rule 10).
    private fun heard(intent: Intent): Event.Heard {
        val text = text(intent)
        if (refused(text, Build.HARDWARE)) throw HookError(YES_CALL_REFUSED)
        return Event.Heard(text, intent.getBooleanExtra(EXTRA_NAMED, false))
    }

    // What the transcript with [tag] did (plan 4D, hook H1; 2026-11-20-a71-checks-4d-interfaces.md, section 2): never words.
    private fun tagState(tag: String): String {
        val tagged = tags.tagged(tag) ?: throw HookError("no transcript has the tag $tag")
        val state = graph.listening.state.value
        val number = tagged.command
        val (kind, outcome) = kindAndOutcome(graph, number)
        val runningNow = number > 0 && number == state.command && state.work == Work.RUNNING_COMMAND
        // The conversation's command runs until it has its outcome (design 14.4 of sub-project 7).
        val taskNow = tagged.task > 0 && graph.conversations.outcome(tagged.task) == null
        val phase = when {
            // A transcript that the conversation has not yet taken waits, as one that the Orchestrator has not read.
            tagged.role == CommandTags.Role.PENDING || tagged.role == CommandTags.Role.CONVERSE -> "pending"
            tagged.task > 0 -> if (taskNow) "running" else "ended"
            number == 0 -> "none"
            runningNow && state.counting -> "countdown"
            runningNow && state.asking -> "question"
            runningNow -> "running"
            else -> "ended"
        }
        val record = tags.record(number)
        return "tag=$tag role=${tagged.role.name.lowercase()} command=$number task=${tagged.task} kind=$kind outcome=$outcome " +
            "asking=${runningNow && state.asking} phase=$phase detail=${record.detail ?: "none"} said=${hashes(record.said)} " +
            "shown=${hashes(record.shown)} read=${tagged.read} latest=${state.command}"
    }

    private fun hashes(list: List<String>): String = list.joinToString(",").ifEmpty { "none" }

    // Plan 4D: the silent clip microphone, so that no sound of the room reaches Kestrel during the checks' text pass.
    private fun quiet(): String {
        microphone.clear()
        val recording = synchronized(lock) {
            val listening = graph.listening
            if (listening.microphones !== clipFactory) {
                replaced = listening.microphones
                listening.microphones = clipFactory
            }
            microphone.recording
        }
        return if (recording) {
            "quiet: Kestrel hears only the silent clip microphone, until voice-clip -Clear or the end of its process"
        } else {
            "quiet: Kestrel hears only the silent clip microphone from its next start, until voice-clip -Clear or the end of its process"
        }
    }

    // The latest instant command, or with --ei number the command with that number, as long as it is one of the newest 16:
    // its number, its kind and how it ended, never its words. With a number, latest= gives the latest command's number,
    // so that a check of the A71 sessions sees how many commands started after its own. followUp= says whether the
    // follow-up window is open (design 6.6 and 14.4 of sub-project 7).
    private fun state(intent: Intent): String {
        val state = graph.listening.state.value
        val session = if (graph.listening.sessionOpen.value) "open" else "closed"
        val clips = if (graph.listening.microphones === clipFactory) "on" else "off"
        val asked = intent.getIntExtra(EXTRA_NUMBER, 0)
        val number = if (asked > 0) asked else state.command
        val recent = graph.listening.recentCommand(number)
        val runningNow = number == state.command && state.work == Work.RUNNING_COMMAND
        val kind = state.running?.takeIf { runningNow }?.javaClass?.simpleName ?: recent?.kind ?: "none"
        val outcome = when {
            runningNow -> "running"
            recent != null -> recent.outcome?.name?.lowercase() ?: "running"
            // Started, but older than the commands that Kestrel keeps, or the hook asked before the command was noted.
            number in 1..state.command -> "unknown"
            else -> "none"
        }
        val latest = if (asked > 0) " latest=${state.command}" else ""
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "followUp=${if (state.work == Work.FOLLOWING_UP) "open" else "closed"} screenOn=${state.screenOn} micBusy=${state.micBusy} command=$number kind=$kind outcome=$outcome$latest " +
            "session=$session clips=$clips"
    }

    private suspend fun clip(intent: Intent): String {
        val text = text(intent)
        if (text.isBlank()) throw HookError("add a text that is not blank")
        // Plan 4D: Kestrel hears this text, so a phone never gets "yes, call" (process rule 10).
        if (refused(text, Build.HARDWARE)) throw HookError(YES_CALL_REFUSED)
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

        /** The tags of plan 4D's checks, one record for the whole process. */
        val tags: CommandTags by lazy { CommandTags() }


        const val YES_CALL_REFUSED = "only the emulator may answer \"yes, call\""

        private val TAG = Regex("[A-Za-z0-9_-]{1,32}")

        /** Whether [hardware], Android's `Build.HARDWARE`, is an emulator's. */
        fun emulator(hardware: String): Boolean = hardware == "ranchu" || hardware == "goldfish"

        /** Whether [text] is "yes, call" on a device whose [hardware] is not an emulator's, which the hooks never send. */
        fun refused(text: String, hardware: String): Boolean = !emulator(hardware) && EmergencyGuard.confirmed(text)

        /**
         * Links [tag] and [options] to [event] before Kestrel handles it, and starts the record of the steps ([install]):
         * the input that a tagged transcript gives the conversation carries its options, and the round that it starts reads
         * them.
         */
        fun tag(graph: AppGraph, tag: String, event: Event.Heard, options: Map<String, String> = emptyMap()) {
            if (!TAG.matches(tag)) throw HookError("add --es tag with 1 to 32 letters, digits, '-' and '_'")
            install(graph)
            if (!tags.register(tag, event, readOf(event.text, event.named), options)) {
                throw HookError("the tag $tag is in use; give each transcript a new tag")
            }
        }

        /**
         * Links [tag] and [options] to [input], which the hook `conversation-type` gives the conversation as typed words
         * (design 14.4 of sub-project 7), and starts the record of the steps ([install]); returns the input with the options.
         */
        fun typed(graph: AppGraph, tag: String, input: OwnerInput, options: Map<String, String> = emptyMap()): OwnerInput {
            if (!TAG.matches(tag)) throw HookError("add --es tag with 1 to 32 letters, digits, '-' and '_'")
            install(graph)
            return tags.typed(tag, input, kindOf(input), options) ?: throw HookError("the tag $tag is in use; give each transcript a new tag")
        }

        /**
         * Notes [input], which a hook gives the conversation without a tag, as the hooks' own, so that it is not taken for
         * the owner's, and starts the record of the steps ([install]; Task 13a and its review M2); returns it.
         */
        fun untagged(graph: AppGraph, input: OwnerInput): OwnerInput {
            install(graph)
            tags.untagged(input)
            return input
        }

        /**
         * Notes [event], a transcript that a hook sends without a tag, so that its input is the hooks' own and not the
         * owner's, and starts the record of the steps ([install]; review M2 of Task 13a); returns it.
         */
        fun untagged(graph: AppGraph, event: Event.Heard): Event.Heard {
            install(graph)
            tags.untagged(event)
            return event
        }

        /** A typed input's kind in lower case, such as command, answer or stop: never its words. */
        fun kindOf(input: OwnerInput): String = input.intent.javaClass.simpleName.lowercase()

        /**
         * Starts the record of the Orchestrator's steps and the tags of the conversation's inputs (plan 4D, H1; design 14.4
         * of sub-project 7), once per process: a tagged transcript's input gets its options, and each round's witness keeps
         * what it said and watches its data tools for the run's marker (plan 6B-basefix). The conversation's steps reach
         * [tags] from the process's start ([HookConversations.watch]), so that the marks' rule never waits for a hook call.
         */
        @Synchronized
        fun install(graph: AppGraph) {
            val listening = graph.listening
            if (listening.steps != null) return
            listening.inputs = tags::input
            graph.goalEnvironment.evidences = RoundEvidences()
            graph.goalEnvironment.witnesses = { options -> DebugTaskWitness.open(options) }
            listening.steps = tags::onStep
        }

        /**
         * The options that a tagged transcript passes to the round that it starts, from the hook's extras: --es model and
         * --ez live, which RoundModels reads (sub-project 5's design, "Shared files and anchors", item 2), and --es marker,
         * a run's marker, which the round's [DebugTaskWitness] watches for (plan 6B-basefix).
         */
        fun taskOptions(model: String?, live: Boolean?, marker: String? = null): Map<String, String> = buildMap {
            model?.let { put("model", it) }
            live?.let { put("live", it.toString()) }
            marker?.takeIf { it.isNotBlank() }?.let { put(DebugTaskWitness.MARKER, it) }
            // sub-project 5B
        }

        /** How Kestrel's Requests read [text]: the request's kind in lower case, such as other or stop, or none. */
        fun readOf(text: String, named: Boolean): String = Requests.read(text, named)?.javaClass?.simpleName?.lowercase() ?: "none"

        /** The kind and the outcome of command [number], as voice-state answers them; none and none for 0. */
        fun kindAndOutcome(graph: AppGraph, number: Int): Pair<String, String> {
            if (number <= 0) return "none" to "none"
            val state = graph.listening.state.value
            val recent = graph.listening.recentCommand(number)
            val runningNow = number == state.command && state.work == Work.RUNNING_COMMAND
            val kind = state.running?.takeIf { runningNow }?.javaClass?.simpleName ?: recent?.kind ?: "none"
            val outcome = when {
                runningNow -> "running"
                recent != null -> recent.outcome?.name?.lowercase() ?: "running"
                number in 1..state.command -> "unknown"
                else -> "none"
            }
            return kind to outcome
        }

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
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_QUIET = "quiet"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_MARKER = "marker"
    }
}
