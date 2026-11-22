package dev.kestrel.screen.act

import dev.kestrel.screen.model.ScreenSnapshot

/** How often a tap or a long press may press (design D32 item 29 of sub-project 5). */
enum class PressPolicy {
    /**
     * One press: when Android answers the node action with false, or does not report the end of the fallback gesture,
     * Kestrel does nothing more and gives [ActionResult.UNCERTAIN], because the app may have acted all the same.
     */
    ONCE,

    /**
     * A second press only when a fresh snapshot, taken after the screen went quiet, shows the press's window exactly as
     * before the press; never a third. A window that changed gives [ActionResult.UNCERTAIN].
     */
    VERIFIED,
}

/** The plain check of [PressPolicy.VERIFIED]. */
internal object Presses {
    /** Whether the window [windowId] has the same elements in [after] as in [before]; the time and other windows do not count. */
    fun unchanged(before: ScreenSnapshot, after: ScreenSnapshot, windowId: Int?): Boolean =
        before.elements.filter { it.windowId == windowId } == after.elements.filter { it.windowId == windowId }
}
