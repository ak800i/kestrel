package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). They query and act on
 * nodes with blocking binder calls on the caller's dispatcher, so callers run them on `Dispatchers.IO`.
 */
class ScreenActions internal constructor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     *
     * A key names a position, not an identity. After a list has added, removed or scrolled rows, a key from an older
     * snapshot can name a look-alike element in another row, so the caller must first check that the element is still
     * the one it saw (design spec, section 9.2).
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gestureAt(ScreenPoint(x, y), Gestures::tap)

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gestureAt(ScreenPoint(x, y), Gestures::longPress)

    /**
     * Replaces the text of the field with [key] by [text]. Kestrel uses the set-text action and reads the text
     * back. If the app refuses the action or the field does not show the text, Kestrel types through its
     * accessibility input method instead, and from then on does so for the whole app (design spec, section 9.7).
     * Kestrel does not type into a disabled field, and a field that has gone does not count as a refusal.
     */
    suspend fun setText(key: String, text: String): ActionResult = setText(key, text, retry = true)

    private suspend fun setText(key: String, text: String, retry: Boolean): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!element.editable) return ActionResult.Failed("The element is not a text field")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
                return settled(ActionMethod.ACTION)
            }
            if (!node.refresh()) {
                // The field has gone, so the app refused nothing: look for it again once (design spec, section 14.3).
                if (!retry) return GONE
                snapshots.invalidate()
                return setText(key, text, retry = false)
            }
            if (!node.isEnabled) return DISABLED
            if (app != null) inputMethodApps += app
        }
        return typeWithInputMethod(element, node, text)
    }

    /**
     * Scrolls the element with [key] or, when [key] is null, a list in the top app window that has one. Kestrel uses
     * the scroll action of the element or of its nearest parent that can scroll in [direction]. Otherwise it swipes
     * across the nearest list that moves along that axis. A sideways swipe across a row can archive a message or
     * start a call, so Kestrel swipes sideways only across a list that moves sideways. Up and down, it swipes across
     * the window when no list moves that way (design spec, section 9.3).
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.performAction(action)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windowBounds(element?.windowId)
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window) else list)
            ?: window?.takeIf { direction.isVertical }
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
        val (from, to) = SwipePaths.forScroll(area, direction)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult {
        val from = ScreenPoint(fromX, fromY)
        val to = ScreenPoint(toX, toY)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    /** Presses and holds at the first point, then moves to the second point, as when moving an icon. */
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult {
        val from = ScreenPoint(fromX, fromY)
        val to = ScreenPoint(toX, toY)
        // Half the touch slop, so that the press still counts as a press while it drifts.
        val drift = maxOf(1, ViewConfiguration.get(service).scaledTouchSlop / 2)
        return gesture(listOf(from, ScreenPoint(fromX + drift, fromY), to)) { Gestures.drag(from, to, drift) }
    }

    /** Moves two fingers apart (when [endSpan] is larger than [startSpan]) or together around a point. */
    suspend fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int): ActionResult {
        val fingers = listOf(startSpan, endSpan).flatMap { span ->
            listOf(ScreenPoint(centerX - span / 2, centerY), ScreenPoint(centerX + span / 2, centerY))
        }
        return gesture(fingers) { arrayOf(Gestures.pinch(ScreenPoint(centerX, centerY), startSpan, endSpan)) }
    }

    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
    val zoomScale: Float get() = service.magnificationController.magnificationConfig?.scale ?: 1f

    /** Magnifies the whole screen [scale] times around a point, with Android's magnification (design spec, section 9.3). */
    suspend fun zoom(scale: Float, centerX: Int, centerY: Int): ActionResult {
        val config = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(scale)
            .setCenterX(centerX.toFloat())
            .setCenterY(centerY.toFloat())
            .build()
        val done = withContext(Dispatchers.Main) { service.magnificationController.setMagnificationConfig(config, false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to magnify the screen")
    }

    suspend fun resetZoom(): ActionResult {
        val done = withContext(Dispatchers.Main) { service.magnificationController.resetCurrentMagnification(false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to reset the magnification")
    }

    /**
     * Magnification moves every element on the screen, and Android sends no accessibility event for it, so Kestrel
     * reports the change to the tracker itself: snapshots are built anew, and the numbers and labels follow the
     * elements. It reports it again after the wait, because a snapshot built during the wait may still show the old
     * positions.
     */
    private suspend fun magnified(): ActionResult {
        tracker.onScreenChanged()
        val result = settled(ActionMethod.MAGNIFICATION)
        tracker.onScreenChanged()
        return result
    }

    suspend fun global(action: GlobalAction): ActionResult {
        return if (service.performGlobalAction(action.id)) {
            settled(ActionMethod.GLOBAL_ACTION)
        } else {
            ActionResult.Failed("Android refused the action ${action.name}")
        }
    }

    /** Opens the app with [packageName] as its launcher icon would. */
    suspend fun openApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("No app with the package $packageName can be opened")
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return settled(ActionMethod.INTENT)
    }

    /** Opens a link or a deep link in the app that handles it. */
    suspend fun openUri(uri: String): ActionResult {
        return try {
            service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app can open the link")
        }
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            return gestureAt(ScreenPoint(bounds.centerX, bounds.centerY), fallback)
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gestureAt(ScreenPoint(element.bounds.centerX, element.bounds.centerY), fallback)
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    val started = inputMethod.currentInputStarted && editor?.packageName == element.packageName &&
                        (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus")
        withContext(Dispatchers.Main) {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1, null)
        }
        return if (node.shows(text, element.password)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            ActionResult.Failed("The text field did not take the text")
        }
    }

    private suspend fun gestureAt(point: ScreenPoint, build: (Int, Int) -> GestureDescription): ActionResult =
        gesture(listOf(point)) { arrayOf(build(point.x, point.y)) }

    /**
     * Runs the gesture that [build] makes, after checking that its [points] lie on the screen: Android's gesture builder
     * throws for a negative point, and a gesture beyond the screen's edge reaches nothing.
     */
    private suspend fun gesture(points: List<ScreenPoint>, build: () -> Array<GestureDescription>): ActionResult {
        val screen = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (points.any { it.x < screen.left || it.x >= screen.right || it.y < screen.top || it.y >= screen.bottom }) {
            return OFF_SCREEN
        }
        return when (gestures.perform(*build())) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
            GestureOutcome.TIMED_OUT -> ActionResult.Failed("Android did not report the end of the gesture")
        }
    }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    /**
     * Whether the field shows [expected] after a short wait. A password field shows only dots, so for it the
     * action's own result has to do.
     */
    private suspend fun AccessibilityNodeInfo.shows(expected: String, password: Boolean): Boolean {
        if (password) return true
        repeat(READ_BACK_TRIES) {
            if (refresh() && shownText().orEmpty() == expected) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /** This node or its nearest parent, up to [MAX_PARENT_DEPTH] levels up, that [matches]. */
    private fun AccessibilityNodeInfo.selfOrParent(matches: (AccessibilityNodeInfo) -> Boolean): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (matches(current)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * The element's node or its nearest parent that handles touches. A finger's touch stops at that view even when it
     * is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? =
        selfOrParent { it.isClickable || it.isLongClickable || it.offers(action) }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** The first action that scrolls this node in [direction], if the node offers one and is enabled. */
    private fun AccessibilityNodeInfo.scrollAction(direction: ScrollDirection): Int? {
        if (!isEnabled) return null
        val offered = offeredActions()
        return ScrollActions.forDirection(direction, scrollAxis(offered)).firstOrNull { it in offered }
    }

    /** Whether a swipe in [direction] may cross this list (see [ScrollActions.movesAlong]). */
    private fun AccessibilityNodeInfo.movesAlong(direction: ScrollDirection): Boolean =
        ScrollActions.movesAlong(scrollAxis(offeredActions()), direction)

    private fun AccessibilityNodeInfo.offeredActions(): Set<Int> = actionList.mapTo(HashSet()) { it.id }

    private fun AccessibilityNodeInfo.scrollAxis(offered: Set<Int>): ScrollAxis =
        ScrollActions.axis(offered, className?.toString(), collectionInfo?.rowCount, collectionInfo?.columnCount)

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * The list to scroll when the owner names none: in the top app window that has a list, the largest list that can
     * scroll in [direction], or else the largest that moves along that axis, or else the largest list. The snapshot
     * lists its windows from the top down.
     */
    private fun LiveSnapshot.scrollableFor(direction: ScrollDirection): ScreenElement? {
        for (window in snapshot.windows.filter { it.kind == WindowKind.APPLICATION }) {
            val lists = snapshot.elements.filter { it.scrollable && it.enabled && it.windowId == window.id }
            if (lists.isEmpty()) continue
            val canScroll = lists.filter { node(it.key)?.scrollAction(direction) != null }
            val alongTheAxis = lists.filter { node(it.key)?.movesAlong(direction) == true }
            return canScroll.ifEmpty { alongTheAxis }.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        }
        return null
    }

    /** The bounds of the window with [windowId], or of the top app window when there is no such window. */
    private fun ScreenSnapshot.windowBounds(windowId: Int?): IntRect? =
        (windows.firstOrNull { it.id == windowId } ?: windows.firstOrNull { it.kind == WindowKind.APPLICATION })?.bounds

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
        val GONE = ActionResult.Failed("The element is no longer on the screen")
        val OFF_SCREEN = ActionResult.Failed("The point is outside the screen")
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
