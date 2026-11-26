package dev.kestrel.app.debug

import dev.kestrel.agent.observe.SystemUiParts
import dev.kestrel.agent.observe.SystemUiParts.Companion.SYSTEM_UI
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowSummary

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
     * The next collapsed notification row around the title [title], so that it can be expanded to show the row's buttons;
     * null when none is collapsed. While a group's row is collapsed, each row in it shows one line and no buttons, even when
     * it is expanded itself, so the outermost collapsed row comes first, the group's before the notification's own. Only
     * notification rows count, never another of SystemUI's containers that offers expand.
     */
    fun collapsedRow(snapshot: ScreenSnapshot, title: String): Collapsed? {
        val rows = rows(snapshot, title)
        val index = rows.indexOfLast { ElementAction.EXPAND in snapshot.elements[it].actions }.takeIf { it >= 0 } ?: return null
        return Collapsed(snapshot.elements[rows[index]].key, if (index == 0) ShadeExpansion.ROW else ShadeExpansion.GROUP)
    }

    /**
     * Looks in the shade up to [tries] times for the button [label] of the row whose title is [title], and asks to expand
     * the next collapsed row around it ([collapsedRow]) after each look that does not show the button. [look] gives the
     * shade's snapshot after the shade's own wait, and [expand] asks SystemUI to expand the row with the key. The answer
     * names the button's key, null when no look showed it, and the expansions that the search asked for, in their order,
     * each once: whether the row showed its buttons without any (design 10.6 of sub-project 7).
     */
    suspend fun find(
        title: String,
        label: String,
        tries: Int,
        look: suspend () -> ScreenSnapshot,
        expand: suspend (String) -> Unit,
    ): Found {
        val expanded = mutableListOf<ShadeExpansion>()
        repeat(tries) {
            val snapshot = look()
            button(snapshot, title, label)?.let { return Found(it, expanded) }
            collapsedRow(snapshot, title)?.let { row ->
                expand(row.key)
                if (row.expansion !in expanded) expanded += row.expansion
            }
        }
        return Found(null, expanded)
    }

    /**
     * Whether SystemUI's shade may be open: false only when the screen shows that it is closed. One of SystemUI's windows is
     * the active one while the shade shows, and never its status bar, which stays inactive under the app in front. While
     * the phone is locked, SystemUI's active window is also the lock screen or the bouncer, so then the shade counts as open
     * only while that window shows Quick Settings' tiles, as the shade pulled over the lock screen does and the lock screen
     * and the bouncer do not. That tells them apart only when [SystemUiParts] knows this phone's tiles, which
     * [tilesKnown] says: whether the shade showed such a tile while it was open ([showsTiles]). Without that evidence, as
     * on a phone whose tile IDs `systemui-parts.json` lacks, a locked phone's shade counts as open, the safe answer: a
     * caller then collapses a shade that may already be closed, which does no harm, and never leaves it open over the lock
     * screen.
     */
    fun shadeOpen(snapshot: ScreenSnapshot, keyguardLocked: Boolean, tilesKnown: Boolean): Boolean {
        if (activeWindow(snapshot) == null) return false
        return !keyguardLocked || !tilesKnown || showsTiles(snapshot)
    }

    /** Whether SystemUI's active window shows a Quick Settings tile that [SystemUiParts] knows. */
    fun showsTiles(snapshot: ScreenSnapshot): Boolean {
        val active = activeWindow(snapshot) ?: return false
        return snapshot.elements.any { it.windowId == active.id && parts.isTile(it) }
    }

    private fun activeWindow(snapshot: ScreenSnapshot): WindowSummary? =
        snapshot.windows.firstOrNull { it.packageName == SYSTEM_UI && it.active }

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

    /** A collapsed notification row with its [key], and which [expansion] expanding it is. */
    data class Collapsed(val key: String, val expansion: ShadeExpansion)

    /** What [find] found: the button's [key], or null, and the [expanded] rows before it, in their order. */
    data class Found(val key: String?, val expanded: List<ShadeExpansion>)
}

/** A collapsed row that the hook goal-control expanded in the shade: the notification's own [ROW], or the [GROUP] that Android put it in. */
internal enum class ShadeExpansion { ROW, GROUP }
