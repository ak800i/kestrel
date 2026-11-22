package dev.kestrel.app.agent

import dev.kestrel.agent.port.Expect
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary

/** The checks of `AppScreenPort` that need no Android (design D11 and D32 item 27 of sub-project 5). */
internal object AgentScreenChecks {
    /**
     * The `expected` predicate of `ScreenActions` for what the observation showed (D32 item 27): the element that a key
     * names now must have the same raw label, class, package, and editable, password and checkable flags, or the action
     * does nothing and gives `ActionResult.CHANGED`.
     */
    fun expected(expect: Expect): (ScreenElement) -> Boolean = { element ->
        element.label == expect.label && element.className == expect.className && element.packageName == expect.packageName &&
            element.editable == expect.editable && element.password == expect.password && element.checkable == expect.checkable
    }

    /** The app window in front: the active application window, else the top one. */
    fun front(snapshot: ScreenSnapshot): WindowSummary? {
        val apps = snapshot.windows.filter { it.kind == WindowKind.APPLICATION }
        return apps.firstOrNull { it.active } ?: apps.firstOrNull()
    }

    /**
     * Whether [packageName] is in front with an actionable element in its window (D11); with a null [packageName],
     * whether any app other than [before] is.
     */
    fun inFront(snapshot: ScreenSnapshot, packageName: String?, before: String?): Boolean {
        val window = front(snapshot) ?: return false
        val app = window.packageName ?: return false
        if (if (packageName != null) app != packageName else app == before) return false
        return snapshot.elements.any { it.windowId == window.id && it.isActionable }
    }

    /** Where a swipe goes: across the element that [key] names, or across the app window in front, or the whole [screen]. */
    fun swipeArea(snapshot: ScreenSnapshot, key: String?, screen: IntRect): IntRect? =
        if (key != null) snapshot.element(key)?.bounds else front(snapshot)?.bounds ?: screen
}
