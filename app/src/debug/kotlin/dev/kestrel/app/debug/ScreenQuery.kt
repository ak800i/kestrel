package dev.kestrel.app.debug

import dev.kestrel.app.commands.DeviceCommands
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import java.util.Collections

/**
 * Hook H3 of plan 4D, screen-has: which elements of Kestrel's snapshot match a filter, answered on the phone as counts,
 * overlay numbers, bounds and hashes, never text (2026-11-20-a71-checks-4d-interfaces.md, section 5). Only elements that a finger can
 * reach count, as for the commands. Pure, so that JVM tests check it on made-up snapshots.
 */
internal object ScreenQuery {
    /** What an element must have; null and empty mean anything. */
    data class Filter(
        val packageName: String? = null,
        val viewId: String? = null,
        val label: String? = null,
        val contains: String? = null,
        val className: String? = null,
        val states: Set<String> = emptySet(),
    ) {
        // The label and the words to contain may be a screen's words, so only their lengths are printed.
        override fun toString() =
            "Filter(package=$packageName, viewId=$viewId, label=${label?.length}, contains=${contains?.length}, " +
                "class=$className, states=$states)"
    }

    val STATES = listOf(
        "checked", "unchecked", "selected", "focused", "editable", "password", "scrollable", "clickable", "actionable",
        "expandable", "collapsible", "dismissable", "slider",
    )

    /** The states of a comma-separated [text]; throws [IllegalArgumentException] for one that is not in [STATES]. */
    fun states(text: String?): Set<String> {
        val states = text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().toSet()
        require(states.all { it in STATES }) { "add --es state with some of ${STATES.joinToString()}" }
        return states
    }

    /** The reachable elements of [snapshot] that pass [filter], in the snapshot's order. */
    fun matches(snapshot: ScreenSnapshot, filter: Filter): List<ScreenElement> =
        snapshot.elements.filter { snapshot.reachable(it) && passes(it, filter) }

    /** The first name of [element]: its label, text, description or hint, for a template's {label}; never sent off the phone. */
    fun firstName(element: ScreenElement): String? =
        listOfNotNull(element.label, element.text, element.contentDescription, element.hint).firstOrNull { it.isNotBlank() }

    /** The answer of screen-has for [snapshot] with the numbers [badges] on it. */
    fun answer(snapshot: ScreenSnapshot, badges: List<Badge>, filter: Filter): String {
        val found = matches(snapshot, filter)
        val first = found.firstOrNull()
        val number = first?.let { element -> badges.firstOrNull { it.key == element.key }?.number }
        val bounds = first?.bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }
        val inner = first?.let { innerName(snapshot, it) }
        val front = DeviceCommands.frontApp(snapshot.windows)
        return "count=${found.size} number=${number ?: "none"} bounds=${bounds ?: "none"} " +
            "first=${inner?.let(CommandTags::hash) ?: "none"} app=${front?.packageName ?: "none"} " +
            "appTitle=${front?.title?.let(CommandTags::hash) ?: "none"} windows=${snapshot.windows.size}"
    }

    private fun passes(element: ScreenElement, filter: Filter): Boolean {
        val packageName = filter.packageName
        if (packageName != null && element.packageName != packageName) return false
        val viewId = filter.viewId
        if (viewId != null && element.viewId?.substringAfter(":id/")?.contains(viewId) != true) return false
        val names = listOfNotNull(element.label, element.text, element.contentDescription, element.hint)
        val label = filter.label
        if (label != null && names.none { Normalizer.normalize(it) == Normalizer.normalize(label) }) return false
        val contains = filter.contains
        if (contains != null) {
            val wanted = Normalizer.words(contains)
            if (wanted.isEmpty() || names.none { Collections.indexOfSubList(Normalizer.words(it), wanted) >= 0 }) return false
        }
        val className = filter.className
        if (className != null) {
            val own = element.className ?: return false
            if (own != className && !own.endsWith(".$className")) return false
        }
        // sub-project 5B
        return filter.states.all { has(element, it) }
    }

    private fun has(element: ScreenElement, state: String): Boolean = when (state) {
        "checked" -> element.checkable && element.checked
        "unchecked" -> element.checkable && !element.checked
        "selected" -> element.selected
        "focused" -> element.focused
        "editable" -> element.editable
        "password" -> element.password
        "scrollable" -> element.scrollable
        "clickable" -> element.clickable
        "actionable" -> element.isActionable
        "expandable" -> ElementAction.EXPAND in element.actions
        "collapsible" -> ElementAction.COLLAPSE in element.actions
        "dismissable" -> ElementAction.DISMISS in element.actions
        "slider" -> element.slider
        else -> false
    }

    // The first name of the first actionable element inside [element] that has one, as a list's first visible row.
    private fun innerName(snapshot: ScreenSnapshot, element: ScreenElement): String? {
        val index = snapshot.elements.indexOf(element)
        if (index < 0) return null
        for (inner in index + 1 until snapshot.elements.size) {
            if (!inside(snapshot, inner, index)) return null
            val candidate = snapshot.elements[inner]
            if (candidate.isActionable) firstName(candidate)?.let { return it }
        }
        return null
    }

    // Whether the element at [index] lies inside the element at [ancestor], at most 20 levels down. The snapshot lists the
    // elements inside an element right after it.
    private fun inside(snapshot: ScreenSnapshot, index: Int, ancestor: Int): Boolean {
        var parent = snapshot.elements[index].parent
        var depth = 0
        while (parent != null && depth < MAX_DEPTH) {
            if (parent == ancestor) return true
            parent = snapshot.elements.getOrNull(parent)?.parent
            depth++
        }
        return false
    }

    private const val MAX_DEPTH = 20
}
