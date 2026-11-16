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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Starts and stops ListeningService. Any thread may call it. */
internal interface ServiceControl {
    /** Asks Android to start the service in the foreground. Throws when Android does not allow the start. */
    fun start()

    /**
     * Asks the service's instance [instance] to stop itself, on the main thread a moment later, if [startId] is still
     * its newest start. [instance] and [startId] are the newest start that Listening has seen (null and 0 before the
     * first). So a stop never ends a start that Listening has not seen: a start that onStartCommand handled after
     * Listening decided to stop, such as a "Start listening" that the stop overtakes, keeps the service. Its report
     * reaches [Listening.onForeground] or [Listening.onStartAction] later, and Listening adopts the service or stops it
     * again with the newer start. Nothing happens when that instance no longer runs, because a new instance counts its
     * starts anew.
     */
    fun stop(instance: Any?, startId: Int)
}

/** Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. */
internal fun interface FeedbackBar {
    fun show(text: String)
}

/** A mode that Kestrel stored, with the wall-clock times of [ListeningSetting]; a time is null when it is not known. */
internal data class StoredMode(val mode: Mode, val writtenAt: Long? = null, val restoredAt: Long? = null)

/** Keeps Kestrel's listening mode across restarts of its process (design spec, sections 8.5 and 14.4). */
internal interface ModeMemory {
    /** The mode that Kestrel stored last, or [Mode.OFF] without times when it stored none. */
    suspend fun read(): StoredMode

    /**
     * Stores [mode], written at [writtenAt], with [restoredAt], the time at which the last restore turned Kestrel
     * on.
     */
    suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?)
}

/** What Kestrel reads about the phone to say why it stopped (design spec, section 14.6). Any thread may call it. */
internal interface ListeningConditions {
    /** Whether the owner allowed Kestrel to record audio. */
    fun microphoneAllowed(): Boolean

    /** Whether the owner set Kestrel's battery use to Restricted (`ActivityManager.isBackgroundRestricted()`). */
    fun backgroundRestricted(): Boolean

    /**
     * Whether Android took the foreground from ListeningService's instance [service]: its foreground service type is 0.
     * Android does this without an exception or a callback (prototype P2).
     */
    fun demoted(service: Any): Boolean
}

/** What Kestrel says and shows while it listens, and why it stopped (design spec, sections 8.9 and 14.6). */
internal object ListeningTexts {
    const val YES = "Yes?"
    const val NO_TASKS = "I can't run tasks yet."
    const val LISTENING = "Listening"
    const val PAUSED = "Paused"
    const val OFF = "Off"

    // Also for the privacy toggle and for Android's own silencing of an app in the background, not only for another
    // app.
    const val MIC_BUSY = "Microphone blocked"
    // Short enough for the feedback bar, which fades after 4 s.
    const val BACKGROUND_START = "Android blocked a start in the background. Start Kestrel with the tile, the shortcut " +
        "or the notification."
    const val MICROPHONE_PERMISSION = "Kestrel needs the microphone permission. Open Kestrel to allow it."
    const val MODELS_MISSING = "The speech models are missing; see the setup checklist."
    const val ENGINES_FAILED = "Kestrel could not load its speech engines."
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
    const val STOPPED_BY_ANDROID = "Android stopped Kestrel's listening."
    const val BATTERY_RESTRICTED = "Android stops Kestrel's listening in the background because its battery use is " +
        "Restricted. Set it to Unrestricted."
    const val CRASHED_AFTER_RESTORE = "Kestrel stopped soon after it came back, so it stays off."
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
    const val SETTINGS_NEED_SERVICE = "I can open my settings only while my accessibility service is on. Tap my " +
        "listening notification instead."
}

/**
 * Why Kestrel turned itself off, or could not turn on (design spec, section 14.6). [text] says it on the feedback bar
 * and in the problems notification. [restart]: a new start can help, so the notification offers "Start listening".
 * [starting]: Kestrel failed while it started, so the notification says that it could not start listening.
 */
internal enum class StopReason(val text: String, val restart: Boolean, val starting: Boolean) {
    /** Android refused to start ListeningService from the background, or to give it the microphone. */
    BACKGROUND_START(ListeningTexts.BACKGROUND_START, restart = true, starting = true),

