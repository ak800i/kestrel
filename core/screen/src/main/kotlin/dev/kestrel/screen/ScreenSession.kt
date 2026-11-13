package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    val labelsShowing: Boolean get() = labels.isShowing

    init {
        scope.launch {
            tracker.version.collectLatest {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collectLatest
                tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
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

    /** Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes. */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        numbersMode = null
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

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /** Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects. */
    fun close() {
        scope.cancel()
        overlays.hideAll()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null && content != whichOneShownOn) {
            whichOneShownOn = null
            numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
