package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.OwnActions
import dev.kestrel.screen.act.OwnerSignal
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.act.SystemControls
import dev.kestrel.screen.act.TextEditor
import dev.kestrel.screen.act.Touch
import dev.kestrel.screen.act.TouchBranch
import dev.kestrel.screen.act.TouchSource
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.ConfirmationCard
import dev.kestrel.screen.overlay.FeedbackBar
import dev.kestrel.screen.overlay.Grid
import dev.kestrel.screen.overlay.GridOverlay
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.overlay.TouchWatcher
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.PlaceCheck
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.WindowEvents
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kestrel's screen layer while its accessibility service is connected (design spec, section 9). [errors] handles the
 * failures of the session's background work, and it may write only class names and stack frames (design spec, section
 * 13.5).
 */
class ScreenSession(service: AccessibilityService, private val errors: CoroutineExceptionHandler) {
    private val reader = ScreenReader(service)
    private val windowEvents = WindowEvents(service.packageName, OverlayHost.ROOT_CLASS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    private val gridOverlay = GridOverlay(overlays)
    private val feedback = FeedbackBar(overlays)
    private val card = ConfirmationCard(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)
    /** Kestrel's own actions, which the touch watcher tells from the owner's finger (design D19 of sub-project 5). */
    private val ownActions = OwnActions(SystemClock::uptimeMillis, Build.VERSION.SDK_INT)
    private val watcher = TouchWatcher(overlays, gestures, ownActions)

    private val builds = AtomicInteger()
    private val buildMillis = AtomicLong()

    /**
     * The current snapshot. A new one is built only after the screen has changed, and after an event of another app's
     * window only once the window has stopped moving (design spec, section 9.2).
     */
    val snapshots = SnapshotCache(
        tracker,
        object : PlaceCheck<LiveSnapshot> {
            override suspend fun inPlace(value: LiveSnapshot, windows: Set<Int>) =
                withContext(Dispatchers.IO) { reader.inPlace(value, windows) }

            override fun forget() = reader.forget()
        },
    ) {
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { reader.read() }.also {
            builds.incrementAndGet()
            buildMillis.addAndGet(SystemClock.elapsedRealtime() - started)
        }
    }

    /**
     * How many snapshots this session has built, and how long the builds took in all, so that the A71 session can
     * measure what [keepFresh] costs (design spec, section 8.3).
     */
    val snapshotBuilds: Int get() = builds.get()
    val snapshotMillis: Long get() = buildMillis.get()

    val actions = ScreenActions(service, snapshots, gestures, tracker, ownActions) { cardCover() }

    /** Edits text fields through Kestrel's input method (design spec, section 9.7). */
    val text = TextEditor(service, snapshots, tracker)

    /** Starts activities and system actions, switches apps and pans the magnification (design spec, section 9.3). */
    val system = SystemControls(service, snapshots, tracker)

    private val whichOne = MutableStateFlow(false)

    /** Whether "Which one?" numbers show. They go when the screen content changes. */
    val whichOneShown: StateFlow<Boolean> = whichOne.asStateFlow()

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. "Which one?" leaves it set. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    @Volatile
    private var numbersChanged = 0L

    /**
     * When the numbers on the screen or the grid's squares last changed, in `SystemClock.uptimeMillis()`, or 0 when they
     * never did. A number that the owner began to say before then may name another element (design spec, section 9.4).
     */
    val numbersChangedAt: Long get() = numbersChanged

    val labelsShowing: Boolean get() = labels.isShowing

    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid

    /** The whole screen in pixels, for the swipes and pinches that the owner names (design spec, section 9.3). */
    val screenBounds: IntRect get() = overlays.screenBounds()

    /** The feedback bar's text now, or null while the bar is hidden. */
    val feedbackText: String? get() = feedback.text

    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches

    /**
     * While true, the session builds a snapshot after each settled change of the screen, so that `snapshots.latest()`
     * stays current for the boost words of each utterance (design spec, section 8.3). AppGraph sets it while a voice
     * session is open and Kestrel listens with the screen on and the phone unlocked. Any thread may set it.
     */
    @Volatile
    var keepFresh: Boolean = false
        set(value) {
            val starts = value && !field
            field = value
            // The newest snapshot may be old by now, so the first one is built at once.
            if (starts) scope.launch { snapshots.current() }
        }

    init {
        scope.launch {
            // collect, not collectLatest: on a screen that changes all the time, such as a stopwatch, collectLatest would
            // cancel every check before it ran. The version is a state, so the check after a slow one sees the latest
            // version, and the snapshot cache waits until the screen is quiet, but never longer than 3 s.
            tracker.version.collect {
                if (!keepFresh && numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collect
                // A failed check must not end the watcher, or the numbers and labels would stop following the screen.
                // The next screen change runs the next check.
                try {
                    onScreenSettled(snapshots.current().snapshot)
                } catch (e: CancellationException) {
                    // Only the watcher's own cancellation ends it. A check that something inside it cancelled, such as
                    // a timeout, failed like any other check.
                    currentCoroutineContext().ensureActive()
                    errors.handleException(currentCoroutineContext(), e)
                } catch (e: Exception) {
                    errors.handleException(currentCoroutineContext(), e)
                }
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        forgetAgentBadges()
        whichOneShownOn = null
        whichOne.value = false
        numbersMode = mode
        val snapshot = snapshots.current().snapshot
        changing { numbers.show(Numbering.forScreen(snapshot, mode)) }
    }

    /**
     * Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes, and
     * then the numbers of "show numbers" come back if it is on.
     */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        forgetAgentBadges()
        val snapshot = snapshots.current().snapshot
        whichOneShownOn = contentOf(snapshot)
        changing { numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys })) }
        whichOne.value = true
    }

