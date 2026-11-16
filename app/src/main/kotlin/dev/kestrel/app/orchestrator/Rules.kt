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
 * finished, and [task] is the number of the latest task, so that the end of an older task changes nothing.
 */
data class State(
    val mode: Mode = Mode.OFF,
    val work: Work = Work.IDLE,
    val speeches: Int = 0,
    val task: Int = 0,
    val screenOn: Boolean = true,
    val micBusy: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0

    /** Whether Kestrel records: in every mode but off, while the screen is on and the phone unlocked (section 8.5). */
    val microphone: Boolean get() = mode != Mode.OFF && screenOn

    /** Which utterances the pipeline transcribes (sections 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            work != Work.IDLE || mode == Mode.LISTENING -> Recognition.ALL
            else -> Recognition.NAMED
        }
}

/** What happens to Kestrel (design spec, section 7.3). */
sealed interface Event {
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event

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

    /** A keyword from the spotter that was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : Event

    /** A reply has been spoken, stopped or refused, or speaking it failed. Send it once for each reply, or recognition stays off. */
    data object SpeechEnded : Event

    /** Task number [task] ended, with a [reply] for the owner or none. */
    data class TaskEnded(val task: Int, val reply: String?) : Event {
        override fun toString() = "TaskEnded($task, reply=${reply?.let { "${it.length} characters" }})"
    }

    /** Android started or stopped silencing Kestrel's recording. */
    data class MicBusy(val busy: Boolean) : Event

    /** The screen is on and the phone unlocked ([on]), or the screen turned off or the phone locked. */
    data class Screen(val on: Boolean) : Event

    /** Kestrel has waited long enough for a task after "Yes?". */
    data object WaitEnded : Event
}

/** What Kestrel says. */
sealed interface Reply {
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

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

    /** Starts the timer that ends the wait for a task after "Yes?". The Orchestrator runs it itself. */
    data object WaitForTask : Effect

    /** Stops the listening service. */
    data object StopService : Effect
}

/** A transition: the new state, and what to do, in order. */
data class Step(val state: State, val effects: List<Effect> = emptyList())

/** Every transition of the Orchestrator, in one place (design spec, section 7.3). */
object Rules {
    fun next(state: State, event: Event): Step = when (event) {
        Event.Toggle -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        Event.Pause -> if (state.mode == Mode.LISTENING) switch(state, Mode.PAUSED) else Step(state)
        Event.Resume -> if (state.mode == Mode.LISTENING) Step(state) else switch(state, Mode.LISTENING)
        Event.TurnOff -> turnOff(state)
        is Event.Heard -> heard(state, event)
        is Event.Spotted -> spotted(state, event.keyword)
        Event.SpeechEnded -> Step(state.copy(speeches = maxOf(0, state.speeches - 1)))
        is Event.TaskEnded -> taskEnded(state, event)
        is Event.MicBusy -> Step(
            state.copy(micBusy = event.busy),
            if (event.busy && !state.micBusy) listOf(Effect.Show(Feedback.MicBusy)) else emptyList(),
        )
        is Event.Screen -> Step(
            state.copy(screenOn = event.on, work = if (!event.on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work),
        )
        Event.WaitEnded -> Step(if (state.work == Work.AWAITING_TASK) state.copy(work = Work.IDLE) else state)
    }

    private fun heard(state: State, event: Event.Heard): Step {
        // A transcription that was queued before the microphone stopped can finish after the screen turned off; no
        // request starts then (section 14.4).
        if (state.mode == Mode.OFF || !state.screenOn) return Step(state)
        val request = Requests.read(event.text, event.named) ?: return Step(state)
        val shown = Effect.Show(Feedback.Heard(event.text))
        return when (state.work) {
            Work.RUNNING_TASK -> whileTaskRuns(state, request, shown)
            Work.AWAITING_TASK -> whileAwaitingTask(state, request, shown)
            Work.IDLE -> whileIdle(state, request, event.text, shown)
        }
    }

    // Section 11.10: while a task runs, only "stop" and "cancel" count, and the ways to pause or turn Kestrel off.
    // "Stop. Kestrel, [task]" cancels the running task and runs the new one.
    private fun whileTaskRuns(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Stop -> cancelTask(state, shown)
        is Request.Task -> if (request.stopFirst) {
            val cancelled = cancelTask(state, shown)
            val started = runTask(cancelled.state, request.text)
            Step(started.state, cancelled.effects + started.effects)
        } else {
            Step(state)
        }
        Request.PauseListening -> {
            val cancelled = cancelTask(state, shown)
            val paused = switch(cancelled.state, Mode.PAUSED)
            Step(paused.state, cancelled.effects + paused.effects)
        }
        Request.TurnOff -> turnOff(state)
        else -> Step(state)
    }

    private fun whileAwaitingTask(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Task -> runTask(state, request.text, shown)
        is Request.Other -> runTask(state, request.text, shown)
        Request.Name -> say(state, Reply.Yes, shown, Effect.WaitForTask)
        Request.WakeUp -> switch(state.copy(work = Work.IDLE), Mode.LISTENING, shown)
        is Request.Stop -> Step(state.copy(work = Work.IDLE), listOf(shown))
        Request.PauseListening -> switch(state.copy(work = Work.IDLE), Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state)
    }

    private fun whileIdle(state: State, request: Request, text: String, shown: Effect): Step = when (request) {
        is Request.Task -> runTask(state, request.text, shown)
        Request.Name -> say(state.copy(work = Work.AWAITING_TASK), Reply.Yes, shown, Effect.WaitForTask)
        Request.WakeUp -> if (state.mode == Mode.PAUSED) switch(state, Mode.LISTENING, shown) else Step(state, listOf(shown))
        Request.PauseListening -> if (state.mode == Mode.LISTENING) switch(state, Mode.PAUSED, shown) else Step(state, listOf(shown))
        Request.TurnOff -> turnOff(state)
        // "Kestrel, stop" with nothing running only ends the speech; plain "stop" may name a button.
        is Request.Stop -> if (request.named) Step(state, listOfNotNull(shown, Effect.StopSaying.takeIf { state.speaking })) else command(state, text.trim(), shown)
        is Request.Other -> command(state, request.text, shown)
    }

    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5).
    private fun command(state: State, text: String, shown: Effect): Step =
        if (state.mode == Mode.LISTENING) Step(state, listOf(shown, Effect.RunCommand(text))) else Step(state)

    private fun spotted(state: State, keyword: Keyword): Step {
        if (!state.speaking || state.mode == Mode.OFF) return Step(state)
        return when (keyword) {
            Keyword.STOP, Keyword.CANCEL ->
                if (state.work == Work.RUNNING_TASK) cancelTask(state) else Step(state.copy(work = Work.IDLE), listOf(Effect.StopSaying))
            Keyword.KESTREL ->
                if (state.work == Work.RUNNING_TASK) {
                    Step(state, listOf(Effect.StopSaying))
                } else {
                    Step(state.copy(work = Work.AWAITING_TASK), listOf(Effect.StopSaying, Effect.WaitForTask))
                }
        }
    }

    private fun taskEnded(state: State, event: Event.TaskEnded): Step {
        if (state.work != Work.RUNNING_TASK || event.task != state.task) return Step(state)
        val ended = state.copy(work = Work.IDLE)
        return if (event.reply == null) Step(ended) else say(ended, Reply.Text(event.reply))
    }

    private fun runTask(state: State, text: String, vararg first: Effect): Step {
        val task = state.task + 1
        return Step(state.copy(work = Work.RUNNING_TASK, task = task), first.toList() + Effect.RunTask(task, text))
    }

    private fun cancelTask(state: State, vararg first: Effect): Step = Step(
        state.copy(work = Work.IDLE),
        first.toList() + listOfNotNull(Effect.StopSaying.takeIf { state.speaking }) + Effect.CancelTask,
    )

    private fun say(state: State, reply: Reply, vararg first: Effect): Step =
        Step(state.copy(speeches = state.speeches + 1), first.toList() + Effect.Say(reply))

    private fun switch(state: State, mode: Mode, vararg first: Effect): Step =
        Step(state.copy(mode = mode), first.toList() + Effect.Show(Feedback.ModeChanged(mode)))

    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off.
    private fun turnOff(state: State): Step = Step(
        state.copy(mode = Mode.OFF, work = Work.IDLE, speeches = 0, micBusy = false),
        listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.CancelTask.takeIf { state.work == Work.RUNNING_TASK },
            Effect.Show(Feedback.ModeChanged(Mode.OFF)),
            Effect.StopService,
        ),
    )
}
