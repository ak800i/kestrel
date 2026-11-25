package dev.kestrel.app.debug

import dev.kestrel.agent.observe.SystemUiParts
import dev.kestrel.agent.observe.SystemUiParts.Companion.SYSTEM_UI
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * A button of Kestrel's own goal notification in SystemUI's shade (plan 7E), for the hook goal-control. The notification's
 * row is the nearest notification row above the notification's title, as [SystemUiParts] knows SystemUI's notification
 * rows; the button is a clickable SystemUI element in that row with the button's label, so that another app's button with
 * the same label never counts. Collapsed notification rows around the title, a group's first, are named for expanding,
 * because a notification can still be in a collapsed group or row (design 10.6 says when). Labels are compared without
 * letter case, because some phones write a notification's buttons in capitals.
 */
internal class NoticeButtons(private val parts: SystemUiParts = SystemUiParts.DEFAULT) {
    /** The key of the button [label] in the row whose title is [title]; null when the shade does not show it. */
    fun button(snapshot: ScreenSnapshot, title: String, label: String): String? {
        val row = rows(snapshot, title).firstOrNull() ?: return null
        return snapshot.elements.indices.firstOrNull { index ->
            isButton(snapshot.elements[index], label) && row in ancestors(snapshot, index)
        }?.let { snapshot.elements[it].key }
    }

    /** Whether [element] is SystemUI's clickable element with the label [label]: the check that a press repeats. */
    fun isButton(element: ScreenElement, label: String): Boolean =
        element.packageName == SYSTEM_UI && element.clickable && named(element, label)

    /**
     * The key of the next collapsed notification row around the title [title], so that it can be expanded to show the
     * row's buttons; null when none is collapsed. While a group's row is collapsed, each row in it shows one line and no
     * buttons, even when it is expanded itself, so the outermost collapsed row comes first, the group's before the
     * notification's own. Only notification rows count, never another of SystemUI's containers that offers expand.
     */
    fun collapsedRow(snapshot: ScreenSnapshot, title: String): String? =
        rows(snapshot, title).lastOrNull { ElementAction.EXPAND in snapshot.elements[it].actions }?.let { snapshot.elements[it].key }

    /**
     * Whether SystemUI's shade is open. One of SystemUI's windows is the active one while the shade shows, and never its
     * status bar, which stays inactive under the app in front. While the phone is locked, SystemUI's active window is also
     * the lock screen or the bouncer, so then the shade counts as open only while that window shows Quick Settings' tiles,
     * as the shade pulled over the lock screen does and the lock screen and the bouncer do not.
     */
    fun shadeOpen(snapshot: ScreenSnapshot, keyguardLocked: Boolean): Boolean {
        val active = snapshot.windows.firstOrNull { it.packageName == SYSTEM_UI && it.active } ?: return false
        return !keyguardLocked || snapshot.elements.any { it.windowId == active.id && parts.isTile(it) }
    }

    // The notification rows that hold the title, from the notification's own row outwards to its group's.
    private fun rows(snapshot: ScreenSnapshot, title: String): List<Int> {
        val titled = snapshot.elements.indices.firstOrNull { snapshot.elements[it].packageName == SYSTEM_UI && named(snapshot.elements[it], title) }
            ?: return emptyList()
        return ancestors(snapshot, titled).filter { index -> parts.isNotification(snapshot.elements[index]) }
    }

    private fun ancestors(snapshot: ScreenSnapshot, index: Int): List<Int> =
        generateSequence(snapshot.elements[index].parent) { snapshot.elements[it].parent }.toList()

    private fun named(element: ScreenElement, words: String): Boolean =
        element.text.equals(words, ignoreCase = true) || element.contentDescription.equals(words, ignoreCase = true)
}