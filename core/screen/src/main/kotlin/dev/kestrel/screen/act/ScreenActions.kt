package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.FileUriExposedException
import android.os.Build
import android.os.Bundle
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.kestrel.screen.model.ElementAction
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
    private val own: OwnActions,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. When windows above, such as the
     * keyboard, cover the centre, it taps the point of a grid of 8 by 8 points over the element that is nearest to the
     * centre and that they leave uncovered, and it fails when they cover every point of the grid. It does not tap a
     * disabled element, because a finger's touch would stop there too.
     *
     * A key names a position, not an identity. After a list has added, removed or scrolled rows, a key from an older
     * snapshot can name a look-alike element in another row, so the caller must first check that the element is still
     * the one it saw (design spec, section 9.2). With [expected], Kestrel also checks the element of every snapshot that
     * it acts on, the fresh one too, and gives [ActionResult.CHANGED] when [expected] refuses it.
     */
    suspend fun tap(key: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult =
        press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap, expected)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult =
        press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress, expected)

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
        if (!element.editable) return ActionResult.Failed("It isn't a text field.")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.perform(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
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
     * the window when no list moves that way (design spec, section 9.3). [expected] checks the element with [key] as
     * [tap] does.
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null, expected: ((ScreenElement) -> Boolean)? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        if (key != null && element != null && expected?.invoke(element) == false) return ActionResult.CHANGED
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.perform(action, kind = OwnActions.Kind.SCROLL)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windows.firstOrNull { it.id == element?.windowId }
            ?: live.snapshot.windows.firstOrNull { it.kind == WindowKind.APPLICATION }
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window.bounds) else list)
            ?: window?.bounds?.takeIf { direction.isVertical }
            ?: return ActionResult.NOTHING_SCROLLS
        // A swipe that started on the keyboard or on a popup above the list would act on that instead, and a swipe
        // shorter than Android's touch slop would tap or long-press a row of the list.
        val shortest = SwipePaths.shortestScrollArea(ViewConfiguration.get(service).scaledTouchSlop)
        val clear = Uncovered.area(area, live.snapshot.windowsAbove(window?.id), direction.isVertical, shortest)
            ?: return ActionResult.Failed("Too little of the list is free for a swipe.")
        val (from, to) = SwipePaths.forScroll(clear, direction)
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

    /**
     * Moves the slider with [key] one step up ([increase]) or down (design spec, Appendix A): with its scroll-forward or
     * scroll-backward action, which moves it by the app's own step, or else with the set-progress action by a twentieth
     * of its range. [expected] checks the element with [key] as [tap] does.
     */
    suspend fun adjust(key: String, increase: Boolean, expected: ((ScreenElement) -> Boolean)? = null): ActionResult {
        val live = snapshots.current()
        refused(live.snapshot, key, expected)?.let { return it }
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!node.isEnabled) return DISABLED
        // A number can name an element that is no slider, such as a list, whose scroll action would move it instead.
        val range = node.rangeInfo ?: return ActionResult.Failed("It isn't a slider.")
        val step = if (increase) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node.offers(step) && node.perform(step)) return settled(ActionMethod.ACTION)
        if (node.offers(SET_PROGRESS)) {
            // A range of whole numbers that has fewer than 20 steps would round a twentieth back to the same value.
            val change = ((range.max - range.min) / SLIDER_STEPS).let {
                if (range.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) maxOf(1f, it) else it
            }
            val value = (range.current + if (increase) change else -change).coerceIn(range.min, range.max)
            if (value != range.current) {
                val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
                return if (node.perform(SET_PROGRESS, arguments)) {
                    settled(ActionMethod.ACTION)
                } else {
                    ActionResult.Failed("The slider didn't move.")
                }
            }
        }
        // Apps offer a step's action only while the slider can move that way.
        return ActionResult.Failed(if (increase) "The slider is at its highest." else "The slider is at its lowest.")
    }

    /**
     * Expands the element with [key] with its expand action, or else taps it, as most rows that open and close allow. In
     * SystemUI it never taps, because a tap on a notification opens its app. [expected] checks the element with [key] as
     * [tap] does.
     */
    suspend fun expand(key: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult =
        expandOrCollapse(key, AccessibilityNodeInfo.ACTION_EXPAND, expected)

    /** Collapses the element with [key], in the same way as [expand]. */
    suspend fun collapse(key: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult =
        expandOrCollapse(key, AccessibilityNodeInfo.ACTION_COLLAPSE, expected)

    private suspend fun expandOrCollapse(key: String, action: Int, expected: ((ScreenElement) -> Boolean)?): ActionResult {
        val live = snapshots.current()
        refused(live.snapshot, key, expected)?.let { return it }
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!node.isEnabled) return DISABLED
        if (node.offers(action) && node.perform(action)) return settled(ActionMethod.ACTION)
        val expanding = action == AccessibilityNodeInfo.ACTION_EXPAND
        // An element that offers only the other action is already as the owner wants it: a tap would toggle it back.
        val other = if (expanding) AccessibilityNodeInfo.ACTION_COLLAPSE else AccessibilityNodeInfo.ACTION_EXPAND
        if (node.offers(other)) return ActionResult.Failed(if (expanding) "It's already expanded." else "It's already collapsed.")
        if (element.packageName == SYSTEM_UI) return ActionResult.Failed(if (expanding) "It can't expand." else "It can't collapse.")
        return tap(key, expected)
    }

    /**
     * Dismisses the element with [key] with its dismiss action (design spec, Appendix A). In SystemUI's notification shade
     * a notification that offers none is swiped away ([swipeAway]). Anywhere else Kestrel never swipes sideways on its
     * own, because a sideways swipe across a row can archive a message or start a call (section 9.3). [expected] checks
     * the element with [key] as [tap] does.
     */
    suspend fun dismiss(key: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult {
        val live = snapshots.current()
        refused(live.snapshot, key, expected)?.let { return it }
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!node.isEnabled) return DISABLED
        if (node.offers(AccessibilityNodeInfo.ACTION_DISMISS) && node.perform(AccessibilityNodeInfo.ACTION_DISMISS)) {
            return settled(ActionMethod.ACTION)
        }
        return if (element.notificationRow) swipeAway(key, expected) else NOT_DISMISSIBLE
    }

    /**
     * Swipes the notification with [key] in SystemUI's notification shade sideways across 80% of its width, as a finger
     * dismisses it, along the line nearest to its middle that the windows above, such as the navigation bar, leave
     * uncovered, and fails when they cover every such line. It swipes nothing else (design spec, section 9.3). [expected]
     * checks the element with [key] as [tap] does.
     */
    suspend fun swipeAway(key: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult {
        val snapshot = snapshots.current().snapshot
        refused(snapshot, key, expected)?.let { return it }
        val element = snapshot.element(key) ?: return NOT_ON_SCREEN
        if (!element.notificationRow) return NOT_DISMISSIBLE
        val bounds = element.bounds
        val fromX = bounds.left + bounds.width / 10
        val toX = bounds.right - 1 - bounds.width / 10
        val y = Uncovered.line(bounds, snapshot.windowsAbove(element.windowId), fromX, toX) ?: return ActionResult.COVERED
        val from = ScreenPoint(fromX, y)
        val to = ScreenPoint(toX, y)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    /** Why the element with [key] in [snapshot] is not one to act on: it is missing, or [expected] refuses it. */
    private fun refused(snapshot: ScreenSnapshot, key: String, expected: ((ScreenElement) -> Boolean)?): ActionResult? {
        val element = snapshot.element(key) ?: return NOT_ON_SCREEN
        return if (expected != null && !expected(element)) ActionResult.CHANGED else null
    }

    /**
     * Whether the element is a notification row in SystemUI's shade: one that offers expand or collapse. A View reports
     * a class that Android knows, such as `FrameLayout`, so the class cannot tell a row, and a Quick Settings tile, the
     * media player or a button of the power menu offers neither action.
     */
    private val ScreenElement.notificationRow: Boolean
        get() = packageName == SYSTEM_UI && (ElementAction.EXPAND in actions || ElementAction.COLLAPSE in actions)

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
        return magnified(ActionResult.Failed("Android refused to magnify the screen.")) {
            service.magnificationController.setMagnificationConfig(config, false)
        }
    }

    suspend fun resetZoom(): ActionResult =
        magnified(ActionResult.Failed("Android refused to reset the magnification.")) {
            service.magnificationController.resetCurrentMagnification(false)
        }

    /**
     * Magnification moves every element on the screen, and Android sends no accessibility event for it, so Kestrel
     * reports the change to the tracker itself: snapshots are built anew, and the numbers and labels follow the
     * elements. It reports it again after the wait, because a snapshot built during the wait may still show the old
     * positions.
     *
     * Before the second report, Kestrel also clears Android's node cache in its own process. Android clears that cache
     * when the magnification changes, but before the window manager uses the new magnification, so a read at that
     * moment puts the old positions back. A change of scale while the screen is already magnified adds no window, so
     * no later event clears the cache, and every snapshot would keep the old positions.
     */
    private suspend fun magnified(refused: ActionResult, change: () -> Boolean): ActionResult =
        changeMagnification(change, refused, tracker::onScreenChanged, { settled(ActionMethod.MAGNIFICATION) }) {
            service.clearCache()
            tracker.onScreenChanged()
        }

    suspend fun global(action: GlobalAction): ActionResult {
        return if (service.performGlobalAction(action.id)) {
            settled(ActionMethod.GLOBAL_ACTION)
        } else {
            action.refused
        }
    }

    /** Opens the app with [packageName] as its launcher icon would. */
    suspend fun openApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("No app with the package $packageName can be opened.")
        return try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app with the package $packageName can be opened.")
        } catch (e: SecurityException) {
            ActionResult.Failed("Android does not let Kestrel open $packageName.")
        }
    }

    /** Opens a link or a deep link in the app that handles it. */
    suspend fun openUri(uri: String): ActionResult {
        return try {
            service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app can open the link.")
        } catch (e: FileUriExposedException) {
            // Android does not let an app hand a file:// link to another app.
            ActionResult.Failed("Android does not let Kestrel open the link.")
        } catch (e: SecurityException) {
            ActionResult.Failed("Android does not let Kestrel open the link.")
        }
    }

    /**
     * Clicks the element whose content description or text is [label], in any letter case, in the windows on the screen
     * of the apps in [packages] only, such as a call's heads-up notification, the incoming-call screen over the lock
     * screen or the in-call screen (design spec, section 8.7): the element itself, or its nearest parent that handles
     * touches. An element with the label in another app's window is never clicked and never counts. Returns whether an
     * element took the click. With [ifOff], an element that is checked already, or whose parent that handles touches is,
     * counts as clicked and stays as it is, such as a speaker control that is on. It looks at no more than
     * [MAX_LABEL_NODES] elements in each window, and it keeps no text that it read.
     */
    fun clickLabelled(label: String, packages: Set<String>, ifOff: Boolean = false): Boolean {
        for (window in service.windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() !in packages) continue
            val node = labelled(root, label) ?: continue
            val target = node.touchTarget(AccessibilityNodeInfo.ACTION_CLICK) ?: continue
            if (ifOff && (node.isOn() || target.node.isOn())) return true
            if (target.node.isEnabled && target.node.perform(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /**
     * The package of the app window in front of the others, such as the in-call screen once it has come to the front, or
     * null while the screen shows no app window that Kestrel can read. Android lists the windows from the top down.
     */
    fun frontApp(): String? {
        val front = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return front?.root?.packageName?.toString()
    }

    // Android 16 replaced the yes-or-no checked state with one that can also be "partly checked".
    private fun AccessibilityNodeInfo.isOn(): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            isChecked
        }

    /** The first visible node below [root], breadth first, whose content description or text is [label]. */
    private fun labelled(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_LABEL_NODES) {
            val node = queue.removeFirst()
            seen++
            val named = node.contentDescription?.toString()?.trim().equals(label, ignoreCase = true) ||
                node.text?.toString()?.trim().equals(label, ignoreCase = true)
            if (named && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private suspend fun press(
        key: String,
        action: Int,
        fallback: (Int, Int) -> GestureDescription,
        expected: ((ScreenElement) -> Boolean)?,
    ): ActionResult {
        val live = snapshots.current()
        refused(live.snapshot, key, expected)?.let { return it }
        val node = live.node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.perform(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh() && node.isVisibleToUser) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            if (!bounds.isEmpty) {
                return fallbackAt(bounds, live.snapshot.windowsAbove(live.snapshot.element(key)?.windowId), fallback)
            }
        }
        // The node has gone, is hidden or refused the action, so look for the element again (design spec, section 14.3).
        // The key may name another element in the fresh snapshot, so [expected] checks it again.
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        if (expected != null && !expected(element)) return ActionResult.CHANGED
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.perform(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return fallbackAt(element.bounds, fresh.snapshot.windowsAbove(element.windowId), fallback)
    }

    /** A fallback gesture on the part of [bounds] that the windows above leave uncovered. */
    private suspend fun fallbackAt(bounds: IntRect, covers: List<IntRect>, build: (Int, Int) -> GestureDescription): ActionResult {
        val point = Uncovered.point(bounds, covers) ?: return ActionResult.COVERED
        return gestureAt(point, build)
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available.")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.perform(AccessibilityNodeInfo.ACTION_FOCUS)) node.perform(AccessibilityNodeInfo.ACTION_CLICK)
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
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method.")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus.")
        // The input method's typing is Kestrel's own action, so its text change never counts as the owner's (design D19 of sub-project 5).
        own.begin()
        try {
            withContext(Dispatchers.Main) {
                connection.performContextMenuAction(android.R.id.selectAll)
                connection.commitText(text, 1, null)
            }
            return if (node.shows(text, element.password)) {
                settled(ActionMethod.INPUT_METHOD)
            } else {
                ActionResult.Failed("The text field did not take the text.")
            }
        } finally {
            own.end()
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
        own.begin()
        val outcome = try {
            gestures.perform(*build())
        } finally {
            own.end()
        }
        return when (outcome) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> {
                // On Android 13 to 15 only a touch on the screen cancels Kestrel's gesture: the owner's (design D19 of sub-project 5).
                own.gestureCancelled()
                ActionResult.Failed("A touch on the screen cancelled the gesture.")
            }
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture.")
            GestureOutcome.TIMED_OUT -> ActionResult.Failed("Android did not report the end of the gesture.")
        }
    }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        // The settle wait belongs to the action, so that no event of it counts as the owner's (design D19 of sub-project 5).
        own.begin()
        try {
            tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        } finally {
            own.end()
        }
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

    /**
     * Performs [action] on this node as one of Kestrel's own actions, so that the touch watcher can tell its echo from the
     * owner's finger (design D19 of sub-project 5), and gives Android's answer.
     */
    private fun AccessibilityNodeInfo.perform(action: Int, arguments: Bundle? = null, kind: OwnActions.Kind = OwnActions.kindOf(action)): Boolean =
        own.node(kind) { performAction(action, arguments) }

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

    private companion object {
        const val MAX_PARENT_DEPTH = 20

        // A call's screens have a few dozen elements; the limit keeps a search of a large window short.
        const val MAX_LABEL_NODES = 2_000
        val NOT_ON_SCREEN = ActionResult.Failed("It isn't on the screen.")
        val DISABLED = ActionResult.Failed("It's disabled.")
        val GONE = ActionResult.Failed("It's no longer on the screen.")
        val OFF_SCREEN = ActionResult.Failed("The point is outside the screen.")
        val NOT_DISMISSIBLE = ActionResult.Failed("I can't dismiss it.")
        val SET_PROGRESS = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        const val SLIDER_STEPS = 20f
        const val SYSTEM_UI = "com.android.systemui"
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