    /** The owner has not allowed the microphone. */
    MICROPHONE_PERMISSION(ListeningTexts.MICROPHONE_PERMISSION, restart = false, starting = true),

    /** A speech model is missing or damaged. */
    MODELS_MISSING(ListeningTexts.MODELS_MISSING, restart = false, starting = true),

    /** Another failure while the voice session opened. */
    ENGINES_FAILED(ListeningTexts.ENGINES_FAILED, restart = false, starting = true),

    /** The microphone failed, also after its restart. */
    MICROPHONE_FAILED(ListeningTexts.MICROPHONE_FAILED, restart = true, starting = false),

    /** Android stopped ListeningService on its own. */
    STOPPED_BY_ANDROID(ListeningTexts.STOPPED_BY_ANDROID, restart = true, starting = false),

    /**
     * Android took the foreground from ListeningService, or stopped it, because Kestrel's battery use is Restricted.
     */
    BATTERY_RESTRICTED(ListeningTexts.BATTERY_RESTRICTED, restart = false, starting = false),

    /**
     * Kestrel crashed less than 2 minutes after a restore had turned it on, so the restore leaves it off (section 8.5).
     */
    CRASHED_AFTER_RESTORE(ListeningTexts.CRASHED_AFTER_RESTORE, restart = true, starting = false),
}

/**
 * The problems notification, which says why Kestrel stopped also while the feedback bar does not exist (design spec,
 * section 14.6). The controller's thread calls it.
 */
internal interface StopNotices {
    /** Posts [reason], in place of an earlier one. Does nothing without the notification permission. */
    fun show(reason: StopReason)

    /** Removes the notification of [show], if it is there. */
    fun clear()
}

/**
 * Kestrel's listening, one for the whole process (design spec, sections 7.2, 7.3 and 8.5). It lives in AppGraph, so that
 * the accessibility service, the notification and the tile can send it events whether or not ListeningService runs. It
 * owns the [Orchestrator] and follows its state: when the mode leaves Off, it starts ListeningService; once the service
 * runs in the foreground, it opens a voice session; while the session is open, the microphone runs exactly while
 * [State.microphone] is true, and the pipeline's recognition is [State.recognition] before the first frame and after
 * every change. [Effect.StopService] closes the session and then stops the service. A failure of the microphone, such as
 * a read error after Android's audio server restarted, or an exception from the pipeline, restarts it after a second;
 * a failure within a minute of such a restart turns Kestrel off (section 14.6). It stores the mode in [memory]:
 * listening or paused once a voice session is open, off at once. When Kestrel's accessibility service connects for the
 * first time in a process in which Kestrel has not been on, it stores Off and turns Kestrel on in the stored mode, so
 * that listening comes back after Android ended Kestrel's process or the phone restarted, while a start that ends the
 * process does not come back at every start. It stays off when the newest of [exits] says that the owner stopped
 * Kestrel, or that Kestrel crashed less than 2 minutes after the last restore. A task never comes back (sections 8.5
 * and 14.4). [clock] gives the wall-clock time in milliseconds, which the exit records also use.
 *
 * Threads: the Orchestrator's loop, its effects, the following of the state, the microphone's start and stop, and the
 * open session's events run on [thread], one at a time. A session opens and closes in a coroutine of [scope] behind
 * one lock, so that a new session opens only after a shutdown has finished: two recognisers would need about 1.4 GB.
 * Android is asked for the service at once, also while a session still closes, because a tap on the tile lets Kestrel
 * start a foreground service from the background only for a few seconds. The public functions may be called from any
 * thread. [report] gets each failure with a fixed description of what failed, and
 * must log no message. When Kestrel turns itself off, the bar shows a [StopReason] in place of "Off" (section 14.6),
 * and [notices] posts it as a notification, which goes once a voice session opens again; [conditions] tells the causes
 * apart.
 * [openSettings] opens Kestrel's settings app and returns true, or returns false while Kestrel's accessibility service
 * is off, because Android then blocks the start without an exception and Kestrel says why instead; the controller's
 * thread calls it, and it throws when Android refuses.
 * [memory] is read in a coroutine on [thread] and written by one coroutine of [scope], so the newest mode is written last,
 * and by the restore, which writes Off before anything else can write.
 */
