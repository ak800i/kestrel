package dev.kestrel.app.orchestrator

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies [Rules] to Kestrel's events one at a time, so that two parts of Kestrel never act at once (design spec,
 * section 7.3). [perform] runs each effect in order on the Orchestrator's coroutine, so it must return quickly. An effect
 * that throws, even with a CancellationException, goes to [onError] and counts as not started: for a [Effect.Say] the
 * Orchestrator then handles [Event.SpeechEnded]. The next effects and events still run. [onError] must not throw. An [Error] from [perform], such as the NotImplementedError
 * that TODO() throws, is not caught and ends the Orchestrator's loop. The timer that ends the wait for a task after
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it. So
 * does the timer of the pause after [silenceMillis] without speech (section 8.5), and the timer of the answer to an
 * instant command's question: [questionMillis] for the owner to start the answer, and [answerMillis] once the owner has
 * started it; after a question without an answer, a number counts as a late answer for [lateMillis] (section 9.4). The
 * answer to a question of the conversation that the voice asked has the same waits, and the end of its wait is the
 * answer `Silence` (design 11 of sub-project 7); the conversation runs a goal's hand-over and pauses itself. The
 * follow-up window after a spoken final reply lasts [State.followUpMillis], and [answerMillis] once the owner has started
 * an utterance in it (design 6.6 of sub-project 7). A
 * [Effect.RunCommand] that throws counts as a command that ended as failed,
 * and the bar shows [commandFailed], so that Kestrel never fails silently (section 14). [onStep] sees each event with the
 * state before it and the step that it caused, on the Orchestrator's coroutine, after the new state is set and before the
 * step's effects run; the debug hooks of plan 4D read it, and a failure in it goes to [onError].
 */
class Orchestrator(
    private val scope: CoroutineScope,
    private val perform: (Effect) -> Unit,
    private val onError: (Exception) -> Unit,
    private val waitMillis: Long = WAIT_MILLIS,
    private val answerMillis: Long = ANSWER_MILLIS,
    private val silenceMillis: Long = SILENCE_MILLIS,
    private val questionMillis: Long = QUESTION_MILLIS,
    private val lateMillis: Long = LATE_MILLIS,
    private val commandFailed: String? = null,
    private val onStep: (Event, State, Step) -> Unit = { _, _, _ -> },
) {
    // Each event with the check that decides, when its turn comes, whether the Orchestrator still handles it.
    private val events = Channel<Pair<Event, () -> Boolean>>(Channel.UNLIMITED)
    private val current = MutableStateFlow(State())
    private var wait: Job? = null
    private var silence: Job? = null
    private var answer: Job? = null
    private var late: Job? = null
    private var followUp: Job? = null

    /** The state after the last event. */
    val state: StateFlow<State> = current.asStateFlow()

    init {
        scope.launch {
            for ((event, valid) in events) if (valid()) handle(event)
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = send(event) { true }

    /**
     * Sends [event], which the Orchestrator handles only if [valid] still returns true when the event's turn comes. Any
     * thread may call it; [valid] runs on the Orchestrator's coroutine, after every event that was sent before this one,
     * and must not throw.
     */
    fun send(event: Event, valid: () -> Boolean) {
        events.trySend(event to valid)
    }

    private suspend fun handle(event: Event) {
        val before = current.value
        val step = Rules.next(before, event)
        current.value = step.state
        try {
            onStep(event, before, step)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            onError(e)
        }
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
        }
        if (step.state.mode != Mode.LISTENING) {
            silence?.cancel()
            silence = null
        }
        if (!step.state.asking && step.state.voiceQuestion == null) {
            answer?.cancel()
            answer = null
        }
        if (!step.state.questionEnded) {
            late?.cancel()
            late = null
        }
        if (step.state.work != Work.FOLLOWING_UP) {
            followUp?.cancel()
            followUp = null
        }
        val notStarted = mutableListOf<Event>()
        for (effect in step.effects) {
            if (effect is Effect.WaitForTask) {
                val number = step.state.wait
                val millis = if (effect.answering) answerMillis else waitMillis
                wait?.cancel()
                wait = scope.launch {
                    delay(millis)
                    send(Event.WaitEnded(number))
                }
            } else if (effect is Effect.WaitForAnswer) {
                val number = step.state.wait
                val millis = if (effect.answering) answerMillis else questionMillis
                answer?.cancel()
                answer = scope.launch {
                    delay(millis)
                    send(Event.AnswerWaitEnded(number))
                }
            } else if (effect == Effect.TimeLateAnswers) {
                val number = step.state.command
                late?.cancel()
                late = scope.launch {
                    delay(lateMillis)
                    send(Event.LateAnswersEnded(number))
                }
            } else if (effect is Effect.TimeFollowUp) {
                val number = step.state.followUp
                val millis = if (effect.answering) answerMillis else step.state.followUpMillis
                followUp?.cancel()
                followUp = scope.launch {
                    delay(millis)
                    send(Event.FollowUpEnded(number))
                }
            } else if (effect == Effect.TimeSilence) {
                val number = step.state.silence
                silence?.cancel()
                silence = scope.launch {
                    delay(silenceMillis)
                    send(Event.Silent(number))
                }
            } else {
                try {
                    perform(effect)
                } catch (e: Exception) {
                    // perform does not suspend, so a CancellationException from it is not the Orchestrator's own.
                    currentCoroutineContext().ensureActive()
                    onError(e)
                    when (effect) {
                        is Effect.Say -> notStarted += Event.SpeechEnded
                        is Effect.RunCommand ->
                            notStarted += Event.CommandEnded(effect.command, CommandResult(Outcome.FAILED, show = commandFailed))
                        else -> {}
                    }
                }
            }
        }
        notStarted.forEach { send(it) }
    }

    private companion object {
        // Section 8.3: the owner has 8 s to start the task after "Yes?". The wait starts again when "Yes?" has been
        // spoken. Plan 2D measures it on the A71.
        const val WAIT_MILLIS = 8_000L

        // Once the owner has started to answer: the longest utterance (30 s, section 8.3), the end-of-speech pause
        // (0.7 s) and a transcription on the A71 (at most 4 s, section 5.3). The endpointer holds an answer and lets it
        // go up to 1.85 s after its end, but at the latest 30 s after its start, so the worst case stays at about
        // 30.7 s and a transcription. Plan 2D measures it on the A71.
        const val ANSWER_MILLIS = 35_000L

        // Section 8.5: the optional pause after 30 s without speech.
        const val SILENCE_MILLIS = 30_000L

        // Design D2 of sub-project 3: the owner has 15 s to start answering an instant command's question, counted from
        // the end of the question.
        const val QUESTION_MILLIS = 15_000L

        // Section 9.4: a late answer began before the question's wait ran out, so its transcript comes within a few
        // seconds of the end of the question.
        const val LATE_MILLIS = 10_000L
    }
}
