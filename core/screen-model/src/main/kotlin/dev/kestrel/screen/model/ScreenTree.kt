package dev.kestrel.screen.model

import kotlinx.serialization.Serializable

/**
 * Where an element lies in a window, in a 3×3 grid (design D6 and D8 of sub-project 5): the observation's `@top-left`
 * to `@bottom-right` and a part of the screen signature. Never coordinates.
 */
@Serializable
enum class Region(val label: String) {
    TOP_LEFT("top-left"),
    TOP("top"),
    TOP_RIGHT("top-right"),
    LEFT("left"),
    MIDDLE("middle"),
    RIGHT("right"),
    BOTTOM_LEFT("bottom-left"),
    BOTTOM("bottom"),
    BOTTOM_RIGHT("bottom-right"),
    ;

    companion object {
        /** The cell of [area] that holds the centre of [bounds]; a centre outside [area] takes the nearest cell. */
        fun of(bounds: IntRect, area: IntRect): Region {
            if (area.isEmpty) return MIDDLE
            val column = ((bounds.centerX - area.left).toLong() * 3 / area.width).toInt().coerceIn(0, 2)
            val row = ((bounds.centerY - area.top).toLong() * 3 / area.height).toInt().coerceIn(0, 2)
            return entries[row * 3 + column]
        }
    }
}

/** Plain questions about a snapshot's tree that the agent and the safety check both ask (design D1 of sub-project 5). */
object ScreenTree {
    private val VERTICAL_CLASSES = listOf("RecyclerView", "ListView", "ScrollView", "GridView")

    /** The active app window, else the first app window, else null. */
    fun frontWindow(snapshot: ScreenSnapshot): WindowSummary? {
        val apps = appWindows(snapshot)
        return apps.firstOrNull { it.active } ?: apps.firstOrNull()
    }

    /** Every app window, both apps of a split screen included. */
    fun appWindows(snapshot: ScreenSnapshot): List<WindowSummary> = snapshot.windows.filter { it.kind == WindowKind.APPLICATION }

    /**
     * The part of [window] that the app can use: its bounds with the bottom raised to the top of a keyboard that covers its
     * lower half. The window keeps its full height while the keyboard shows, but the app moves its bottom bar above the
     * keyboard, so regions measured in this area do not change when the keyboard opens or closes (design D6).
     */
    fun visibleArea(snapshot: ScreenSnapshot, window: WindowSummary): IntRect {
        val bounds = window.bounds
        val top = snapshot.windows.filter { it.kind == WindowKind.INPUT_METHOD }
            .mapNotNull { it.bounds.intersect(bounds) }
            .filter { it.centerY > bounds.centerY }
            .minOfOrNull { it.top } ?: return bounds
        return if (top > bounds.top) bounds.copy(bottom = top) else bounds
    }

    /** The indices of the ancestors of element [index], nearest first. */
    fun ancestors(snapshot: ScreenSnapshot, index: Int): List<Int> = buildList {
        val seen = HashSet<Int>()
        var parent = snapshot.elements.getOrNull(index)?.parent
        while (parent != null && seen.add(parent)) {
            add(parent)
            parent = snapshot.elements.getOrNull(parent)?.parent
        }
    }

    fun insideScrollable(snapshot: ScreenSnapshot, index: Int): Boolean =
        ancestors(snapshot, index).any { snapshot.elements[it].scrollable }

    /**
     * Whether [element] is a list that scrolls up and down: by its axis, else by its up or down action, else by a class
     * of a vertical list. A horizontal scroll view, a pager or a carousel is none.
     */
    fun isVerticalList(element: ScreenElement): Boolean {
        if (!element.scrollable) return false
        element.scrollAxis?.let { return it == ScrollAxis.VERTICAL }
        val actions = element.actions
        if (ElementAction.SCROLL_UP in actions || ElementAction.SCROLL_DOWN in actions) return true
        if (ElementAction.SCROLL_LEFT in actions || ElementAction.SCROLL_RIGHT in actions) return false
        val name = element.className.orEmpty()
        return !name.endsWith("HorizontalScrollView") && VERTICAL_CLASSES.any { name.endsWith(it) }
    }

    fun insideVerticalList(snapshot: ScreenSnapshot, index: Int): Boolean =
        ancestors(snapshot, index).any { isVerticalList(snapshot.elements[it]) }

    /** Whether element [index] or an ancestor has a view ID that holds `toolbar` or `action_bar`. */
    fun inToolbar(snapshot: ScreenSnapshot, index: Int): Boolean =
        (listOf(index) + ancestors(snapshot, index)).any { position ->
            val id = idName(snapshot.elements[position].viewId)?.lowercase() ?: return@any false
            "toolbar" in id || "action_bar" in id
        }

    /** The element's label, else the name that Kestrel derived from its view ID. */
    fun name(element: ScreenElement): String? = element.label ?: element.suggestedName

    /** The part of a view ID after `:id/`; a Firefox page's HTML `id` has none and stays whole. */
    fun idName(viewId: String?): String? = viewId?.substringAfter(":id/")?.takeIf { it.isNotEmpty() }
}
