package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Orchestrator
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.State
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Starts and stops ListeningService. Any thread may call it. */
internal interface ServiceControl {
    /** Asks Android to start the service in the foreground. Throws when Android does not allow the start. */
    fun start()

    /**
     * Asks the service to stop itself. It stops unless a newer start, such as a notification action, is still on its way
     * to it; that start reports to [Listening.onForeground]. Nothing happens when the service does not run.
     */
    fun stop()
}

/** Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. */
internal fun interface FeedbackBar {
    fun show(text: String)
}

/** What Kestrel says and shows while it listens. Plan 2C-3 decides the final wording. */
internal object ListeningTexts {
    const val YES = "Yes?"
    const val NO_TASKS = "I can't run tasks yet."
    const val LISTENING = "Listening"
    const val PAUSED = "Paused"
    const val OFF = "Off"
    const val MIC_BUSY = "Microphone busy"
    const val CANNOT_START = "Kestrel could not start listening. Start it from the notification or the tile."
    const val MODELS_MISSING = "The speech models are missing; see the setup checklist."
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
}

/**
 * Kestrel's listening, one for the whole process (design spec, sections 7.2, 7.3 and 8.5). It lives in AppGraph, so that
 * the accessibility service, the notification and later the tile can send it events whether or not ListeningService
 * runs. It owns the [Orchestrator] and follows its state: when the mode leaves Off, it starts ListeningService; once the
 * service runs in the foreground, it opens a voice session; while the session is open, the microphone runs exactly while
 * [State.microphone] is true, and the pipeline's recognition is [State.recognition] before the first frame and after
 * every change. [Effect.StopService] closes the session and then stops the service.
 *
 * Threads: the Orchestrator's loop, its effects, the following of the state, and the microphone's start and stop run on
 * [thread], one at a time. A session opens and closes in a coroutine of [scope] behind one lock, so that a new start
 * waits until a shutdown has finished: two recognisers would need about 1.4 GB. The public functions may be called from
 * any thread. [report] gets each failure with a fixed description of what failed, and must log no message.
 * [openSettings] opens Kestrel's settings app; the controller's thread calls it, and it throws when Android refuses.
 */
