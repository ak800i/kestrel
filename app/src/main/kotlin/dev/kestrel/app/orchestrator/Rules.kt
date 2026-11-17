package dev.kestrel.app.orchestrator

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition

/** The listening modes (design spec, section 8.5). Ringing and In call come with calls in plan 2D. */
enum class Mode { OFF, LISTENING, PAUSED }

/** What Kestrel is doing besides listening: the spec's "current activity" (section 7.3). */
enum class Work {
    IDLE,

    /** After "Kestrel" on its own: the next utterance is the task. */
    AWAITING_TASK,

    /** A task runs, and only "stop" and "cancel" count (section 11.10). */
    RUNNING_TASK,
}

/**
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished. [task] is the number of the latest task and [wait] the number of the latest wait for a task, so that the
 * end of an older task or wait changes nothing. [cancelled]: the latest task was cancelled and has not reported yet.
 * [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6).
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
) {
    val speaking: Boolean get() = speeches > 0

    /** Whether Kestrel records: in every mode but off, while the screen is on and the phone unlocked (section 8.5). */
    val microphone: Boolean get() = mode != Mode.OFF && screenOn

    /** Which utterances the pipeline transcribes, and whether it holds them (sections 8.3, 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
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

    /** A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. */
    data class Heard(val text: String, val named: Boolean) : Event {
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
     * its accessibility service is on. Kestrel says so, because the feedback bar does not exist then (section 14).
     */
    data object SettingsUnavailable : Event

    /**
     * An utterance ended without a transcript: the pipeline dropped it, or transcribing it failed. Like a transcript
     * without words, it gives the owner the whole wait for a task again, but it does not count towards the hint about
     * empty transcripts (section 14.6).
     */
    data object NothingHeard : Event
}

/** What Kestrel says. */
sealed interface Reply {
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

    /** Why Kestrel did not open its settings: its accessibility service is off (section 14). */
    data object SettingsNeedService : Reply

    data class Text(val text: String) : Reply {
        override fun toString() = "Text(${text.length} characters)"
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
}

/** What the Orchestrator asks of the rest of Kestrel. The microphone and recognition follow [State] instead. */
sealed interface Effect {
    data class Say(val reply: Reply) : Effect

    data object StopSaying : Effect

    data class RunTask(val task: Int, val text: String) : Effect {
        override fun toString() = "RunTask($task, ${text.length} characters)"
    }

    data object CancelTask : Effect

    /** Speech for the instant commands of sub-project 3. */
    data class RunCommand(val text: String) : Effect {
        override fun toString() = "RunCommand(${text.length} characters)"
    }

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
}

/** A transition: the new state, and what to do, in order. */
data class Step(val state: State, val effects: List<Effect> = emptyList())

/** Every transition of the Orchestrator, in one place (design spec, section 7.3). */
object Rules {
    fun next(state: State, event: Event): Step = when (event) {
        Event.Toggle -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        is Event.Start ->
            if (state.mode == Mode.OFF) switch(state, if (event.paused) Mode.PAUSED else Mode.LISTENING) else Step(state)
        Event.Pause -> if (state.mode == Mode.LISTENING) switch(state, Mode.PAUSED) else Step(state)
        // The notification exists only while Kestrel is on, so a Resume while off comes from a stale notification.
        Event.Resume -> if (state.mode == Mode.PAUSED) switch(state, Mode.LISTENING) else Step(state)
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
        is Event.Screen -> Step(
            state.copy(screenOn = event.on, work = if (!event.on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work),
        )
        is Event.WaitEnded ->
            if (state.work == Work.AWAITING_TASK && event.wait == state.wait) Step(state.copy(work = Work.IDLE)) else Step(state)
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
        Event.NothingHeard ->
            if (state.mode != Mode.OFF && state.work == Work.AWAITING_TASK) waitForTask(state) else Step(state)
    }

    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        val awaiting = state.work == Work.AWAITING_TASK
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a task ends when the screen turns off.
        val request = Requests.read(event.text, event.named, afterName = awaiting)
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
            Work.IDLE -> whileIdle(counted, request, event.text, shown)
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
        is Request.Task -> if (request.stopFirst) cancelTask(state, shown).then { runTask(it, request.text) } else Step(state)
        Request.PauseListening -> cancelTask(state, shown).then { changeMode(it, Mode.PAUSED) }
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings ->
            if (request.stopFirst) cancelTask(state, shown).then { Step(it, listOf(Effect.OpenSettings)) } else Step(state)
        else -> Step(state)
    }

    // The answer to "Yes?" is read as the words after the name, so it is never Other.
    private fun whileAwaitingTask(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Task -> runTask(state, request.text, shown)
        is Request.Other -> runTask(state, request.text, shown)
        Request.Name -> askForTask(state, shown)
        Request.WakeUp -> changeMode(state.copy(work = Work.IDLE), Mode.LISTENING, shown)
        is Request.Stop -> Step(state.copy(work = Work.IDLE), listOf(shown))
        Request.PauseListening -> changeMode(state.copy(work = Work.IDLE), Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings -> Step(state.copy(work = Work.IDLE), listOf(shown, Effect.OpenSettings))
    }

    private fun whileIdle(state: State, request: Request, text: String, shown: Effect): Step = when (request) {
        is Request.Task -> runTask(state, request.text, shown)
        Request.Name -> askForTask(state, shown)
        Request.WakeUp -> changeMode(state, Mode.LISTENING, shown)
        Request.PauseListening -> changeMode(state, Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings -> Step(state, listOf(shown, Effect.OpenSettings))
        is Request.Stop -> if (request.named) {
            // "Kestrel, stop" with nothing running only ends the speech; plain "stop" may name a button.
            Step(state, listOfNotNull(shown, Effect.StopSaying.takeIf { state.speaking }))
        } else {
            command(state, text.trim(), shown)
        }
        is Request.Other -> command(state, request.text, shown)
    }

    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5), and not after the screen turned off.
    private fun command(state: State, text: String, shown: Effect): Step =
        if (state.mode == Mode.LISTENING && state.screenOn) Step(state, listOf(shown, Effect.RunCommand(text))) else Step(state)

    private fun spotted(state: State, keyword: Keyword): Step {
        if (!state.speaking || state.mode == Mode.OFF) return Step(state)
        return when (keyword) {
            Keyword.STOP, Keyword.CANCEL ->
                if (state.work == Work.RUNNING_TASK) cancelTask(state) else Step(state.copy(work = Work.IDLE), listOf(Effect.StopSaying))
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
    private fun askForTask(state: State, shown: Effect): Step =
        waitForTask(state, shown, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }

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
        ),
        first.toList() + listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.CancelTask.takeIf { state.work == Work.RUNNING_TASK },
            Effect.Show(Feedback.ModeChanged(Mode.OFF)),
            Effect.StopService,
        ),
    )

    /** This step, then the step that [next] makes from its state. */
    private fun Step.then(next: (State) -> Step): Step = next(state).let { Step(it.state, effects + it.effects) }

    // Section 14.6: how many transcripts without words in a row bring the hint.
    private const val EMPTY_TRANSCRIPTS_FOR_HINT = 3
}
