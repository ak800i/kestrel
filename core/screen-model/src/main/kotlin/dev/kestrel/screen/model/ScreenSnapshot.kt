package dev.kestrel.screen.model

import kotlinx.serialization.Serializable

/** The kind of window that an element comes from. Snapshots never contain Kestrel's own overlays. */
@Serializable
enum class WindowKind { APPLICATION, INPUT_METHOD, SYSTEM, SPLIT_SCREEN_DIVIDER, OTHER }

@Serializable
data class WindowSummary(
    val id: Int,
    val kind: WindowKind,
    val packageName: String? = null,
    val title: String? = null,
    val bounds: IntRect,
    val layer: Int = 0,
    val active: Boolean = false,
    val focused: Boolean = false,
) {
    /** Counts and lengths only: a window's title can name a chat (spec 13.5). */
    override fun toString(): String =
        "WindowSummary(id=$id, kind=$kind, packageLength=${packageName?.length ?: 0}, titleLength=${title?.length ?: 0}, layer=$layer, active=$active)"
}

/** The standard accessibility actions that an element offers (design spec, sections 9.2 and 9.3). */
@Serializable
enum class ElementAction {
    CLICK,
    LONG_CLICK,
    FOCUS,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    EXPAND,
    COLLAPSE,
    DISMISS,
}

/** The axis along which a scrollable element scrolls, when Kestrel knows it (design D1 and D8 of sub-project 5). */
@Serializable
enum class ScrollAxis { VERTICAL, HORIZONTAL }

/**
 * One element of a [ScreenSnapshot] (design spec, section 9.2). Its [key] is built from the element's package, view
 * ID, label and class and its position among look-alike siblings, the elements with the same four parts under the
 * same parent. Look-alikes under different parents, such as the "Delete" buttons of a list's rows, can get the same
 * key, so the second element with that key in the snapshot gets the suffix `~2`, the third `~3`, and so on. A text
 * field's label part is only its own description, supplemental description or hint. The key can change when any of
 * these parts change, for example when a look-alike before the element appears or goes, so a key names a position, not
 * an identity.
 */
@Serializable
data class ScreenElement(
    val key: String,
    val windowId: Int,
    /** The index in [ScreenSnapshot.elements] of the nearest ancestor that is also an element. */
    val parent: Int? = null,
    val packageName: String? = null,
    val className: String? = null,
    /** Always null for password fields. */
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    /** A short description that adds to the element's text, such as a field's purpose. */
    val supplementalDescription: String? = null,
    val stateDescription: String? = null,
    val viewId: String? = null,
    /**
     * The name that Kestrel uses for the element: its text, description, supplemental description or hint, or the text
     * inside it.
     */
    val label: String? = null,
    /** For an element without a [label], a name derived from its view ID, for example "compose" for `compose_button`. */
    val suggestedName: String? = null,
    val bounds: IntRect,
    val actions: Set<ElementAction> = emptySet(),
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val password: Boolean = false,
    val dataSensitive: Boolean = false,
    /** A slider, which "increment" and "decrement" move (see `NodeData.slider`). */
    val slider: Boolean = false,
    /** The element's tooltip, the last of the label's sources (design D8 of sub-project 5). */
    val tooltip: String? = null,
    /** The axis of a scrollable element, or null when Kestrel cannot tell it (design D1 and D8 of sub-project 5). */
    val scrollAxis: ScrollAxis? = null,
) {
    /** Counts and lengths only, never screen text (spec 13.5): an element can carry a code or a message. */
    override fun toString(): String =
        "ScreenElement(window=$windowId, keyLength=${key.length}, classLength=${className?.length ?: 0}, textLength=${text?.length ?: 0}, " +
            "labelLength=${label?.length ?: 0}, actionable=$isActionable)"

    /** Whether the owner can act on the element directly. Numbers go on these elements (design spec, section 9.4). */
    val isActionable: Boolean get() = enabled && (clickable || longClickable || editable || checkable || slider)
}

/** The interactive windows on the screen and their elements, top window first (design spec, section 9.2). */
@Serializable
data class ScreenSnapshot(
    val takenAtMillis: Long,
    val windows: List<WindowSummary>,
    val elements: List<ScreenElement>,
) {
    /** Counts only (spec 13.5). */
    override fun toString(): String = "ScreenSnapshot(windows=${windows.size}, elements=${elements.size})"

    fun element(key: String): ScreenElement? = elements.firstOrNull { it.key == key }

    /**
     * The bounds of the windows above the window with [windowId], such as the keyboard, a popup or the status bar; none
     * when the snapshot has no such window. A tap or a gesture goes only where they leave the element uncovered (design
     * spec, section 9.3).
     */
    fun windowsAbove(windowId: Int?): List<IntRect> {
        val window = windows.firstOrNull { it.id == windowId } ?: return emptyList()
        return windows.filter { it.layer > window.layer }.map { it.bounds }
    }

    /**
     * Whether a finger can reach [element]: it lies outside the keyboard's window, and no window above covers it whole.
     * Commands act only on such elements, and only their names become boost words (design spec, sections 8.3 and 9.6).
     */
    fun reachable(element: ScreenElement): Boolean {
        val window = windows.firstOrNull { it.id == element.windowId } ?: return true
        if (window.kind == WindowKind.INPUT_METHOD) return false
        val bounds = element.bounds
        return windows.none { it.layer > window.layer && it.bounds.contains(bounds) }
    }
}