    /**
     * Removes the "Which one?" numbers of a command that has ended: the numbers of "show numbers" come back when it is
     * on, and otherwise the numbers go. Nothing changes when no "Which one?" numbers show. The numbers come back from the
     * newest snapshot, without waiting for a quiet screen: the screen watcher, which runs while numbers show, numbers the
     * screen again after its next change.
     */
    suspend fun endWhichOne() = withContext(Dispatchers.Main) {
        if (whichOneShownOn == null) return@withContext
        whichOneShownOn = null
        whichOne.value = false
        val mode = numbersMode
        if (mode == null) {
            changing { numbers.hide() }
        } else {
            val snapshot = (snapshots.latest() ?: snapshots.current()).snapshot
            changing { numbers.show(Numbering.forScreen(snapshot, mode)) }
        }
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        forgetAgentBadges()
        numbersMode = null
        whichOneShownOn = null
        whichOne.value = false
        changing { numbers.hide() }
    }

    /** Shows names next to the actionable elements until the screen content changes (design spec, section 9.4). */
    suspend fun showLabels() = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        labelsShownOn = contentOf(snapshot)
        labels.show(snapshot.elements.filter { it.isActionable })
    }

    suspend fun hideLabels() = withContext(Dispatchers.Main) {
        labelsShownOn = null
        labels.hide()
    }

    /** Shows [grid], by default one over the whole screen. "More squares" shows `grid.more()` (design spec, section 9.4). */
    suspend fun showGrid(grid: Grid? = null) = withContext(Dispatchers.Main) {
        changing { gridOverlay.show(grid ?: Grid.over(overlays.screenBounds())) }
    }

    suspend fun hideGrid() = withContext(Dispatchers.Main) { changing { gridOverlay.hide() } }

    /**
     * Ends "show numbers" and the "Which one?" numbers, and hides the labels and the grid, as when Kestrel stops listening
     * (design spec, section 9.4). The feedback bar stays.
     */
    suspend fun hideOverlays() = withContext(Dispatchers.Main) {
        forgetAgentBadges()
        numbersMode = null
        whichOneShownOn = null
        whichOne.value = false
        labelsShownOn = null
        labels.hide()
        changing {
            numbers.hide()
            gridOverlay.hide()
        }
    }

    /**
     * Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). With [keep], the text
     * stays until [releaseFeedback] or the next text, as an instant command's question does.
     */
    suspend fun showFeedback(text: String, keep: Boolean = false) = withContext(Dispatchers.Main) { feedback.show(text, keep) }

    /** A kept text on the feedback bar fades from now on. */
    suspend fun releaseFeedback() = withContext(Dispatchers.Main) { feedback.release() }

    /**
     * Hides the feedback bar at once, also a kept text, as before a screenshot (design spec, Appendix A); the numbers, the
     * labels and the grid stay.
     */
    suspend fun hideFeedback() = withContext(Dispatchers.Main) { feedback.hide() }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. It returns false when the
     * owner taps Cancel, when a newer question replaces this one or when the session closes, and the card then goes at
     * once. A caller that also listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call, which
     * removes the card too (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) {
        // An action that Kestrel made before the question can never hide the owner's touch (design D19 of sub-project 5).
        ownActions.clear()
        card.ask(question)
    }

    /**
     * Starts reporting the owner's touches in [ownerTouches], for example while a task runs, and returns whether the
     * watcher's window is on the screen; a task does not run without it (design D19 of sub-project 5). Starting it
     * forgets Kestrel's earlier actions.
     */
    suspend fun startTouchWatcher(): Boolean = withContext(Dispatchers.Main) { watcher.start() }

    suspend fun stopTouchWatcher() = withContext(Dispatchers.Main) { watcher.stop() }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.isNotification(event.eventType)) return
        val window = windowEvents.movesWindow(event.eventType, event.packageName, event.className, event.windowId, event.windowChanges)
        if (window || ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged(if (window) event.windowId else null)
        signalOwner(event)
    }

    /**
     * Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects.
     * It may be called more than once.
     */
    fun close() {
        scope.cancel()
        card.dismiss()
        // Each overlay's own hide() also clears its state, such as the numbers or the feedback bar's pending fade.
        numbers.hide()
        labels.hide()
        gridOverlay.hide()
        feedback.hide()
        watcher.stop()
        overlays.close()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null) {
            if (content == whichOneShownOn) return
            // The candidates belong to the screen on which "Which one?" was asked.
            whichOneShownOn = null
            whichOne.value = false
            if (numbersMode == null) changing { numbers.hide() }
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) changing { numbers.show(badges) }
        }
    }

    /**
     * Runs [change] on the main thread, and notes the time when it changed which element a number names or the grid. A
     * badge that only moved does not count.
     */
    private inline fun changing(change: () -> Unit) {
        val badges = numbers.badges
        val grid = gridOverlay.grid
        change()
        if (!Numbering.sameNumbers(numbers.badges, badges) || gridOverlay.grid != grid) numbersChanged = SystemClock.uptimeMillis()
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }

    // sub-project 5B

    /**
     * How the touch watcher tells an echo of Kestrel's own actions from the owner's finger on this device (design D19 of
     * sub-project 5): the rule, and branch B, on which a click or a text change that Kestrel did not cause also counts as
     * the owner's touch. Any thread may set it; the debug hook `agent-touch-source` stores it per device.
     */
    var touchSource: TouchSource
        get() = ownActions.source
        set(value) {
            ownActions.source = value
        }

    /** Every touch that the watcher classified, Kestrel's own and the owner's, for D19's probe and the activity log. */
    val touches: SharedFlow<Touch> get() = watcher.all

    /** Forgets Kestrel's earlier actions, so that none of them hides the owner's touch that answers a question (D19). */
    fun forgetOwnActions() = ownActions.clear()

    /**
     * Whether the confirmation card is on the screen (design D8 and D32 item 20 of sub-project 5): `AppScreenPort` waits
     * until it is false before it reads the snapshot of a confirmed action. It stays true for one settle after the card
     * ended, while Android may still deliver a touch to the card's window ([dev.kestrel.screen.overlay.CardPresence]).
     */
    val cardShowing: Boolean get() = card.isShowing

    // The card's bounds while it shows, or the whole screen while Android has not laid it out yet.
    private fun cardCover(): IntRect? = if (card.isShowing) card.bounds ?: overlays.screenBounds() else null

    private val ownPackage: String = service.packageName

    /** Branch B of D19: a click or a text change in another app's window that Kestrel did not cause is the owner's touch. */
    private fun signalOwner(event: AccessibilityEvent) {
        if (ownActions.source.branch != TouchBranch.B || !watcher.running) return
        val focused = { event.source?.isFocused == true }
        if (OwnerSignal.counts(event.eventType, event.packageName?.toString(), ownPackage, focused, ownActions.busy())) {
            watcher.report(OwnActions.signal())
        }
    }

    // sub-project 5C

    /** Screenshots of the app in front for the agent's look_closer (design D20 of sub-project 5). */
    val capture = dev.kestrel.screen.capture.ScreenCapture(service) { hideAllForCapture() }

    // The agent's numbers of look_closer's third step, and the mode of "show numbers" that they replaced.
    private var agentBadges = false
    private var modeBeforeBadges: NumbersMode? = null

    /**
     * Makes Kestrel's overlays transparent for a screenshot of the whole display and returns the handle that shows them
     * again, or null when Android did not draw them hidden within 500 ms (design D20 of sub-project 5). Call it on the
     * main thread, as its only caller [dev.kestrel.screen.capture.ScreenCapture.capture] does: a switch of dispatcher here
     * would run the hide in a scope of its own, and a cancellation that came as that scope ended would drop the handle
     * and leave every overlay shown later transparent.
     */
    suspend fun hideAllForCapture(): dev.kestrel.screen.overlay.HiddenOverlays? {
        check(Looper.getMainLooper().isCurrentThread) { "hideAllForCapture runs on the main thread" }
        return dev.kestrel.screen.overlay.HiddenOverlays.hide(overlays)
    }

    /**
     * Shows the agent's [badges] instead of Numbering's own until [hideBadges] (design D20 of sub-project 5); "show
     * numbers" pauses meanwhile and comes back afterwards, unless the owner's numbers changed meanwhile or Kestrel stopped
     * listening.
     */
    suspend fun showBadges(badges: List<Badge>) = withContext(Dispatchers.Main) {
        if (!agentBadges) {
            agentBadges = true
            modeBeforeBadges = numbersMode
        }
        numbersMode = null
        whichOneShownOn = null
        whichOne.value = false
        changing { numbers.show(badges) }
    }

    // The owner's or Listening's newer choice of numbers replaces the agent's numbers, so hideBadges must not undo it.
    private fun forgetAgentBadges() {
        agentBadges = false
        modeBeforeBadges = null
    }

    /** Removes the agent's numbers; the numbers of "show numbers" come back when it was on. Nothing else changes. */
    suspend fun hideBadges() = withContext(Dispatchers.Main) {
        if (!agentBadges) return@withContext
        agentBadges = false
        val mode = modeBeforeBadges
        modeBeforeBadges = null
        if (mode == null) changing { numbers.hide() } else showNumbers(mode)
    }
}
