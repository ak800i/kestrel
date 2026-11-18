package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells which accessibility events may start a window's slide into place: a new state of another app's window, such as
 * an app or a dialog that opens, and a window that is added, removed or moved (design spec, section 9.2). Kestrel's own
 * windows, such as the feedback bar, the numbers, the grid and the touch watcher, never count: their state events name
 * [ownPackage], and the changes of the window list that follow name only the window, which this class then remembers.
 */
class WindowEvents(private val ownPackage: String) {
    private val ownWindows = ConcurrentHashMap.newKeySet<Int>()

    fun movesWindow(eventType: Int, packageName: CharSequence?, windowId: Int, windowChanges: Int): Boolean =
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val own = packageName?.toString() == ownPackage
                if (own) ownWindows += windowId
                !own
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (windowId in ownWindows) {
                    if (windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0) ownWindows -= windowId
                    false
                } else {
                    windowChanges and MOVES != 0
                }
            }
            else -> false
        }

    private companion object {
        const val MOVES = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
    }
}
