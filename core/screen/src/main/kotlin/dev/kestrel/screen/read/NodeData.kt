package dev.kestrel.screen.read

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind

/** What a snapshot reads from one accessibility node. Android's nodes and the tests' fake nodes provide it. */
interface NodeData {
    val packageName: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val hint: String?
    /**
     * A short description that adds to the node's text, such as a field's purpose. WebView and Chrome 157 give a form
     * field's name here, for example a text field's or a drop-down list's, and keep only a field's placeholder or help
     * text in the hint.
     */
    val supplementalDescription: String?
    val stateDescription: String?
    val viewId: String?
    val bounds: IntRect
    val visible: Boolean
    val enabled: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val scrollable: Boolean
    val focused: Boolean
    val selected: Boolean
    val password: Boolean
    val dataSensitive: Boolean

    /**
     * Whether the node is a slider: an enabled node with a range that offers the set-progress, scroll-forward or
     * scroll-backward action (design spec, Appendix A, "Increment" and "Decrement"). A determinate progress bar has a
     * range but none of these actions, so it is no slider.
     */
    val slider: Boolean
    val actions: Set<ElementAction>
    val childCount: Int

    fun child(index: Int): NodeData?
}

/** What a snapshot reads from one window. */
interface WindowData {
    val id: Int
    val kind: WindowKind
    /** Accessibility and magnification overlays, including Kestrel's own, are left out of snapshots (design spec, section 9.2). */
    val isOverlay: Boolean
    val title: String?
    val bounds: IntRect
    val layer: Int
    val active: Boolean
    val focused: Boolean
    val root: NodeData?
}
