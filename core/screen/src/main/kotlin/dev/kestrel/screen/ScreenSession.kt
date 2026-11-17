package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.act.SystemControls
import dev.kestrel.screen.act.TextEditor
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
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    private val gridOverlay = GridOverlay(overlays)
    private val feedback = FeedbackBar(overlays)
    private val card = ConfirmationCard(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)
    private val watcher = TouchWatcher(overlays, gestures)

    private val builds = AtomicInteger()
    private val buildMillis = AtomicLong()

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) {
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

    val actions = ScreenActions(service, snapshots, gestures, tracker)

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
     * session is open and Kestrel listens. Any thread may set it.
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
        whichOneShownOn = null
        whichOne.value = false
        numbersMode = mode
        numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    /**
     * Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes, and
     * then the numbers of "show numbers" come back if it is on.
     */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
        whichOne.value = true
    }

    /**
     * Removes the "Which one?" numbers of a command that has ended: the numbers of "show numbers" come back when it is
     * on, and otherwise the numbers go. Nothing changes when no "Which one?" numbers show.
     */
    suspend fun endWhichOne() = withContext(Dispatchers.Main) {
        if (whichOneShownOn == null) return@withContext
        whichOneShownOn = null
        whichOne.value = false
        val mode = numbersMode
        if (mode == null) numbers.hide() else numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        whichOne.value = false
        numbers.hide()
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
        gridOverlay.show(grid ?: Grid.over(overlays.screenBounds()))
    }

    suspend fun hideGrid() = withContext(Dispatchers.Main) { gridOverlay.hide() }

    /**
     * Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). With [keep], the text
     * stays until [releaseFeedback] or the next text, as an instant command's question does.
     */
    suspend fun showFeedback(text: String, keep: Boolean = false) = withContext(Dispatchers.Main) { feedback.show(text, keep) }

    /** A kept text on the feedback bar fades from now on. */
    suspend fun releaseFeedback() = withContext(Dispatchers.Main) { feedback.release() }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. It returns false when the
     * owner taps Cancel, when a newer question replaces this one or when the session closes, and the card then goes at
     * once. A caller that also listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call, which
     * removes the card too (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) { card.ask(question) }

    /** Starts reporting the owner's touches in [ownerTouches], for example while a task runs. */
    suspend fun startTouchWatcher() = withContext(Dispatchers.Main) { watcher.start() }

    suspend fun stopTouchWatcher() = withContext(Dispatchers.Main) { watcher.stop() }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
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
            if (numbersMode == null) numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
