package dev.kestrel.screen.read

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind

/** A node for tests on the PC. Each property has the value of a plain, visible, enabled view. */
class FakeNode(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val hint: String? = null,
    override val stateDescription: String? = null,
    override val viewId: String? = null,
    override val className: String? = "android.view.View",
    override val packageName: String? = "dev.app",
    override val bounds: IntRect = IntRect(0, 0, 100, 100),
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val clickable: Boolean = false,
    override val longClickable: Boolean = false,
    override val editable: Boolean = false,
    override val checkable: Boolean = false,
    override val checked: Boolean = false,
    override val scrollable: Boolean = false,
    override val focused: Boolean = false,
    override val selected: Boolean = false,
    override val password: Boolean = false,
    override val dataSensitive: Boolean = false,
    override val actions: Set<ElementAction> = emptySet(),
    val children: List<FakeNode> = emptyList(),
) : NodeData {
    override val childCount: Int get() = children.size

    override fun child(index: Int): NodeData = children[index]
}

class FakeWindow(
    override val root: NodeData?,
    override val id: Int = 1,
    override val kind: WindowKind = WindowKind.APPLICATION,
    override val isOverlay: Boolean = false,
    override val title: String? = null,
    override val bounds: IntRect = IntRect(0, 0, 1080, 2400),
    override val layer: Int = 0,
    override val active: Boolean = true,
    override val focused: Boolean = true,
) : WindowData