class Listening internal constructor(
    private val scope: CoroutineScope,
    private val thread: CoroutineDispatcher,
    private val sessions: VoiceSessions,
    private val services: ServiceControl,
    private val bar: FeedbackBar,
    private val report: (String, Throwable) -> Unit,
    private val openSettings: () -> Boolean,
    private val memory: ModeMemory,
    private val conditions: ListeningConditions,
    private val notices: StopNotices,
    private val exits: ExitRecords,
    private val clock: () -> Long,
    microphones: () -> Microphone,
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }

    /** What the restore does: start in the stored mode, stay off, or stay off and say that Kestrel crashed. */
    private enum class Restore { START, STAY_OFF, CRASHED }

    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones

    // Only [thread] uses these. [requested]: the service was asked to start since the last stop; start(),
    // onStartAction() and shutdown() change it together with [serviceState], so it always equals
    // serviceState != STOPPED, and foreground() relies on that. [running]: the service's instance that runs in the
    // foreground and that Kestrel has not asked to stop.
    private var serviceState = ServiceState.STOPPED
    private var requested = false
    private var running: Any? = null

    // Android was asked to start the service, and the service has not reported back yet. Stopping such a service makes
    // Android end Kestrel's process.
    private var awaitingForeground = false

    // Only [thread] uses these: the newest start that ListeningService reported, by its instance and its start ID. A stop
    // names it, so that a newer start that has not reached [thread] yet keeps the service.
    private var reported: Any? = null
    private var reportedStartId = 0

    // Only [thread] uses these. [microphoneWait]: the microphone failed, and starts again when this wait ends; until then
    // follow() leaves it off. [restartedLately]: runs for a minute after such a restart (section 14.6).
    private var microphoneWait: Job? = null
    private var restartedLately: Job? = null

    // Why Kestrel turned itself off. The next "Off" on the bar shows it instead (section 14.6).
    private var offReason: String? = null
    private var session: Session? = null
    private var listener: Listener? = null
    private var microphoneOn = false
    private val lifecycle = Mutex()
    private val sessionState = MutableStateFlow(false)

    // Only [thread] uses these. [lastMode]: the mode that was last noted for storing, null before the first change of
    // mode in this process. [restoreChecked]: the accessibility service has connected in this process, so the stored mode
    // was considered. [wasOn]: Kestrel has been on in this process, so the owner has already chosen a mode.
    private var lastMode: Mode? = null
    private var restoreChecked = false
    private var wasOn = false

    // When the restore of this process turned Kestrel on, until Kestrel next turns off; null otherwise. Written on
    // [thread], and stored with every mode by the mode's writer, so that a crash in the restored process finds it.
    @Volatile
    private var restoredAt: Long? = null

    // The newest mode to store. It stays null until the mode first changes in this process, so that the Off that every
    // process starts with never replaces the stored mode before the restore has read it.
    private val modeToStore = MutableStateFlow<Mode?>(null)

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
        // One writer, so that the newest mode is written last. It may skip a mode that a newer one replaced at once.
        scope.launch {
            modeToStore.filterNotNull().collect { mode ->
                try {
                    memory.write(mode, clock(), restoredAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Storing the listening mode", e)
                } catch (e: Error) {
                    // Such as an OutOfMemoryError. Thrown on, it would end this collector, and no later mode, not even
                    // an Off that the owner chooses, would be stored in this process.
                    report("Storing the listening mode", e)
                }
            }
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)

    /**
     * Kestrel's accessibility service has connected, which also happens after Android ended Kestrel's process or the phone
     * restarted. The first time in a process, if Kestrel has not been on in it, Kestrel turns on in the mode that it
     * stored, unless the newest exit record says that the owner stopped Kestrel, or that Kestrel crashed soon after the
     * last restore (section 8.5); Android lets it start its foreground service from the background from now on
     * (prototype P1). A stored Off that carries the time of a restore means that the restored process ended before its
     * voice session opened: Kestrel stays off, and says why after a crash. Any thread may call it.
     */
    fun onAccessibilityServiceConnected() {
        scope.launch(thread) {
            if (restoreChecked) return@launch
            restoreChecked = true
            // The owner has already chosen a mode in this process, for example with the tile.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            val stored = try {
                memory.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Reading the listening mode", e)
                return@launch
            }
            // Also when the owner chose a mode while the stored one was read.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            // An Off without the time of a restore is the owner's Off, or a restore's decision to stay off.
            if (stored.mode == Mode.OFF && stored.restoredAt == null) return@launch
            val exit = try {
                exits.newest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without the record, Kestrel restores as it did before it read records.
                report("Reading the exit records", e)
                null
            }
            // Also when the owner chose a mode while the record was read.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            // An Off with the time of a restore: that restore turned Kestrel on, and its process ended before the voice
            // session opened, for example with a native crash while the models loaded. Kestrel does not start again,
            // and says why when that end was a crash soon after the restore.
            val decided = restoreAfter(stored, exit)
            val restore = if (stored.mode == Mode.OFF && decided == Restore.START) Restore.STAY_OFF else decided
            val now = clock()
            // Off first: if this start ends Kestrel's process, for example with a native crash while the models load,
            // the next start of the process finds Off and does not try again. The mode is stored again once the voice
            // session is open. Nothing else writes now, because Kestrel has not been on in this process. When Kestrel
            // stays off, Off is all that it stores, without the time of a restore.
            try {
                memory.write(Mode.OFF, now, if (restore == Restore.START) now else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Storing the listening mode", e)
                return@launch
            }
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            when (restore) {
                Restore.START -> {
                    restoredAt = now
                    send(Event.Start(paused = stored.mode == Mode.PAUSED))
                }
                Restore.STAY_OFF -> Unit
                // Section 14.6: the bar and the notification say why, and the notification offers "Start listening".
                Restore.CRASHED -> stopBecause(StopReason.CRASHED_AFTER_RESTORE, notify = true)
            }
        }
    }

    /** Section 8.5: what the newest exit record [exit] says about the restore of [stored]. */
    private fun restoreAfter(stored: StoredMode, exit: ExitRecord?): Restore {
        // A record from before the stored mode was written belongs to an older process; a reboot leaves no record of
        // its own. A mode that an earlier version stored has no time, so its newest record decides.
        if (exit == null || (stored.writtenAt != null && exit.timestamp < stored.writtenAt)) return Restore.START
        return when (exit.reason) {
            ExitReason.OWNER_STOPPED -> Restore.STAY_OFF
            ExitReason.CRASH -> {
                val restored = stored.restoredAt
                if (restored != null && exit.timestamp - restored < CRASH_AFTER_RESTORE_MILLIS) Restore.CRASHED else Restore.START
            }
            ExitReason.OTHER -> Restore.START
        }
    }

    /**
     * For the debug hook's off: no restore turns Kestrel on in this process from now on. A restore either stops at its
     * next check, or it sent its Event.Start before this returned, so an Event.TurnOff sent afterwards comes after it.
     */
    internal suspend fun skipRestore() {
        withContext(thread) {
            restoreChecked = true
            // As if the owner had chosen a mode.
            wasOn = true
        }
    }

    /** ListeningService, whose instance is [instance], runs in the foreground after its start [startId]. */
    fun onForeground(instance: Any, startId: Int) {
        scope.launch(thread) { foreground(instance, startId) }
    }

    /**
     * ListeningService, whose instance is [instance], runs in the foreground after the problems notification's "Start
     * listening" (section 14.6), its start [startId]. From Off, Kestrel marks this service as asked for before it sends
     * Event.Start, so it never stops the service as one that nobody asked for. While Kestrel is on, nothing changes.
     */
    fun onStartAction(instance: Any, startId: Int) {
        scope.launch(thread) {
            if (serviceState == ServiceState.STOPPED) {
                serviceState = ServiceState.STARTING
                requested = true
                // A reason from an earlier start must never come back.
                offReason = null
            }
            // Event.Start acts only from Off, so a tap after the owner started Kestrel another way changes nothing.
            send(Event.Start())
            foreground(instance, startId)
        }
    }

    /**
     * ListeningService could not enter the foreground; [error] says why. [startAction]: the start came from the
     * problems notification's "Start listening", so the owner asked for it, also while Kestrel is off.
     */
    fun onForegroundFailed(error: Exception, startAction: Boolean = false) {
        scope.launch(thread) {
            // The service stops itself after a failed startForeground(), which Android allows.
            awaitingForeground = false
            when {
                // Section 14.6: Kestrel is off, so the bar and the notification say why the owner's start failed.
                startAction && serviceState == ServiceState.STOPPED ->
                    turnOff("Starting the listening service", error, startFailure(error), notify = true)
                // A start that nobody wants any more: Kestrel turned off before the service reported.
                serviceState == ServiceState.STOPPED -> report("Starting the listening service", error)
                // Also for the running service: a newer start, such as "Start listening", failed, and the service stops
                // itself.
                else -> turnOff("Starting the listening service", error, startFailure(error))
            }
        }
    }

    /** Android destroyed the ListeningService instance [instance], whether Kestrel asked for it or not. */
    fun onServiceDestroyed(instance: Any) {
        scope.launch(thread) { destroyed(instance) }
    }

    // Every change of mode shows the new mode, so the controller sees each change here, in order. This includes the
    // changes that the Orchestrator makes in one run, which the state's collector skips. The Off that every process
    // starts with is not a change, so it never replaces the stored mode before the restore has read it.
    private fun noteMode(mode: Mode) {
        if (mode != Mode.OFF) wasOn = true
        // A start after an Off is the owner's, so a crash after it is not one soon after a restore (section 8.5).
        if (mode == Mode.OFF) restoredAt = null
        remember(mode)
    }

    // Listening and paused count once a voice session is open, so that a start that ends Kestrel's process, such as a
    // native crash while the models load, does not come back at every start of the process. Off counts at once.
    private fun remember(mode: Mode) {
        if (mode != Mode.OFF && session == null) return
        if (mode == lastMode) return
        lastMode = mode
        modeToStore.value = mode
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
        // Not while the microphone waits to start again after a failure.
        if (state.microphone && !microphoneOn && microphoneWait == null) {
            try {
                current.startMicrophone()
                microphoneOn = true
            } catch (e: Exception) {
                // A start that failed after the microphone opened leaves it open until a stop.
                releaseMicrophone(current)
                turnOff("Starting the microphone", e, microphoneFailure())
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
        // At once, also while a shutdown still closes the session: a tap on the tile lets Kestrel start a foreground
        // service from the background only for a few seconds. The shutdown's stop sees [requested] and leaves the
        // service running, and open() waits for the close.
        requested = true
        try {
            services.start()
            awaitingForeground = true
        } catch (e: Exception) {
            turnOff("Starting the listening service", e, startFailure(e))
        }
    }

    private fun foreground(instance: Any, startId: Int) {
        awaitingForeground = false
        reported = instance
        reportedStartId = startId
        when (serviceState) {
            // The start's report. While a shutdown still closes the session, Android gives the new start to the instance
            // that runs, so the report may come from it, and the shutdown leaves it running.
            ServiceState.STARTING -> {
                serviceState = ServiceState.FOREGROUND
                running = instance
                open()
            }
            // A service that nobody wants any more, such as one whose start came after Kestrel turned off again.
            ServiceState.STOPPED -> scope.launch(thread) {
                lifecycle.withLock { if (serviceState == ServiceState.STOPPED) stopService() }
            }
            // A newer start, such as "Start listening" while Kestrel listens, reached the running service.
            ServiceState.FOREGROUND -> Unit
        }
    }

    private fun destroyed(instance: Any) {
        if (instance !== running) return
        // Android ended the service on its own, so Kestrel no longer listens, and the Orchestrator must know it.
        // Kestrel says so in place of "Off" (section 14.6).
        running = null
        // Under the battery restriction, Android stops the service a minute after it took its foreground (prototype
        // P2).
        stopBecause(if (conditions.backgroundRestricted()) StopReason.BATTERY_RESTRICTED else StopReason.STOPPED_BY_ANDROID)
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
                    turnOff("Opening the voice session", e, StopReason.MODELS_MISSING)
                    return@withLock
                } catch (e: Exception) {
                    turnOff("Opening the voice session", e, StopReason.ENGINES_FAILED)
                    return@withLock
                } catch (e: Error) {
                    // Kestrel must not look as if it listened, and says why, as for a native library that does not
                    // load. AppGraph's handler still logs the error's classes.
                    stopBecause(StopReason.ENGINES_FAILED)
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
                // Kestrel listens again, so the notification that said why it stopped goes (section 14.6). Before the
                // microphone starts: a microphone that cannot start posts its own reason, which must stay.
                try {
                    notices.clear()
                } catch (e: Exception) {
                    report("Removing the problems notification", e)
                }
                followSafely(state.value)
                // After followSafely, which starts the microphone, so that a native crash there comes before Listening is
                // stored.
                remember(state.value.mode)
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
        // A restart of the microphone that waits, and the minute after a restart, belong to the closing session.
        microphoneWait?.cancel()
        microphoneWait = null
        restartedLately?.cancel()
        restartedLately = null
        scope.launch(thread) {
            lifecycle.withLock {
                try {
                    // The session first, so that Kestrel plays audio only while the service runs (B5).
                    if (closing != null) closeQuietly(closing)
                } finally {
                    // Also after an Error from close(), so that the service does not run on while the mode is Off.
                    // Decided here, under the lock and after the close: a start may have asked Android for the service
                    // while the session closed, and then the service keeps running. A service that has not called
                    // startForeground() yet reports to foreground(), which stops it then.
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
            services.stop(reported, reportedStartId)
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

    /**
     * Section 14.6: a failure of the microphone, such as ERROR_DEAD_OBJECT after Android's audio server restarted, or an
     * exception that the pipeline threw from a frame, need not end listening. The microphone, which the caller has
     * stopped, starts again after [MICROPHONE_RESTART_MILLIS] if the state still wants it then; a turn-off or a new
     * session cancels the wait in shutdown(). A restart that fails, or a failure within [MICROPHONE_FAILURES_MILLIS] of
     * a restart, turns Kestrel off. The minute counts only from this restart: when the state did not want the microphone
     * as the wait ended, for example because the screen was off, the microphone starts later and no minute counts.
     */
    private fun restartMicrophone(error: Exception) {
        if (restartedLately?.isActive == true) {
            turnOff("The microphone", error, StopReason.MICROPHONE_FAILED)
            return
        }
        report("The microphone", error)
        microphoneWait?.cancel()
        microphoneWait = scope.launch(thread) {
            // Android's audio server needs a moment to come back.
            delay(MICROPHONE_RESTART_MILLIS)
            microphoneWait = null
            if (state.value.microphone) restartedLately = scope.launch(thread) { delay(MICROPHONE_FAILURES_MILLIS) }
            // Starts the microphone if the state wants it; a start that fails turns Kestrel off.
            followSafely(state.value)
        }
    }

    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, reason: StopReason, notify: Boolean = state.value.mode != Mode.OFF) {
        report(what, error)
        stopBecause(reason, notify)
    }

    /**
     * Turns Kestrel off for [reason], which the bar shows in place of "Off", or shows [reason] when Kestrel is off
     * already. While a turn-off for an earlier reason is on its way, that reason stays: the earlier failure caused this
     * one, as when Android destroys a service whose newer start failed. [offReason] is set only here, together with the
     * turn-off, and the next Off consumes it, so while Kestrel is on it tells that a turn-off is on its way. With
     * [notify], the problems notification says it too, because the bar exists only while the accessibility service
     * runs. By default it does so only when Kestrel turns itself off, never after an Off that the owner chose.
     */
    private fun stopBecause(reason: StopReason, notify: Boolean = state.value.mode != Mode.OFF) {
        when {
            state.value.mode == Mode.OFF -> bar.show(reason.text)
            offReason != null -> return
            else -> {
                offReason = reason.text
                send(Event.TurnOff)
            }
        }
        // After the turn-off, so that a notification that cannot be posted never keeps Kestrel on.
        if (notify) {
            try {
                notices.show(reason)
            } catch (e: Exception) {
                report("Posting the problems notification", e)
            }
        }
    }

    // Android 14 and later refuse a microphone service without the permission at startForeground(). With the
    // permission, a SecurityException there means that the start came too late after a tap (prototype P2).
    private fun startFailure(error: Exception): StopReason =
        if (error is SecurityException && !conditions.microphoneAllowed()) StopReason.MICROPHONE_PERMISSION else StopReason.BACKGROUND_START

    // Android 13, as on the A71, lets the service start without the permission, and the microphone fails instead.
    private fun microphoneFailure(): StopReason =
        if (conditions.microphoneAllowed()) StopReason.MICROPHONE_FAILED else StopReason.MICROPHONE_PERMISSION

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
            is Effect.Show -> {
                (effect.feedback as? Feedback.ModeChanged)?.let { noteMode(it.mode) }
                if (effect.feedback == Feedback.MicBusy && demotedByBattery()) {
                    // Section 14.6: the microphone stays silenced, and Android stops the service a minute later. Only
                    // the first MicBusy asks: when something else already blocked the microphone, the bar says
                    // "Microphone blocked" until Android stops the service, and destroyed() then names the battery.
                    stopBecause(StopReason.BATTERY_RESTRICTED)
                } else {
                    bar.show(text(effect.feedback))
                }
            }
            // The Orchestrator runs the wait itself.
            is Effect.WaitForTask -> Unit
            Effect.StopService -> shutdown()
            Effect.OpenSettings -> openSettingsOrSayWhy()
        }
    }

    // Section 14: Kestrel never fails silently. Android blocks an activity start from the background without an
    // exception unless Kestrel's accessibility service is on (prototype P1); then the bar does not exist either, so
    // Kestrel says why.
    private fun openSettingsOrSayWhy() {
        val opened = try {
            openSettings()
        } catch (e: Exception) {
            report("Opening the settings", e)
            bar.show(ListeningTexts.CANNOT_OPEN_SETTINGS)
            return
        }
        if (!opened) send(Event.SettingsUnavailable)
    }

    /**
     * Whether Android took the foreground from the running service because Kestrel's battery use is Restricted. Without
     * Kestrel's accessibility service, Android does this about 30 s after the Quick Settings panel closes, and the
     * first sign is the silenced microphone (prototype P2).
     */
    private fun demotedByBattery(): Boolean {
        val service = running ?: return false
        return conditions.demoted(service) && conditions.backgroundRestricted()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun say(reply: Reply) {
        val text = when (reply) {
            Reply.Yes -> ListeningTexts.YES
            Reply.SettingsNeedService -> ListeningTexts.SETTINGS_NEED_SERVICE
            is Reply.Text -> reply.text
        }
        // Section 14.6: a reply that Kestrel cannot speak goes to the bar, except "Yes?", which the bar shows already
        // at the start of the wait for the task (Feedback.AwaitingTask).
        val showInstead = { if (reply != Reply.Yes) bar.show(text) }
        val current = session
        if (current == null) {
            // Thrown before the reply starts, so that the Orchestrator ends the reply in its place.
            showInstead()
            error("No voice session")
        }
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (error == null && spoken.getCompleted() == Spoke.REFUSED) {
                showInstead()
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
        Feedback.AwaitingTask -> ListeningTexts.YES
    }

    /**
     * Where one session reports. It goes quiet when the session starts to close. [send] returns at once, and the
     * Orchestrator checks [active] when the event's turn comes, on [thread], after every event that was sent before it:
     * an event that a session thread sends just as Kestrel turns off is dropped once the shutdown has run, and never acts
     * on the next state. The events keep the order in which they were sent.
     */
    private inner class Listener : SessionListener {
        // Written on [thread]; read there by the Orchestrator's check and by microphoneFailed, and on a session thread by
        // transcriptionFailed.
        @Volatile
        var active = true

        override fun send(event: Event) = orchestrator.send(event) { active }

        override fun microphoneFailed(error: Exception) {
            // The microphone's own thread cannot stop the microphone, so the controller's thread does.
            scope.launch(thread) {
                val current = session
                if (!active || current == null) return@launch
                if (microphoneOn) {
                    microphoneOn = false
                    releaseMicrophone(current)
                }
                restartMicrophone(error)
            }
        }

        override fun transcriptionFailed(error: Exception) {
            if (active) report("A transcription", error)
        }
    }

    private companion object {
        // Android's audio server needs about this long to come back after it restarted (section 14.6).
        const val MICROPHONE_RESTART_MILLIS = 1_000L

        // A failure within this time after a restart turns Kestrel off.
        const val MICROPHONE_FAILURES_MILLIS = 60_000L

        // Section 8.5: a crash within this time after a restore keeps Kestrel off, so that a start that crashes does
        // not come back after every crash.
        const val CRASH_AFTER_RESTORE_MILLIS = 120_000L
    }
}
