package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent

/**
 * Tells which accessibility events may start a window's slide into place: a new state of another app's window, such as
 * an app or a dialog that opens, and a window that is added, removed or moved (design spec, section 9.2). Kestrel's
 * overlay windows, such as the feedback bar, the numbers, the grid and the touch watcher, never count: their state
 * events name [ownPackage] and the overlays' root class [overlayClass], and the changes of the window list that follow
 * name only the window, which this class then remembers, the newest [MAX_OWN_WINDOWS] of them. Kestrel's settings app
 * counts like any other app. Use it on one thread, such as the main thread, which gets the accessibility events.
 */
class WindowEvents(private val ownPackage: String, private val overlayClass: String) {
    // Each overlay that Kestrel shows is a new window, and those that take no touches never fire a removal.
    private val ownWindows = ArrayDeque<Int>()

    fun movesWindow(eventType: Int, packageName: CharSequence?, className: CharSequence?, windowId: Int, windowChanges: Int): Boolean =
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val own = packageName?.toString() == ownPackage && className?.toString() == overlayClass
                if (own && windowId !in ownWindows) {
                    ownWindows.addLast(windowId)
                    if (ownWindows.size > MAX_OWN_WINDOWS) ownWindows.removeFirst()
                }
                !own
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (windowId in ownWindows) {
                    if (windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0) ownWindows.remove(windowId)
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
        const val MAX_OWN_WINDOWS = 16
    }
}
