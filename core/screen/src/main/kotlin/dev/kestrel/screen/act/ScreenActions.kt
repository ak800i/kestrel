package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache

/** Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). */
class ScreenActions internal constructor(
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gesture(Gestures.tap(x, y))

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gesture(Gestures.longPress(x, y))

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
            return gesture(fallback(bounds.centerX, bounds.centerY))
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return ActionResult.Failed("The element is no longer on the screen")
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gesture(fallback(element.bounds.centerX, element.bounds.centerY))
    }

    private suspend fun gesture(description: GestureDescription): ActionResult =
        when (gestures.perform(description)) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
        }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = clock())
        return ActionResult.Done(method)
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /**
     * The element's node, or its nearest parent up to [MAX_PARENT_DEPTH] levels up, that handles touches. A finger's
     * touch stops at that view even when it is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (current.isClickable || current.isLongClickable || current.offers(action)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
    }
}
