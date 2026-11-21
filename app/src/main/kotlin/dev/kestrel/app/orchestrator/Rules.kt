package dev.kestrel.app.orchestrator

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition

/**
 * The listening modes (design spec, section 8.5). [RINGING]: a call rings, and Kestrel listens only for the call
 * commands, also on the lock screen. [IN_CALL]: a call is active, and Kestrel is paused.
 */
enum class Mode { OFF, LISTENING, PAUSED, RINGING, IN_CALL }

/** The phone's call state (design spec, section 8.7), as Android's call-state listener reports it. */
enum class CallState { IDLE, RINGING, ACTIVE }

/**
 * What Kestrel does when the screen comes back on and the phone is unlocked (design spec, section 8.5): it keeps the
 * mode that it had ([RESUME]), listens ([LISTEN]), or turns off ([STAY_OFF]).
 */
enum class WakeUpMode { RESUME, LISTEN, STAY_OFF }

/** What Kestrel is doing besides listening: the spec's "current activity" (section 7.3). */
enum class Work {
    IDLE,

    /** After "Kestrel" on its own: the next utterance is the task. */
    AWAITING_TASK,

    /** A task runs, and only "stop" and "cancel" count (section 11.10). */
    RUNNING_TASK,

    /** An instant command runs (section 7.3): it may ask a question or count down, and "stop" or "cancel" cancel it. */
    RUNNING_COMMAND,
}

/**
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished. [task] is the number of the latest task and [wait] the number of the latest wait for a task, so that the
 * end of an older task or wait changes nothing. [cancelled]: the latest task was cancelled and has not reported yet.
 * [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6). [beforeCall] is the mode that a call interrupted, while the mode is
 * Ringing or In call; it comes back after the call (section 8.7). [callCommands]: the owner turned on "Commands during a
 * call", so that Kestrel listens for them during a call (section 8.7). [onWakeUp] is what Kestrel does after an unlock,
 * and [pauseAfterSilence] pauses listening after 30 s without speech (section 8.5); [silence] is the number of the
 * latest timer of that pause, so that the end of an older one changes nothing. [screenSeen]: since Kestrel turned on,
 * the running ListeningService has reported the screen on and the phone unlocked, so that the next unlock is a wake-up
 * (section 8.5). [command] is the number of the latest instant command, so that the end of an older one changes nothing;
 * [running] is the command that runs and [heard] its transcript; [asking]: it waits for the answer to its question;
 * [counting]: it counts down, as before a call. [lastCommand] and [lastHeard] are the last command that ended as
 * repeatable and its transcript, for "again" (section 9.5); off forgets them. [spottedStop]: the spotter's "stop" or
 * "cancel" cancelled the command, or stopped a reply while no command ran, so the transcript of the same word names no
 * button. It lasts until the next transcript that reaches the commands, so when the pipeline dropped that word, one
 * later "stop…" or "cancel…" is only shown. [questionEnded]: a command's question ended without an answer, so the next
 * transcript that reaches the commands, when it names a number, may be a late answer and is only shown (section 9.4). It
 * lasts 10 s at most, and a pause, a call, a task or the wait for one, and the screen turning off end it.
 */
