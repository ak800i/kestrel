package dev.kestrel.app.debug

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * A button of Kestrel's own goal notification in SystemUI's shade (plan 7E), for the hook goal-control. The notification's
 * row is the SystemUI element above the notification's title that offers expand or collapse, as `ScreenActions` tells a
 * row; the button is a clickable SystemUI element in that row with the button's label, so that another app's button with
 * the same label never counts. Labels are compared without letter case, because some phones write a notification's
 * buttons in capitals.
 */
internal object NoticeButtons {
    /** The key of the button [label] in the row whose title is [title]; null when the shade does not show it. */
    fun button(snapshot: ScreenSnapshot, title: String, label: String): String? {
        val row = row(snapshot, title) ?: return null
        return snapshot.elements.indices.firstOrNull { index ->
            isButton(snapshot.elements[index], label) && row in ancestors(snapshot, index)
        }?.let { snapshot.elements[it].key }
    }

    /** Whether [element] is SystemUI's clickable element with the label [label]: the check that a press repeats. */
    fun isButton(element: ScreenElement, label: String): Boolean =
        element.packageName == SYSTEM_UI && element.clickable && named(element, label)

    /** The key of the row whose title is [title] while it is collapsed, so that it can be expanded to show its buttons. */
    fun collapsedRow(snapshot: ScreenSnapshot, title: String): String? =
        row(snapshot, title)?.let { snapshot.elements[it] }?.takeIf { ElementAction.EXPAND in it.actions }?.key

    private fun row(snapshot: ScreenSnapshot, title: String): Int? {
        val titled = snapshot.elements.indices.firstOrNull { snapshot.elements[it].packageName == SYSTEM_UI && named(snapshot.elements[it], title) }
            ?: return null
        return ancestors(snapshot, titled).firstOrNull { index ->
            val actions = snapshot.elements[index].actions
            ElementAction.EXPAND in actions || ElementAction.COLLAPSE in actions
        }
    }

    private fun ancestors(snapshot: ScreenSnapshot, index: Int): List<Int> =
        generateSequence(snapshot.elements[index].parent) { snapshot.elements[it].parent }.toList()

    private fun named(element: ScreenElement, words: String): Boolean =
        element.text.equals(words, ignoreCase = true) || element.contentDescription.equals(words, ignoreCase = true)

    private const val SYSTEM_UI = "com.android.systemui"
}
