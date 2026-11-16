package dev.kestrel.app.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies [Rules] to Kestrel's events one at a time, so that two parts of Kestrel never act at once (design spec,
 * section 7.3). [perform] runs each effect in order on the Orchestrator's coroutine, so it must return quickly; an effect
 * that throws goes to [onError], and the next ones still run. The timer that ends the wait for a task after "Yes?" runs
 * here.
 */
class Orchestrator(
    private val scope: CoroutineScope,
    private val perform: (Effect) -> Unit,
    private val onError: (Exception) -> Unit,
    private val waitMillis: Long = WAIT_MILLIS,
) {
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val current = MutableStateFlow(State())
    private var wait: Job? = null

    /** The state after the last event. */
    val state: StateFlow<State> = current.asStateFlow()

    init {
        scope.launch {
            for (event in events) handle(event)
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) {
        events.trySend(event)
    }

    private fun handle(event: Event) {
        val step = Rules.next(current.value, event)
        current.value = step.state
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
        }
        for (effect in step.effects) {
            if (effect == Effect.WaitForTask) {
                wait?.cancel()
                wait = scope.launch {
                    delay(waitMillis)
                    send(Event.WaitEnded)
                }
            } else {
                try {
                    perform(effect)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    private companion object {
        // How long Kestrel waits for the task after "Yes?".
        const val WAIT_MILLIS = 8_000L
    }
}