data class State(
    val mode: Mode = Mode.OFF,
    val work: Work = Work.IDLE,
    val speeches: Int = 0,
    val task: Int = 0,
    val wait: Int = 0,
    val cancelled: Boolean = false,
    val screenOn: Boolean = true,
    val micBusy: Boolean = false,
    val emptyTranscripts: Int = 0,
    val noWordsShown: Boolean = false,
    val beforeCall: Mode? = null,
    val callCommands: Boolean = false,
    val onWakeUp: WakeUpMode = WakeUpMode.RESUME,
    val pauseAfterSilence: Boolean = false,
    val silence: Int = 0,
    val screenSeen: Boolean = false,
    val command: Int = 0,
    val running: Command? = null,
    val heard: String? = null,
    val asking: Boolean = false,
    val counting: Boolean = false,
    val lastCommand: Command? = null,
    val lastHeard: String? = null,
    val spottedStop: Boolean = false,
    val questionEnded: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0

    // The owner's words never reach a log (section 13.5): only the commands' kinds and the transcripts' lengths.
    override fun toString() =
        "State(mode=$mode, work=$work, speeches=$speeches, task=$task, wait=$wait, cancelled=$cancelled, screenOn=$screenOn, " +
            "micBusy=$micBusy, emptyTranscripts=$emptyTranscripts, noWordsShown=$noWordsShown, beforeCall=$beforeCall, " +
            "callCommands=$callCommands, onWakeUp=$onWakeUp, pauseAfterSilence=$pauseAfterSilence, silence=$silence, " +
            "screenSeen=$screenSeen, command=$command, running=${running?.javaClass?.simpleName}, heard=${heard?.length}, " +
            "asking=$asking, counting=$counting, lastCommand=${lastCommand?.javaClass?.simpleName}, " +
            "lastHeard=${lastHeard?.length}, spottedStop=$spottedStop, questionEnded=$questionEnded)"

    /** Whether a call rings or is active (section 8.7). */
    val inCall: Boolean get() = mode == Mode.RINGING || mode == Mode.IN_CALL

    /**
     * Whether Kestrel records: while it listens or is paused, with the screen on and the phone unlocked; while a call
     * rings, also on the lock screen (section 8.5); and during a call only with [callCommands] and the phone unlocked.
     */
    val microphone: Boolean
        get() = when (mode) {
            Mode.OFF -> false
            Mode.RINGING -> true
            Mode.IN_CALL -> callCommands && screenOn
            Mode.LISTENING, Mode.PAUSED -> screenOn
        }

    /** Which utterances the pipeline transcribes, and whether it holds them (sections 8.3, 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            // While a call rings, the call commands may come without the name (section 8.7).
            mode == Mode.RINGING -> Recognition.ALL
            // During a call with the setting, only the utterances at whose start the keyword spotter heard "Kestrel", as
            // while Kestrel is paused, so that Kestrel never transcribes the call's conversation; when the spotter misses
            // the name, the owner says the command again (section 8.7).
            mode == Mode.IN_CALL -> if (callCommands) Recognition.NAMED else Recognition.NONE
            // While Kestrel waits for a task, after "Yes?" or after an interruption with "Kestrel", the owner dictates the
            // task and may pause in it.
            work == Work.AWAITING_TASK -> Recognition.ANSWER
            work != Work.IDLE || mode == Mode.LISTENING -> Recognition.ALL
            else -> Recognition.NAMED
        }
}

/** What happens to Kestrel (design spec, section 7.3). */
sealed interface Event {
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event

    /**
     * Turns Kestrel on while it is off: listening, or paused when [paused]. While Kestrel listens or is paused it changes
     * nothing, so that a start that comes late cannot toggle anything. The restore after a restart of Kestrel's process
     * sends it (section 8.5).
     */
    data class Start(val paused: Boolean = false) : Event

    /** The notification's Pause action. */
    data object Pause : Event

    /** The notification's Resume action. */
    data object Resume : Event

    /** The notification's Stop action. */
    data object TurnOff : Event

    /**
     * A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. [spokeAt] is when the owner
     * began to say it, in `SystemClock.uptimeMillis()`, or null when it is not known; a command that names a number
     * ends when the numbers changed after it (section 9.4). [seconds] is the length of its audio, and [endedAt] the end
     * of its speech on the phone's clock (SystemClock.elapsedRealtime), or 0 when it is not known; only the A71 session's
     * counts and times read them (plan 2E). [closedAt] is when its end-of-speech pause ended, on the same clock, or 0
     * (plan 4D). [stopSpotted] is true when the keyword spotter heard "stop" at its start (plan 4E).
     */
    data class Heard(
        val text: String,
        val named: Boolean,
        val spokeAt: Long? = null,
        val seconds: Float = 0f,
        val endedAt: Long = 0L,
        val closedAt: Long = 0L,
        val stopSpotted: Boolean = false,
    ) : Event {
        override fun toString() = "Heard(${text.length} characters, named=$named)"
    }

    /**
     * The owner speaks while Kestrel recognises speech. Send it when the speech detector hears speech on a frame for
     * which recognition is on, and on the frame before it either heard none or recognition was off. Send it again when a
     * transcript arrives while the detector still hears speech. Read `hearsSpeech` on the microphone's thread, after
     * `ListeningPipeline.accept`; the pipeline's `VoiceEvent.SpeechStarted` follows this rule. An utterance that ends
     * without a transcript while recognition is on restarts the 8 s wait for a task: `VoiceSession` sends
     * [NothingHeard] for it.
     */
    data object Speaking : Event

    /** A keyword from the spotter that was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : Event

    /**
     * A reply has been spoken, stopped, interrupted or refused, or speaking it failed. Send it once for each reply, or
     * recognition stays off.
     */
    data object SpeechEnded : Event

    /** Task number [task] ended, with a [reply] for the owner or none. */
    data class TaskEnded(val task: Int, val reply: String?) : Event {
        override fun toString() = "TaskEnded($task, reply=${reply?.let { "${it.length} characters" }})"
    }

    /** Android started or stopped silencing Kestrel's recording. */
    data class MicBusy(val busy: Boolean) : Event

    /**
     * The screen is on and the phone unlocked ([on]), or the screen turned off or the phone locked. Sub-project 5 pauses
     * a running task here, and asks "Continue the task?" when the owner is back (section 14.4).
     */
    data class Screen(val on: Boolean) : Event

    /** Wait number [wait] for a task after "Yes?" has lasted long enough. */
    data class WaitEnded(val wait: Int) : Event

    /**
     * Kestrel could not open its settings app, because Android lets it start an activity from the background only while
     * its accessibility service is on or it holds the assistant role. Kestrel says so, because the feedback bar does not
     * exist then (section 14).
     */
    data object SettingsUnavailable : Event

    /**
     * Android did not bring Kestrel's settings to the front, although Kestrel's accessibility service is on or Kestrel
     * holds the assistant role, as Android 13 may do with only the role. Kestrel posted a notification that opens them,
     * and says so (section 14).
     */
    data object SettingsBlocked : Event

    /**
     * An utterance ended without a transcript: the pipeline dropped it, or transcribing it failed. Like a transcript
     * without words, it gives the owner the whole wait for a task again, but it does not count towards the hint about
     * empty transcripts (section 14.6).
     */
    data object NothingHeard : Event

    /** The phone's call state changed (section 8.7). Only Listening's call-state listener sends it. */
    data class CallState(val state: dev.kestrel.app.orchestrator.CallState) : Event

    /**
     * The assistant gesture or an earbud's assistant button (section 8.6): Kestrel listens, if it was paused, and waits
     * for a task, as after "Kestrel" on its own. Listening turns Kestrel on first and sends it once a voice session is
     * open, so the Rules ignore it while Kestrel is off.
     */
    data object Assist : Event

    /** The owner's choices for listening, as Listening read them at its start or after a change (section 15). */
    data class Options(val onWakeUp: WakeUpMode, val pauseAfterSilence: Boolean, val callCommands: Boolean) : Event

    /** Timer number [silence] of the pause after 30 s without speech has run out (section 8.5). */
    data class Silent(val silence: Int) : Event

    /** Instant command number [command] ended with [result] (section 7.3). Listening sends it for every command that ran. */
    data class CommandEnded(val command: Int, val result: CommandResult) : Event

    /** Command number [command] asks [question] and waits for the answer. */
    data class CommandAsks(val command: Int, val question: String) : Event {
        override fun toString() = "CommandAsks($command, ${question.length} characters)"
    }

    /** Command number [command] says [text], such as a call's announcement. */
    data class CommandSays(val command: Int, val text: String) : Event {
        override fun toString() = "CommandSays($command, ${text.length} characters)"
    }

    /** Command number [command] starts ([counting]) or ends a countdown, as before a call (section 13.6). */
    data class CommandCounts(val command: Int, val counting: Boolean) : Event

    /** Wait number [wait] for the answer to a command's question has lasted long enough. */
    data class AnswerWaitEnded(val wait: Int) : Event

    /** 10 s have passed since the question of command number [command] ended without an answer (section 9.4). */
    data class LateAnswersEnded(val command: Int) : Event
}

/** What Kestrel says. */
sealed interface Reply {
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

    /**
     * Why Kestrel did not open its settings: its accessibility service is off, and it does not hold the assistant role
     * (section 14).
     */
    data object SettingsNeedService : Reply

    /** Android kept Kestrel's settings back, and a notification opens them (section 14). */
    data object SettingsBlocked : Reply

    data class Text(val text: String) : Reply {
        override fun toString() = "Text(${text.length} characters)"
    }

    /** What instant command number [command] says or asks. */
    data class Command(val command: Int, val text: String) : Reply {
        override fun toString() = "Command($command, ${text.length} characters)"
    }
}

/** What the feedback bar shows (design spec, section 8.9). */
sealed interface Feedback {
    data class ModeChanged(val mode: Mode) : Feedback

    data class Heard(val text: String) : Feedback {
        override fun toString() = "Heard(${text.length} characters)"
    }

    data object MicBusy : Feedback

    /**
     * Kestrel waits for the task, after "Yes?" or after an interruption with "Kestrel": the question that Kestrel asks
     * (section 8.9).
     */
    data object AwaitingTask : Feedback

    /** Three transcripts in a row had no words, so the owner may check the microphone (section 14.6). */
    data object NoWords : Feedback

    /** What an instant command shows. With [keep], the text stays until the command ends or the next text. */
    data class CommandText(val text: String, val keep: Boolean = false) : Feedback {
        override fun toString() = "CommandText(${text.length} characters, keep=$keep)"
    }

    /** "Cancelled": a command was cancelled, or its question was not answered. */
    data object CommandCancelled : Feedback

    /** A command came while another ran, and was dropped (decision 3 of sub-project 3). */
    data object CommandBusy : Feedback

    /** "Again" without a command to repeat. */
    data object NothingToRepeat : Feedback

    /**
     * A number came as the first transcript after a command's question ended without an answer, within 10 s: it may be a
     * late answer, whose number would name another element now, so it did nothing (section 9.4).
     */
    data object QuestionEnded : Feedback
}

/** What the Orchestrator asks of the rest of Kestrel. The microphone and recognition follow [State] instead. */
sealed interface Effect {
    data class Say(val reply: Reply) : Effect

    data object StopSaying : Effect

    data class RunTask(val task: Int, val text: String) : Effect {
        override fun toString() = "RunTask($task, ${text.length} characters)"
    }

    data object CancelTask : Effect

    /**
     * Runs instant command number [command], [parsed] from the transcript [heard], which the executor needs for its
     * fallback to a bare label and for dictation. [spokeAt] is when the owner began to say it, as in [Event.Heard]. It
     * must send [Event.CommandEnded] once it ends (section 7.3).
     */
    data class RunCommand(val command: Int, val parsed: Command, val heard: String, val spokeAt: Long? = null) : Effect {
        override fun toString() = "RunCommand($command, ${parsed.javaClass.simpleName}, ${heard.length} characters)"
    }

    /** Cancels the running instant command. Nothing new may act on the screen until its job has completed. */
    data object CancelCommand : Effect

    /** The answer to the question of command number [command], or null when the wait for it ended. */
    data class AnswerCommand(val command: Int, val text: String?) : Effect {
        override fun toString() = "AnswerCommand($command, ${if (text == null) "no answer" else "${text.length} characters"})"
    }

    /** Holds the countdown of command number [command] while the owner speaks ([hold]), or lets it go on. */
    data class HoldCommand(val command: Int, val hold: Boolean) : Effect

    /**
     * Starts the timer that ends the latest wait for the answer to a command's question, in place of the one that runs.
     * [answering]: the owner has started to answer. The Orchestrator runs it itself.
     */
    data class WaitForAnswer(val answering: Boolean = false) : Effect

    data class Show(val feedback: Feedback) : Effect

    /**
     * Starts the timer that ends the latest wait for a task after "Yes?", in place of the one that runs. [answering]: the
     * owner has started to answer, so the timer also covers the answer and its transcription. The Orchestrator runs it
     * itself.
     */
    data class WaitForTask(val answering: Boolean = false) : Effect

    /** Stops the listening service. */
    data object StopService : Effect

    /** Opens Kestrel's settings app, for "Kestrel, open settings" (section 15). */
    data object OpenSettings : Effect

    /** Carries out a call command (section 8.7). */
    data class CallCommand(val command: Request.Call) : Effect

    /**
     * Starts the timer of the pause after 30 s without speech, in place of the one that runs (section 8.5). The
     * Orchestrator runs it itself.
     */
    data object TimeSilence : Effect

    /**
     * Starts the timer after which a number no longer counts as a late answer to the question of the latest command
     * ([Event.LateAnswersEnded]), in place of the one that runs. The Orchestrator runs it itself.
     */
    data object TimeLateAnswers : Effect
}

/** A transition: the new state, and what to do, in order. */
data class Step(val state: State, val effects: List<Effect> = emptyList())

/** Every transition of the Orchestrator, in one place (design spec, section 7.3). */
object Rules {
    fun next(state: State, event: Event): Step = silenceTimer(state, event, endLateAnswers(react(state, event)))

    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come before "stop" or "cancel", as in "Okay, stop scrolling" or "No, cancel the drag".
    private val BEFORE_STOP = setOf("ok", "okay", "please", "now", "no", "hey", "oh", "just", "wait", "so")

    // Words that may come before or with "no" during a countdown, as in "Oh no", "Wait, no" or "Thank you, no".
    private val NO_FILLERS =
        setOf("oh", "wait", "please", "now", "ok", "okay", "hey", "just", "not", "thanks", "thank", "you", "dont")

    private fun react(state: State, event: Event): Step =
        if (state.work == Work.RUNNING_COMMAND) duringCommand(state, event) else withoutCommand(state, event)

    private fun withoutCommand(state: State, event: Event): Step = when (event) {
        Event.Toggle -> when {
            state.inCall -> afterCall(state, if (state.beforeCall == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
            else -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        }
        is Event.Start ->
            if (state.mode == Mode.OFF) switch(state, if (event.paused) Mode.PAUSED else Mode.LISTENING) else Step(state)
        Event.Pause -> when {
            state.inCall -> afterCall(state, Mode.PAUSED)
            state.mode == Mode.LISTENING -> switch(state, Mode.PAUSED)
            else -> Step(state)
        }
        // The notification exists only while Kestrel is on, so a Resume while off comes from a stale notification.
        Event.Resume -> when {
            state.inCall -> afterCall(state, Mode.LISTENING)
            state.mode == Mode.PAUSED -> switch(state, Mode.LISTENING)
            else -> Step(state)
        }
        Event.TurnOff -> if (state.mode == Mode.OFF) Step(state) else turnOff(state)
        is Event.Heard -> heard(state, event)
        // The owner has started to answer "Yes?", so the wait lasts until the answer has been transcribed.
        Event.Speaking -> if (state.work == Work.AWAITING_TASK && !state.speaking) waitForTask(state, answering = true) else Step(state)
        is Event.Spotted -> spotted(state, event.keyword)
        Event.SpeechEnded -> speechEnded(state)
        is Event.TaskEnded -> taskEnded(state, event)
        is Event.MicBusy -> when {
            state.mode == Mode.OFF -> Step(state)
            event.busy && !state.micBusy -> Step(state.copy(micBusy = true), listOf(Effect.Show(Feedback.MicBusy)))
            else -> Step(state.copy(micBusy = event.busy))
        }
        is Event.Screen -> screen(state, event.on)
        is Event.WaitEnded ->
            if (state.work == Work.AWAITING_TASK && event.wait == state.wait) Step(state.copy(work = Work.IDLE)) else Step(state)
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
        // Not over a ringing call: the verdict comes up to 5 s after the request (SettingsOpener.WAIT_MILLIS).
        Event.SettingsBlocked -> if (state.mode == Mode.OFF || state.inCall) Step(state) else say(state, Reply.SettingsBlocked)
        Event.NothingHeard ->
            if (state.mode != Mode.OFF && state.work == Work.AWAITING_TASK) waitForTask(state) else Step(state)
        is Event.CallState -> call(state, event.state)
        Event.Assist -> assist(state)
        is Event.Options -> Step(
            state.copy(onWakeUp = event.onWakeUp, pauseAfterSilence = event.pauseAfterSilence, callCommands = event.callCommands),
        )
        is Event.Silent -> silent(state, event.silence)
        is Event.LateAnswersEnded ->
            if (event.command == state.command && state.questionEnded) Step(state.copy(questionEnded = false)) else Step(state)
        // A command that has ended or was cancelled may still report; nothing changes then.
        is Event.CommandEnded, is Event.CommandAsks, is Event.CommandSays, is Event.CommandCounts, is Event.AnswerWaitEnded ->
            Step(state)
    }

    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        if (state.inCall) return callCommand(state, event)
        val awaiting = state.work == Work.AWAITING_TASK
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a task ends when the screen turns off.
        val request = Requests.read(event.text, event.named, afterName = awaiting, stopSpotted = event.stopSpotted)
            ?: return if (state.screenOn) withoutWords(state, awaiting) else Step(state)
        // Words start the count of empty transcripts again.
        val counted = state.copy(emptyTranscripts = 0)
        // A transcription that was queued before the microphone stopped can finish after the screen turned off. Then
        // only the requests that stop something count, because nothing may start (section 14.4).
        if (!counted.screenOn && !stopsSomething(request)) return Step(counted)
        val shown = Effect.Show(Feedback.Heard(event.text))
        return when (counted.work) {
            Work.RUNNING_TASK -> whileTaskRuns(counted, request, shown)
            Work.AWAITING_TASK -> whileAwaitingTask(counted, request, shown)
            Work.IDLE -> whileIdle(counted, request, event.text, shown, event.spokeAt)
            // duringCommand() reads the transcripts of a running command.
            Work.RUNNING_COMMAND -> Step(counted)
        }
    }

    // Section 14.6: a transcript without words. After three in a row, the bar suggests checking the microphone, once
    // until Kestrel turns off. An answer to "Yes?" without words gives the owner the full wait again.
    private fun withoutWords(state: State, awaiting: Boolean): Step {
        val count = state.emptyTranscripts + 1
        val hint = count >= EMPTY_TRANSCRIPTS_FOR_HINT && !state.noWordsShown
        val counted = state.copy(emptyTranscripts = count, noWordsShown = state.noWordsShown || hint)
        val shown = listOfNotNull(Effect.Show(Feedback.NoWords).takeIf { hint })
        return if (awaiting) waitForTask(counted, *shown.toTypedArray()) else Step(counted, shown)
    }

    private fun stopsSomething(request: Request): Boolean =
        request is Request.Stop || request == Request.PauseListening || request == Request.TurnOff

    // Section 11.10: while a task runs, only "stop" and "cancel" count, and the ways to pause or turn Kestrel off.
    // "Stop. Kestrel, [task]" cancels the running task and runs the new one, and "Stop. Kestrel, open settings" cancels it
    // and opens the settings.
    private fun whileTaskRuns(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Stop -> cancelTask(state, shown)
        is Request.Task -> when {
            !request.stopFirst -> Step(state)
            // Plan 4E: after "Stop.", a near miss of "wake up" while Kestrel is paused cancels the task and starts none.
            request.nearWakeUp && state.mode == Mode.PAUSED -> cancelTask(state, shown)
            else -> cancelTask(state, shown).then { runTask(it, request.text) }
        }
        Request.PauseListening -> cancelTask(state, shown).then { changeMode(it, Mode.PAUSED) }
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings ->
            if (request.stopFirst) cancelTask(state, shown).then { Step(it, listOf(Effect.OpenSettings)) } else Step(state)
        else -> Step(state)
    }

    // The answer to "Yes?" is read as the words after the name, so it is never Other. While Kestrel is paused, a near miss
    // of "wake up" ends the wait and starts nothing (plan 4E).
    private fun whileAwaitingTask(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Task ->
            if (request.nearWakeUp && state.mode == Mode.PAUSED) Step(state.copy(work = Work.IDLE), listOf(shown)) else runTask(state, request.text, shown)
        is Request.Other -> runTask(state, request.text, shown)
        Request.Name -> askForTask(state, shown)
        Request.WakeUp -> changeMode(state.copy(work = Work.IDLE), Mode.LISTENING, shown)
        is Request.Stop -> Step(state.copy(work = Work.IDLE), listOf(shown))
        Request.PauseListening -> changeMode(state.copy(work = Work.IDLE), Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings -> Step(state.copy(work = Work.IDLE), listOf(shown, Effect.OpenSettings))
        // Requests.read never returns a call command; only readCall does.
        is Request.Call -> Step(state)
    }

    // A near miss of "Kestrel, wake up" while Kestrel is paused starts nothing: the owner meant to wake Kestrel up (plan 4E).
    private fun whileIdle(state: State, request: Request, text: String, shown: Effect, spokeAt: Long? = null): Step = when (request) {
        is Request.Task -> if (request.nearWakeUp && state.mode == Mode.PAUSED) Step(state, listOf(shown)) else runTask(state, request.text, shown)
        Request.Name -> askForTask(state, shown)
        Request.WakeUp -> changeMode(state, Mode.LISTENING, shown)
        Request.PauseListening -> changeMode(state, Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings -> Step(state, listOf(shown, Effect.OpenSettings))
        is Request.Stop -> if (request.named) {
            // "Kestrel, stop" with nothing running only ends the speech; plain "stop" may name a button.
            Step(state, listOfNotNull(shown, Effect.StopSaying.takeIf { state.speaking }))
        } else {
            command(state, text.trim(), shown, spokeAt)
        }
        is Request.Other -> command(state, request.text, shown, spokeAt)
        // Requests.read never returns a call command; only readCall does.
        is Request.Call -> Step(state)
    }

    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5), and only with the screen on and the phone
    // unlocked. "Again" runs the last repeatable command again, with its transcript.
    private fun command(state: State, text: String, shown: Effect, spokeAt: Long?): Step {
        val spotted = state.spottedStop
        val late = state.questionEnded
        val fresh = if (spotted || late) state.copy(spottedStop = false, questionEnded = false) else state
        if (fresh.mode != Mode.LISTENING || !fresh.screenOn) return Step(fresh)
        // Design D2: the transcript of the "stop" or "cancel" whose keyword already cancelled the command names no button.
        if (spotted && startsWithStop(text)) return Step(fresh, listOf(shown))
        // An answer that the owner began just before the question's wait ran out arrives after the command has ended. Its
        // number would name an element of "show numbers" or a square of the grid by now (section 9.4).
        if (late && namesNumber(text)) return Step(fresh, listOf(shown, Effect.Show(Feedback.QuestionEnded)))
        return startCommand(fresh, text, shown, spokeAt)
    }

    private fun namesNumber(text: String): Boolean = SpokenNumbers.answer(text) != null || SpokenNumbers.namesNumber(CommandParser.parse(text))

    private fun startCommand(state: State, text: String, shown: Effect, spokeAt: Long?): Step {
        val parsed = CommandParser.parse(text) ?: return Step(state, listOf(shown))
        if (parsed != Command.Again) return runCommand(state, parsed, text, spokeAt, shown)
        val last = state.lastCommand
        val lastHeard = state.lastHeard
        if (last == null || lastHeard == null) return Step(state, listOf(shown, Effect.Show(Feedback.NothingToRepeat)))
        return runCommand(state, last, lastHeard, spokeAt, shown)
    }

    // Every command gets the next number, so that the end of an older one changes nothing.
    private fun runCommand(state: State, command: Command, heard: String, spokeAt: Long?, vararg first: Effect): Step {
        val number = state.command + 1
        return Step(
            state.copy(work = Work.RUNNING_COMMAND, command = number, running = command, heard = heard),
            first.toList() + Effect.RunCommand(number, command, heard, spokeAt),
        )
    }

    // Section 7.3: while an instant command runs. A step that leaves RUNNING_COMMAND other than through the command's own
    // end goes through leaveCommand(), which cancels the command's job first.
    private fun duringCommand(state: State, event: Event): Step = when (event) {
        is Event.CommandEnded -> if (event.command == state.command) commandEnded(state, event.result) else Step(state)
        is Event.CommandAsks -> if (event.command == state.command) ask(state, event.question) else Step(state)
        is Event.CommandSays -> if (event.command == state.command) {
            say(state, Reply.Command(event.command, event.text), Effect.Show(Feedback.CommandText(event.text)))
        } else {
            Step(state)
        }
        is Event.CommandCounts -> if (event.command == state.command) Step(state.copy(counting = event.counting)) else Step(state)
        is Event.AnswerWaitEnded -> if (state.asking && event.wait == state.wait) {
            Step(
                state.copy(asking = false, questionEnded = true),
                listOf(Effect.AnswerCommand(state.command, null), Effect.TimeLateAnswers),
            )
        } else {
            Step(state)
        }
        is Event.Heard -> heardDuringCommand(state, event)
        // The owner starts to speak: a countdown holds until the transcript decides, and an answer gets the longer wait.
        Event.Speaking -> when {
            state.counting -> Step(state, listOf(Effect.HoldCommand(state.command, hold = true)))
            state.asking && !state.speaking -> waitForAnswer(state, answering = true)
            else -> Step(state)
        }
        // An utterance without a transcript lets a held countdown go on, and gives an answer the whole wait again.
        Event.NothingHeard -> when {
            state.counting -> Step(state, listOf(Effect.HoldCommand(state.command, hold = false)))
            state.asking -> waitForAnswer(state)
            else -> Step(state)
        }
        is Event.Spotted -> spottedDuringCommand(state, event.keyword)
        // The wait for the answer starts again when the question has been spoken.
        Event.SpeechEnded ->
            speechEnded(state).let { ended -> if (state.speeches == 1 && state.asking) ended.then { waitForAnswer(it) } else ended }
        is Event.TaskEnded -> lateTaskEnded(state, event)
        // A countdown ignores the assistant gesture, as it ignores Kestrel's other requests.
        Event.Assist -> if (state.counting) Step(state) else keepOrLeave(state, event)
        // The screen that "lock screen" turned off is what the command did; its end still comes.
        is Event.Screen -> if (!event.on && !locksScreen(state.running)) leaveFor(state, event) else keepOrLeave(state, event)
        // The call that "call X" placed becomes active: the command goes on in the mode In call, and its end still counts.
        is Event.CallState -> if (event.state == CallState.ACTIVE && state.running is PhoneCommand.Call) {
            withoutCommand(state, event).let { Step(it.state.copy(work = Work.RUNNING_COMMAND), it.effects) }
        } else {
            keepOrLeave(state, event)
        }
        else -> keepOrLeave(state, event)
    }

    // An event that leaves the work and the mode alone keeps the command; any other cancels it first and then acts as it
    // would without a command.
    private fun keepOrLeave(state: State, event: Event): Step {
        val step = withoutCommand(state, event)
        return if (step.state.work == Work.RUNNING_COMMAND && step.state.mode == state.mode) step else leaveFor(state, event)
    }

    private fun leaveFor(state: State, event: Event): Step = leaveCommand(state).then { withoutCommand(it, event) }.stopSayingOnce()

    // leaveCommand() does not lower the count of replies, so the step after it may stop the same reply again.
    private fun Step.stopSayingOnce(): Step {
        val first = effects.indexOf(Effect.StopSaying)
        return if (first < 0) this else copy(effects = effects.filterIndexed { i, effect -> i == first || effect != Effect.StopSaying })
    }

    // Cancels the running command's job, and stops a question or an announcement in progress.
    private fun leaveCommand(state: State, vararg first: Effect): Step = Step(
        state.copy(work = Work.IDLE, running = null, heard = null, asking = false, counting = false),
        first.toList() + Effect.CancelCommand + listOfNotNull(Effect.StopSaying.takeIf { state.speaking }),
    )

    // "Stop" or "cancel": the command is cancelled, and the bar says so.
    private fun cancelCommand(state: State, vararg first: Effect): Step =
        leaveCommand(state, *first).let { Step(it.state, it.effects + Effect.Show(Feedback.CommandCancelled)) }

    private fun locksScreen(command: Command?): Boolean = (command as? PhoneCommand.Global)?.action == GlobalCommand.LOCK_SCREEN

    // The command's result: the bar shows it or "Cancelled", Kestrel says what it must say, a repeatable command becomes
    // the one that "again" repeats, and an answer that did not answer the question is read as a new utterance.
    private fun commandEnded(state: State, result: CommandResult): Step {
        val cancelled = result.outcome == Outcome.CANCELLED
        val repeat = result.repeatable && result.outcome == Outcome.DONE
        val ended = state.copy(
            work = Work.IDLE,
            running = null,
            heard = null,
            asking = false,
            counting = false,
            lastCommand = if (repeat) result.acted ?: state.running else state.lastCommand,
            lastHeard = if (repeat) state.heard else state.lastHeard,
        )
        // Copied, because a property of another module allows no smart cast.
        val show = result.show
        val said = result.say
        val passOn = result.passOn
        val shown = when {
            show != null -> Effect.Show(Feedback.CommandText(show))
            cancelled && passOn == null -> Effect.Show(Feedback.CommandCancelled)
            else -> null
        }
        // A touch that cancelled a question also stops it while Kestrel still says it (section 14.1).
        var step = Step(ended, listOfNotNull(shown, Effect.StopSaying.takeIf { cancelled && state.speaking }))
        if (said != null) step = step.then { say(it, Reply.Text(said)) }
        if (passOn == null) return step
        return step.then { heard(it, Event.Heard(passOn, named = false)) }
    }

    // The question stays on the bar until the command ends. The wait for the answer starts now and again when the
    // question has been spoken.
    private fun ask(state: State, question: String): Step = say(
        state.copy(asking = true),
        Reply.Command(state.command, question),
        Effect.Show(Feedback.CommandText(question, keep = true)),
    ).then { waitForAnswer(it) }

    // Every wait gets the next number, as the waits for a task do, so that the end of an older wait changes nothing.
    private fun waitForAnswer(state: State, answering: Boolean = false): Step =
        Step(state.copy(wait = state.wait + 1), listOf(Effect.WaitForAnswer(answering)))

    // Section 7.3 and design D2 of sub-project 3: a transcript while a command runs. "Stop", "cancel", and a transcript
    // that starts with either, cancel it; so does "no" during a countdown. During a countdown, only those and the changes
    // of mode count, and anything else lets it go on. Kestrel's own requests cancel the command and then run. While the
    // command asks, the transcript is the answer; otherwise the new command is dropped.
    private fun heardDuringCommand(state: State, event: Event.Heard): Step {
        if (state.inCall) return callCommand(state, event)
        val request = Requests.read(event.text, event.named, stopSpotted = event.stopSpotted)
            ?: return if (state.screenOn) withoutWordsDuringCommand(state) else Step(state)
        val counted = state.copy(emptyTranscripts = 0)
        if (!counted.screenOn && !stopsSomething(request)) return Step(counted)
        val shown = Effect.Show(Feedback.Heard(event.text))
        val stops = request is Request.Stop || (request is Request.Other && startsWithStop(event.text)) ||
            (state.counting && saysNo(event.text, event.named))
        if (stops) return cancelCommand(counted, shown)
        if (state.counting && request != Request.PauseListening && request != Request.TurnOff) {
            return Step(counted, listOf(shown, Effect.HoldCommand(state.command, hold = false)))
        }
        if (request !is Request.Other) return leaveCommand(counted).then { whileIdle(it, request, event.text, shown) }.stopSayingOnce()
        if (state.asking) {
            return Step(counted.copy(asking = false), listOf(shown, Effect.AnswerCommand(state.command, request.text)))
        }
        return busy(counted, request.text, shown)
    }

    private fun withoutWordsDuringCommand(state: State): Step {
        val counted = withoutWords(state, awaiting = false)
        return when {
            state.counting -> Step(counted.state, counted.effects + Effect.HoldCommand(state.command, hold = false))
            state.asking -> counted.then { waitForAnswer(it) }
            else -> counted
        }
    }

    private fun startsWithStop(text: String): Boolean = Normalizer.words(text).firstOrNull { it !in BEFORE_STOP } in STOP_WORDS

    // "No", "Kestrel, no", "oh no, wait" or "no, not her": the first word that is neither a filler nor the name is "no",
    // whatever follows it. The name may come anywhere, misspelt too: a word counts as the name when Requests reads it
    // alone as the name.
    private fun saysNo(text: String, named: Boolean): Boolean =
        Normalizer.words(text).firstOrNull { it !in NO_FILLERS && Requests.read(it, named) != Request.Name } == "no"

    // Decision 3 of sub-project 3: a command that arrives while another runs is dropped. A recognised one says so on the
    // bar; bare speech may be conversation, so the bar says so only while dictation may run (a text or a bare command).
    private fun busy(state: State, text: String, shown: Effect): Step {
        val parsed = CommandParser.parse(text)
        val dictating = state.running is TextCommand || state.running is Command.Bare
        return if (parsed != null && (parsed !is Command.Bare || dictating)) {
            Step(state, listOf(shown, Effect.Show(Feedback.CommandBusy)))
        } else {
            Step(state, listOf(shown))
        }
    }

    // The spotter's "stop" or "cancel" cancels the command while Kestrel speaks or a countdown runs, at once. Its "Kestrel"
    // over Kestrel's voice cancels the command and waits for a task, as when idle, except during a countdown.
    private fun spottedDuringCommand(state: State, keyword: Keyword): Step = when {
        state.inCall -> Step(state)
        keyword != Keyword.KESTREL ->
            if (state.counting || state.speaking) cancelCommand(state.copy(spottedStop = true)) else Step(state)
        state.speaking && !state.counting -> leaveCommand(state).then { spotted(it, keyword) }.stopSayingOnce()
        else -> Step(state)
    }

    // A task that was cancelled before the command started may still report what it did.
    private fun lateTaskEnded(state: State, event: Event.TaskEnded): Step {
        if (event.task != state.task || !state.cancelled) return Step(state)
        val ended = state.copy(cancelled = false)
        return if (event.reply == null) Step(ended) else say(ended, Reply.Text(event.reply))
    }

    private fun spotted(state: State, keyword: Keyword): Step {
        if (!state.speaking || state.mode == Mode.OFF || state.inCall) return Step(state)
        return when (keyword) {
            // As after a cancelled command, the transcript of the same word then names no button (design D2).
            Keyword.STOP, Keyword.CANCEL ->
                if (state.work == Work.RUNNING_TASK) {
                    cancelTask(state.copy(spottedStop = true))
                } else {
                    Step(state.copy(work = Work.IDLE, spottedStop = true), listOf(Effect.StopSaying))
                }
            // Kestrel does not wait for a task while a task runs or the screen is off.
            Keyword.KESTREL ->
                if (state.work == Work.RUNNING_TASK || !state.screenOn) {
                    Step(state, listOf(Effect.StopSaying))
                } else {
                    waitForTask(state, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask))
                }
        }
    }

    // The wait starts again when "Yes?" has been spoken, so that the owner has all of it to start the answer.
    private fun speechEnded(state: State): Step {
        val ended = state.copy(speeches = maxOf(0, state.speeches - 1))
        return if (state.speeches == 1 && state.work == Work.AWAITING_TASK) waitForTask(ended) else Step(ended)
    }

    // A cancelled task still reports what it had already done, unless a newer task or wait has started (sections 14.1
    // and 14.5).
    private fun taskEnded(state: State, event: Event.TaskEnded): Step {
        if (event.task != state.task || !(state.work == Work.RUNNING_TASK || state.cancelled)) return Step(state)
        val ended = state.copy(work = Work.IDLE, cancelled = false)
        return if (event.reply == null) Step(ended) else say(ended, Reply.Text(event.reply))
    }

    // The bar shows "Yes?" at the start of the wait, also when "Yes?" cannot be spoken (section 8.9).
    private fun askForTask(state: State, vararg first: Effect): Step =
        waitForTask(state, *first, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }

    // Every wait gets the next number, so that the end of an older wait changes nothing.
    private fun waitForTask(state: State, vararg first: Effect, answering: Boolean = false): Step = Step(
        state.copy(work = Work.AWAITING_TASK, wait = state.wait + 1, cancelled = false),
        first.toList() + Effect.WaitForTask(answering),
    )

    private fun runTask(state: State, text: String, vararg first: Effect): Step {
        val task = state.task + 1
        return Step(state.copy(work = Work.RUNNING_TASK, task = task, cancelled = false), first.toList() + Effect.RunTask(task, text))
    }

    private fun cancelTask(state: State, vararg first: Effect): Step = Step(
        state.copy(work = Work.IDLE, cancelled = true),
        first.toList() + listOfNotNull(Effect.StopSaying.takeIf { state.speaking }) + Effect.CancelTask,
    )

    private fun say(state: State, reply: Reply, vararg first: Effect): Step =
        Step(state.copy(speeches = state.speeches + 1), first.toList() + Effect.Say(reply))

    private fun switch(state: State, mode: Mode, vararg first: Effect): Step =
        Step(state.copy(mode = mode), first.toList() + Effect.Show(Feedback.ModeChanged(mode)))

    // Shows a mode only when it changes.
    private fun changeMode(state: State, mode: Mode, vararg first: Effect): Step =
        if (state.mode == mode) Step(state, first.toList()) else switch(state, mode, *first)

    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off,
    // and the count of empty transcripts, so that the next voice session may show the hint again.
    private fun turnOff(state: State, vararg first: Effect): Step = Step(
        state.copy(
            mode = Mode.OFF,
            work = Work.IDLE,
            speeches = 0,
            cancelled = false,
            micBusy = false,
            emptyTranscripts = 0,
            noWordsShown = false,
            beforeCall = null,
            // The next run's reports start the count of wake-ups again (section 8.5).
            screenSeen = false,
            // "Again" never repeats a command of an earlier run, and the owner's last words go.
            lastCommand = null,
            lastHeard = null,
            spottedStop = false,
            questionEnded = false,
        ),
        first.toList() + listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.CancelTask.takeIf { state.work == Work.RUNNING_TASK },
            Effect.Show(Feedback.ModeChanged(Mode.OFF)),
            Effect.StopService,
        ),
    )

    // Section 8.5: after an unlock, Kestrel follows the setting "On screen wake-up" while it listens or is paused; a call
    // keeps its mode. Waiting for a task ends when the screen turns off.
    private fun screen(state: State, on: Boolean): Step {
        val changed = state.copy(
            screenOn = on,
            work = if (!on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work,
            // An unlock is a wake-up only after the owner has used the phone in this run: not the first report after a
            // start, not the unlock after a start on the lock screen, and not a report from a service that stops.
            screenSeen = state.mode != Mode.OFF && (on || state.screenSeen),
        )
        if (!on || state.screenOn || !state.screenSeen || (state.mode != Mode.LISTENING && state.mode != Mode.PAUSED)) {
            return Step(changed)
        }
        return when (state.onWakeUp) {
            WakeUpMode.RESUME -> Step(changed)
            WakeUpMode.LISTEN -> changeMode(changed, Mode.LISTENING)
            WakeUpMode.STAY_OFF -> turnOff(changed)
        }
    }

    // Section 8.5: 30 s without speech pause listening. A timer that ran out while Kestrel spoke, waited for a task, ran
    // one, did not record or found its microphone blocked starts again, because the owner was not silent then, or could
    // not be heard.
    private fun silent(state: State, silence: Int): Step = when {
        silence != state.silence || state.mode != Mode.LISTENING || !state.pauseAfterSilence -> Step(state)
        !hears(state) ->
            Step(state.copy(silence = state.silence + 1), listOf(Effect.TimeSilence))
        else -> switch(state, Mode.PAUSED)
    }

    // Section 8.5: while Kestrel listens with the setting on, the timer starts whenever Kestrel can hear the owner again
    // (it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends), when the setting
    // goes on, when the owner starts to speak, and when an utterance ends, so that the time counts from the end of the
    // owner's speech. Each timer gets the next number.
    private fun silenceTimer(before: State, event: Event, step: Step): Step {
        val after = step.state
        val start = after.pauseAfterSilence && hears(after) &&
            (
                !hears(before) || !before.pauseAfterSilence || event == Event.Speaking || event is Event.Heard ||
                    event == Event.NothingHeard
                )
        return if (start) Step(after.copy(silence = after.silence + 1), step.effects + Effect.TimeSilence) else step
    }

    // Section 9.4: a late answer to a question that ended without one comes within moments. A pause, a call, a task or the
    // wait for one, and the screen turning off end that time, and so does Event.LateAnswersEnded 10 s after the question.
    private fun endLateAnswers(step: Step): Step {
        val state = step.state
        val over = state.mode != Mode.LISTENING || !state.screenOn || state.work == Work.AWAITING_TASK ||
            state.work == Work.RUNNING_TASK
        return if (state.questionEnded && over) step.copy(state = state.copy(questionEnded = false)) else step
    }

    // Kestrel listens, idle, silent itself and recording, and Android does not block its microphone ("Microphone
    // blocked", section 14.6): only then does the time without speech count.
    private fun hears(state: State): Boolean =
        state.mode == Mode.LISTENING && !state.speaking && state.work == Work.IDLE && state.microphone && !state.micBusy

    // Section 8.6: the assistant gesture or an earbud's button. Kestrel listens, if it was paused, and waits for a task
    // as after "Kestrel" on its own; over its own voice it stops speaking and waits, as after an interruption with
    // "Kestrel". While a task runs, it does what "Kestrel" does then: it stops speaking. A call keeps its mode, and an
    // Assist while Kestrel is off was queued behind a turn-off, because Listening sends it only to an open session.
    private fun assist(state: State): Step = when {
        state.mode == Mode.OFF || state.inCall -> Step(state)
        state.work == Work.RUNNING_TASK -> Step(state, listOfNotNull(Effect.StopSaying.takeIf { state.speaking }))
        // Section 8.5: Kestrel does not record on the lock screen, so it asks nothing there; it hears the owner after the
        // unlock, which is therefore no wake-up, as after a start on the lock screen.
        !state.screenOn -> changeMode(
            state.copy(screenSeen = false),
            Mode.LISTENING,
            *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
        )
        state.speaking ->
            changeMode(state, Mode.LISTENING).then { waitForTask(it, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask)) }
        else -> changeMode(state, Mode.LISTENING).then { askForTask(it) }
    }

    // Section 8.7: while a call rings, only "answer", "answer on speaker" and "decline" count, also on the lock screen;
    // during the call, only "hang up", "mute" and "speaker" after the name, and only with the setting "Commands during a
    // call".
    private fun callCommand(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.IN_CALL && !state.callCommands) return Step(state)
        val command = Requests.readCall(event.text, event.named, ringing = state.mode == Mode.RINGING) ?: return Step(state)
        return Step(state, listOf(Effect.Show(Feedback.Heard(event.text)), Effect.CallCommand(command)))
    }

    // Section 8.7: a call that rings while Kestrel listens or is paused, or that is active, interrupts the mode, and the
    // mode comes back after the call. While Kestrel is off, calls change nothing.
    private fun call(state: State, call: CallState): Step = when (call) {
        CallState.RINGING ->
            if (state.mode == Mode.LISTENING || state.mode == Mode.PAUSED) enterCall(state, Mode.RINGING) else Step(state)
        CallState.ACTIVE -> when (state.mode) {
            Mode.LISTENING, Mode.PAUSED -> enterCall(state, Mode.IN_CALL)
            Mode.RINGING -> switch(state, Mode.IN_CALL)
            Mode.OFF, Mode.IN_CALL -> Step(state)
        }
        CallState.IDLE -> {
            val back = state.beforeCall
            if (!state.inCall || back == null) Step(state) else switch(state.copy(beforeCall = null), back)
        }
    }

    // Kestrel stops speaking at once, because Android refuses its audio focus during a call (prototype P3). The call
    // cancels a running task and ends the wait for one; sub-project 5 asks "Continue the task?" afterwards (section 14.4).
    private fun enterCall(state: State, mode: Mode): Step = switch(
        state.copy(
            beforeCall = state.mode,
            work = Work.IDLE,
            cancelled = state.cancelled || state.work == Work.RUNNING_TASK,
        ),
        mode,
        *listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.CancelTask.takeIf { state.work == Work.RUNNING_TASK },
        ).toTypedArray(),
    )

    // During a call, the toggle, the pause and the resume choose the mode that comes back after the call, and the bar
    // shows it.
    private fun afterCall(state: State, mode: Mode): Step =
        if (state.beforeCall == mode) {
            Step(state)
        } else {
            Step(state.copy(beforeCall = mode), listOf(Effect.Show(Feedback.ModeChanged(mode))))
        }

    /** This step, then the step that [next] makes from its state. */
    private fun Step.then(next: (State) -> Step): Step = next(state).let { Step(it.state, effects + it.effects) }

    // Section 14.6: how many transcripts without words in a row bring the hint.
    private const val EMPTY_TRANSCRIPTS_FOR_HINT = 3
}
