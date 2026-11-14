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
)

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

/**
 * One element of a [ScreenSnapshot] (design spec, section 9.2). Its [key] stays the same as long as the
 * element's package, view ID, label and class and its position among similar siblings stay the same.
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
    val stateDescription: String? = null,
    val viewId: String? = null,
    /** The name that Kestrel uses for the element: its text, description or hint, or the text inside it. */
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
) {
    /** Whether the owner can act on the element directly. Numbers go on these elements (design spec, section 9.4). */
    val isActionable: Boolean get() = enabled && (clickable || longClickable || editable || checkable)
}

/** The interactive windows on the screen and their elements, top window first (design spec, section 9.2). */
@Serializable
data class ScreenSnapshot(
    val takenAtMillis: Long,
    val windows: List<WindowSummary>,
    val elements: List<ScreenElement>,
) {
    fun element(key: String): ScreenElement? = elements.firstOrNull { it.key == key }
}
