package dev.kestrel.screen.read

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind

/** Reads a snapshot's data from one of Android's accessibility nodes. */
internal class AccessibilityNodeData(val info: AccessibilityNodeInfo) : NodeData {
    override val packageName: String? get() = info.packageName?.toString()
    override val className: String? get() = info.className?.toString()
    override val text: String? get() = info.shownText()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val hint: String? get() = info.hintText?.toString()
    override val stateDescription: String? get() = info.stateDescription?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val bounds: IntRect get() = Rect().also(info::getBoundsInScreen).toIntRect()
    override val visible: Boolean get() = info.isVisibleToUser
    override val enabled: Boolean get() = info.isEnabled
    override val clickable: Boolean get() = info.isClickable
    override val longClickable: Boolean get() = info.isLongClickable
    override val editable: Boolean get() = info.isEditable
    override val checkable: Boolean get() = info.isCheckable
    override val checked: Boolean get() = info.isCheckedCompat()
    override val scrollable: Boolean get() = info.isScrollable
    override val focused: Boolean get() = info.isFocused
    override val selected: Boolean get() = info.isSelected
    override val password: Boolean get() = info.isPassword
    override val dataSensitive: Boolean get() = Build.VERSION.SDK_INT >= 34 && info.isAccessibilityDataSensitive
    override val actions: Set<ElementAction> get() = info.actionList.mapNotNullTo(mutableSetOf()) { STANDARD_ACTIONS[it.id] }
    override val childCount: Int get() = info.childCount

    override fun child(index: Int): NodeData? = info.getChild(index)?.let(::AccessibilityNodeData)

    private companion object {
        val STANDARD_ACTIONS = mapOf(
            AccessibilityAction.ACTION_CLICK.id to ElementAction.CLICK,
            AccessibilityAction.ACTION_LONG_CLICK.id to ElementAction.LONG_CLICK,
            AccessibilityAction.ACTION_FOCUS.id to ElementAction.FOCUS,
            AccessibilityAction.ACTION_SET_TEXT.id to ElementAction.SET_TEXT,
            AccessibilityAction.ACTION_SCROLL_FORWARD.id to ElementAction.SCROLL_FORWARD,
            AccessibilityAction.ACTION_SCROLL_BACKWARD.id to ElementAction.SCROLL_BACKWARD,
            AccessibilityAction.ACTION_SCROLL_UP.id to ElementAction.SCROLL_UP,
            AccessibilityAction.ACTION_SCROLL_DOWN.id to ElementAction.SCROLL_DOWN,
            AccessibilityAction.ACTION_SCROLL_LEFT.id to ElementAction.SCROLL_LEFT,
            AccessibilityAction.ACTION_SCROLL_RIGHT.id to ElementAction.SCROLL_RIGHT,
            AccessibilityAction.ACTION_EXPAND.id to ElementAction.EXPAND,
            AccessibilityAction.ACTION_COLLAPSE.id to ElementAction.COLLAPSE,
            AccessibilityAction.ACTION_DISMISS.id to ElementAction.DISMISS,
        )
    }
}

/** Reads a snapshot's data from one of Android's accessibility windows. */
internal class AccessibilityWindowData(private val info: AccessibilityWindowInfo) : WindowData {
    override val id: Int get() = info.id
    override val kind: WindowKind
        get() = when (info.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowKind.SPLIT_SCREEN_DIVIDER
            else -> WindowKind.OTHER
        }
    override val isOverlay: Boolean
        get() = info.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            info.type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
    override val title: String? get() = info.title?.toString()
    override val bounds: IntRect get() = Rect().also(info::getBoundsInScreen).toIntRect()
    override val layer: Int get() = info.layer
    override val active: Boolean get() = info.isActive
    override val focused: Boolean get() = info.isFocused
    override val root: NodeData? get() = info.root?.let(::AccessibilityNodeData)
}

private fun Rect.toIntRect() = IntRect(left, top, right, bottom)

/** Android 16 replaced the yes-or-no checked state with one that can also be "partly checked". */
private fun AccessibilityNodeInfo.isCheckedCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= 36) {
        checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
    } else {
        @Suppress("DEPRECATION")
        isChecked
    }

/**
 * The node's text, or null while a text field shows its hint: Android then reports the hint as the text and sets
 * `isShowingHintText`, and an empty field must not seem to contain its hint.
 */
internal fun AccessibilityNodeInfo.shownText(): String? = if (isShowingHintText) null else text?.toString()
