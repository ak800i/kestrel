package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
     * Taps the element with [key] in [seen], or in the current snapshot. Kestrel uses the element's click action,
     * or the click action of its nearest clickable parent. If the element's node has gone or the click fails, it
     * looks for the element again in a fresh snapshot. If nothing offers a click, it taps the element's centre.
     */
    suspend fun tap(key: String, seen: LiveSnapshot? = null): ActionResult =
        press(key, seen, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String, seen: LiveSnapshot? = null): ActionResult =
        press(key, seen, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gesture(Gestures.tap(x, y))

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gesture(Gestures.longPress(x, y))

    private suspend fun press(key: String, seen: LiveSnapshot?, action: Int, gesture: (Int, Int) -> GestureDescription): ActionResult {
        val live = seen ?: snapshots.current()
        val node = live.node(key) ?: return ActionResult.Failed("No element has the key $key")
        val target = node.selfOrParentOffering(action)
        if (target != null) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but neither it nor a parent offers the action.
            val bounds = live.snapshot.element(key)!!.bounds
            return gesture(gesture(bounds.centerX, bounds.centerY))
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return ActionResult.Failed("The element is no longer on the screen")
        val freshTarget = fresh.node(key)?.selfOrParentOffering(action)
        if (freshTarget != null && freshTarget.node.performAction(action)) return settled(ActionMethod.RETRY_ACTION)
        return gesture(gesture(element.bounds.centerX, element.bounds.centerY))
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

    private fun AccessibilityNodeInfo.selfOrParentOffering(action: Int): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (current.isEnabled && current.actionList.any { it.id == action }) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    private companion object {
        const val MAX_PARENT_DEPTH = 20
    }
}
