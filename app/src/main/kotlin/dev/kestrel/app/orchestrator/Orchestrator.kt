package dev.kestrel.app.orchestrator

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
 * Orchestrator then handles [Event.SpeechEnded], and for a [Effect.RunTask] [Event.TaskEnded] without a reply. The next
 * effects and events still run. [onError] must not throw. An [Error] from [perform], such as the NotImplementedError
 * that TODO() throws, is not caught and ends the Orchestrator's loop. The timer that ends the wait for a task after
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it.
 */
class Orchestrator(
    private val scope: CoroutineScope,
    private val perform: (Effect) -> Unit,
    private val onError: (Exception) -> Unit,
    private val waitMillis: Long = WAIT_MILLIS,
    private val answerMillis: Long = ANSWER_MILLIS,
) {
    // Each event with the check that decides, when its turn comes, whether the Orchestrator still handles it.
    private val events = Channel<Pair<Event, () -> Boolean>>(Channel.UNLIMITED)
    private val current = MutableStateFlow(State())
    private var wait: Job? = null

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
        val step = Rules.next(current.value, event)
        current.value = step.state
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
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
            } else {
                try {
                    perform(effect)
                } catch (e: Exception) {
                    // perform does not suspend, so a CancellationException from it is not the Orchestrator's own.
                    currentCoroutineContext().ensureActive()
                    onError(e)
                    when (effect) {
                        is Effect.Say -> notStarted += Event.SpeechEnded
                        is Effect.RunTask -> notStarted += Event.TaskEnded(effect.task, reply = null)
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
    }
}