package dev.kestrel.app.debug

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * A button of Kestrel's own goal notification in SystemUI's shade (plan 7E), for the hook goal-control. The notification's
 * row is the SystemUI element above the notification's title that offers expand or collapse, as `ScreenActions` tells a
 * row; the button is a clickable SystemUI element in that row with the button's label, so that another app's button with
 * the same label never counts. Collapsed rows around the title, the group's that Android puts Kestrel's notifications in
 * first, are named for expanding. Labels are compared without letter case, because some phones write a notification's
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

    /**
     * The key of the next collapsed row around the title [title], so that it can be expanded to show the row's buttons; null
     * when none is collapsed. Android groups an app's notifications by itself (on Android 17 from the second one), and
     * while the group's row is collapsed each row in it shows one line and no buttons, even when it is expanded itself: so
     * the outermost collapsed row comes first, the group's before the notification's own.
     */
    fun collapsedRow(snapshot: ScreenSnapshot, title: String): String? =
        rows(snapshot, title).lastOrNull { ElementAction.EXPAND in snapshot.elements[it].actions }?.let { snapshot.elements[it].key }

    // The notification's own row: the nearest of the rows around its title.
    private fun row(snapshot: ScreenSnapshot, title: String): Int? = rows(snapshot, title).firstOrNull()

    // The rows that hold the title, from the notification's own row outwards to its group's: the elements above the title
    // that offer expand or collapse.
    private fun rows(snapshot: ScreenSnapshot, title: String): List<Int> {
        val titled = snapshot.elements.indices.firstOrNull { snapshot.elements[it].packageName == SYSTEM_UI && named(snapshot.elements[it], title) }
            ?: return emptyList()
        return ancestors(snapshot, titled).filter { index ->
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