class Listening internal constructor(
    private val scope: CoroutineScope,
    private val thread: CoroutineDispatcher,
    private val sessions: VoiceSessions,
    private val services: ServiceControl,
    private val bar: FeedbackBar,
    private val report: (String, Throwable) -> Unit,
    private val openSettings: () -> Unit,
    microphones: () -> Microphone,
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }

    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones

    // Only [thread] uses these. [requested]: the service was asked to start since the last stop. [running]: the
    // service's instance that runs in the foreground and that Kestrel has not asked to stop.
    private var serviceState = ServiceState.STOPPED
    private var requested = false
    private var running: Any? = null

    // Android was asked to start the service, and the service has not reported back yet. Stopping such a service makes
    // Android end Kestrel's process.
    private var awaitingForeground = false

    // Why Kestrel turned itself off. The next "Off" on the bar shows it instead (section 14.6).
    private var offReason: String? = null
    private var session: Session? = null
    private var listener: Listener? = null
    private var microphoneOn = false
    private val lifecycle = Mutex()
    private val sessionState = MutableStateFlow(false)

    /** Whether a voice session is open: from the end of its opening until its close has returned. */
    internal val sessionOpen: StateFlow<Boolean> = sessionState.asStateFlow()

    private val orchestrator = Orchestrator(
        CoroutineScope(scope.coroutineContext + thread),
        perform = ::perform,
        onError = { report("An effect", it) },
    )

    /** The Orchestrator's state after the last event. */
    val state: StateFlow<State> get() = orchestrator.state

    init {
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)

    /** ListeningService, whose instance is [instance], runs in the foreground. */
    fun onForeground(instance: Any) {
        scope.launch(thread) { foreground(instance) }
    }

    /** ListeningService could not enter the foreground; [error] says why. */
    fun onForegroundFailed(error: Exception) {
        scope.launch(thread) {
            // The service stops itself after a failed startForeground(), which Android allows.
            awaitingForeground = false
            if (serviceState == ServiceState.STOPPED) {
                // A start that nobody asked for, such as a stale notification action.
                report("Starting the listening service", error)
            } else {
                // Also for the running service: a notification action's start failed, and the service stops itself.
                turnOff("Starting the listening service", error, ListeningTexts.CANNOT_START)
            }
        }
    }

    /** Android destroyed the ListeningService instance [instance], whether Kestrel asked for it or not. */
    fun onServiceDestroyed(instance: Any) {
        scope.launch(thread) { destroyed(instance) }
    }

    private fun followSafely(state: State) {
        try {
            follow(state)
        } catch (e: Error) {
            // Such as an OutOfMemoryError when the microphone's thread cannot be created. Thrown on, it would end the
            // state's collector, and then nothing would start the service or stop the microphone any more.
            report("Following the listening state", e)
            send(Event.TurnOff)
        }
    }

    private fun follow(state: State) {
        if (state.mode != Mode.OFF && serviceState == ServiceState.STOPPED) start()
        val current = session ?: return
        current.recognition = state.recognition
        if (state.microphone && !microphoneOn) {
            try {
                current.startMicrophone()
                microphoneOn = true
            } catch (e: Exception) {
                // A start that failed after the microphone opened leaves it open until a stop.
                releaseMicrophone(current)
                turnOff("Starting the microphone", e, ListeningTexts.MICROPHONE_FAILED)
            }
        } else if (!state.microphone && microphoneOn) {
            microphoneOn = false
            releaseMicrophone(current)
        }
    }

    private fun start() {
        serviceState = ServiceState.STARTING
        // A reason from an earlier start must never come back.
        offReason = null
        scope.launch(thread) {
            // After a shutdown in progress, so that its stop cannot end the new service.
            lifecycle.withLock {
                if (serviceState != ServiceState.STARTING || requested) return@withLock
                requested = true
                try {
                    services.start()
                    awaitingForeground = true
                } catch (e: Exception) {
                    turnOff("Starting the listening service", e, ListeningTexts.CANNOT_START)
                }
            }
        }
    }

    private fun foreground(instance: Any) {
        awaitingForeground = false
        when (serviceState) {
            // Before the start was asked for, the instance is the one that a shutdown in progress is about to stop.
            ServiceState.STARTING -> if (requested) {
                serviceState = ServiceState.FOREGROUND
                running = instance
                open()
            }
            // A service that nobody asked for, such as one that a stale notification started while Kestrel was off.
            ServiceState.STOPPED -> scope.launch(thread) {
                lifecycle.withLock { if (serviceState == ServiceState.STOPPED) stopService() }
            }
            // The running service handled a notification action.
            ServiceState.FOREGROUND -> Unit
        }
    }

    private fun destroyed(instance: Any) {
        if (instance !== running) return
        // Android ended the service on its own, so Kestrel no longer listens, and the Orchestrator must know it.
        running = null
        send(Event.TurnOff)
    }

    private fun open() {
        scope.launch(thread) {
            lifecycle.withLock {
                if (serviceState != ServiceState.FOREGROUND || session != null) return@withLock
                val events = Listener()
                val opened = try {
                    sessions.open(microphones(), events)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelsNotReadyException) {
                    turnOff("Opening the voice session", e, ListeningTexts.MODELS_MISSING)
                    return@withLock
                } catch (e: Exception) {
                    turnOff("Opening the voice session", e, ListeningTexts.CANNOT_START)
                    return@withLock
                } catch (e: Error) {
                    // Kestrel must not look as if it listened. AppGraph's handler still logs the error's classes.
                    send(Event.TurnOff)
                    throw e
                }
                if (serviceState != ServiceState.FOREGROUND) {
                    // Kestrel was turned off while the session opened.
                    events.active = false
                    closeQuietly(opened)
                    return@withLock
                }
                session = opened
                listener = events
                sessionState.value = true
                followSafely(state.value)
            }
        }
    }

    private fun shutdown() {
        val closing = session
        // From now on, the closing session's events, such as a late transcript, are dropped. See Listener.
        listener?.active = false
        session = null
        listener = null
        microphoneOn = false
        serviceState = ServiceState.STOPPED
        requested = false
        running = null
        scope.launch(thread) {
            lifecycle.withLock {
                try {
                    // The session first, so that Kestrel plays audio only while the service runs (B5).
                    if (closing != null) closeQuietly(closing)
                } finally {
                    // Also after an Error from close(), so that the service does not run on while the mode is Off.
                    // Decided here, under the lock: a start that waited for the lock may have been asked for since
                    // shutdown() ran. A service that has not called startForeground() yet reports to foreground(),
                    // which stops it then.
                    if (!requested && !awaitingForeground) stopService()
                }
            }
        }
        // The Orchestrator may turn Kestrel on again in the same run of events. Its state then equals the one that the
        // collector saw last, and StateFlow skips an equal value, so follow() would never see this Off.
        scope.launch(thread) { followSafely(state.value) }
    }

    private suspend fun closeQuietly(closing: Session) {
        try {
            closing.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Closing the voice session", e)
        } finally {
            sessionState.value = false
        }
    }

    private fun stopService() {
        try {
            services.stop()
        } catch (e: Exception) {
            report("Stopping the listening service", e)
        }
    }

    private fun releaseMicrophone(session: Session) {
        try {
            session.stopMicrophone()
        } catch (e: Exception) {
            report("Stopping the microphone", e)
        }
    }

    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, text: String) {
        report(what, error)
        if (state.value.mode == Mode.OFF) {
            bar.show(text)
        } else {
            offReason = text
            send(Event.TurnOff)
        }
    }

    private fun perform(effect: Effect) {
        try {
            performUnguarded(effect)
        } catch (e: Error) {
            // Such as an OutOfMemoryError. Thrown on, it would end the Orchestrator's loop, and then nothing would turn
            // Kestrel off any more, while the service and the microphone run on. Off also ends a reply that did not start.
            report("An effect", e)
            send(Event.TurnOff)
        }
    }

    private fun performUnguarded(effect: Effect) {
        when (effect) {
            is Effect.Say -> say(effect.reply)
            Effect.StopSaying -> session?.stopSpeaking()
            // Sub-project 5 runs tasks. Until then every task ends at once, or Kestrel would wait for it for ever.
            is Effect.RunTask -> send(Event.TaskEnded(effect.task, ListeningTexts.NO_TASKS))
            // Nothing runs that could be cancelled yet, and the instant commands come with sub-project 3.
            Effect.CancelTask, is Effect.RunCommand -> Unit
            is Effect.Show -> bar.show(text(effect.feedback))
            // The Orchestrator runs the wait itself.
            is Effect.WaitForTask -> Unit
            Effect.StopService -> shutdown()
            Effect.OpenSettings -> openSettingsOrSayWhy()
        }
    }

    // Section 14: Kestrel never fails silently. Android may also block an activity start from the background without an
    // exception; Kestrel's accessibility service gives it the right to start one (prototype P1).
    private fun openSettingsOrSayWhy() {
        try {
            openSettings()
        } catch (e: Exception) {
            report("Opening the settings", e)
            bar.show(ListeningTexts.CANNOT_OPEN_SETTINGS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun say(reply: Reply) {
        val text = when (reply) {
            Reply.Yes -> ListeningTexts.YES
            is Reply.Text -> reply.text
        }
        val current = session
        if (current == null) {
            // Section 14.6: without a voice the text goes to the bar. Thrown before the reply starts, so that the
            // Orchestrator ends the reply in its place.
            bar.show(text)
            error("No voice session")
        }
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                // Section 14.6: when the voice fails, Kestrel shows the text instead.
                bar.show(text)
            } else if (error == null && spoken.getCompleted() == Spoke.REFUSED) {
                bar.show(text)
            }
            send(Event.SpeechEnded)
        }
    }

    private fun text(feedback: Feedback): String = when (feedback) {
        is Feedback.ModeChanged -> when (feedback.mode) {
            Mode.LISTENING -> ListeningTexts.LISTENING
            Mode.PAUSED -> ListeningTexts.PAUSED
            Mode.OFF -> (offReason ?: ListeningTexts.OFF).also { offReason = null }
        }
        is Feedback.Heard -> feedback.text
        Feedback.MicBusy -> ListeningTexts.MIC_BUSY
    }

    /**
     * Where one session reports. It goes quiet when the session starts to close. An event that a session thread sends
     * just as Kestrel turns off can still slip through, within the width of an instruction, and act on the next state;
     * it still comes before the next session opens, because close() waits for calls that are on their way.
     */
    private inner class Listener : SessionListener {
        @Volatile
        var active = true

        override fun send(event: Event) {
            if (active) orchestrator.send(event)
        }

        override fun microphoneFailed(error: Exception) {
            // The microphone's own thread cannot stop the microphone, so the controller's thread does.
            scope.launch(thread) {
                val current = session
                if (!active || current == null) return@launch
                if (microphoneOn) {
                    microphoneOn = false
                    releaseMicrophone(current)
                }
                turnOff("The microphone", error, ListeningTexts.MICROPHONE_FAILED)
            }
        }

        override fun transcriptionFailed(error: Exception) {
            if (active) report("A transcription", error)
        }
    }
}
