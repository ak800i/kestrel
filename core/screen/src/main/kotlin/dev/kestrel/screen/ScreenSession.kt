package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kestrel's screen layer while its accessibility service is connected (design spec, section 9). [errors] handles the
 * failures of the session's background work, and it may write only class names and stack frames (design spec, section
 * 13.5).
 */
class ScreenSession(service: AccessibilityService, errors: CoroutineExceptionHandler) {
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

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

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

    /** The feedback bar's text now, or null while the bar is hidden. */
    val feedbackText: String? get() = feedback.text

    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches

    init {
        scope.launch {
            // collect, not collectLatest: on a screen that changes all the time, such as a stopwatch, collectLatest would
            // cancel every check before it ran. The version is a state, so the check after a slow one sees the latest
            // version, and the snapshot cache waits until the screen is quiet, but never longer than 3 s.
            tracker.version.collect {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collect
                onScreenSettled(snapshots.current().snapshot)
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
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
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
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

    /** Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). */
    suspend fun showFeedback(text: String) = withContext(Dispatchers.Main) { feedback.show(text) }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. A caller that also
     * listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) { card.ask(question) }

    /** Starts reporting the owner's touches in [ownerTouches], for example while a task runs. */
    suspend fun startTouchWatcher() = withContext(Dispatchers.Main) { watcher.start() }

    suspend fun stopTouchWatcher() = withContext(Dispatchers.Main) { watcher.stop() }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /** Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects. */
    fun close() {
        scope.cancel()
        card.dismiss()
        overlays.hideAll()
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
