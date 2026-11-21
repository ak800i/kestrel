package dev.kestrel.safety

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary

/**
 * Builds the safety tests' snapshots: an app window of 1000 × 2000 pixels (ID 1, layer 1, active) and its elements in the
 * order of [add], each 100 pixels high and as wide as the window unless the test says otherwise. [add] returns the
 * element's key, and [index] its position, for proposals and the tree's questions.
 */
internal class TestScreen(val packageName: String = "com.example.chat", title: String? = null) {
    private val windows = mutableListOf(WindowSummary(1, WindowKind.APPLICATION, packageName, title, IntRect(0, 0, 1000, 2000), layer = 1, active = true))
    private val elements = mutableListOf<ScreenElement>()

    fun add(
        top: Int,
        label: String? = null,
        id: String? = null,
        parent: String? = null,
        className: String = "android.widget.Button",
        left: Int = 0,
        right: Int = 1000,
        height: Int = 100,
        clickable: Boolean = true,
        editable: Boolean = false,
        password: Boolean = false,
        focused: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        scrollable: Boolean = false,
        text: String? = label,
        hint: String? = null,
        description: String? = null,
        axis: ScrollAxis? = null,
        window: Int = 1,
        packageName: String = windows.first { it.id == window }.packageName.orEmpty(),
    ): String {
        val key = "$packageName|${elements.size}|${label.orEmpty()}"
        elements += ScreenElement(
            key = key,
            windowId = window,
            parent = parent?.let { index(it) },
            packageName = packageName,
            className = className,
            text = text,
            contentDescription = description,
            hint = hint,
            viewId = id?.let { "$packageName:id/$it" },
            label = label ?: description ?: hint,
            bounds = IntRect(left, top, right, top + height),
            actions = if (scrollable) setOf(ElementAction.SCROLL_FORWARD) else emptySet(),
            clickable = clickable,
            editable = editable,
            checkable = checkable,
            checked = checked,
            scrollable = scrollable,
            focused = focused,
            password = password,
            scrollAxis = axis,
        )
        return key
    }

    /** A window above the app's, such as the keyboard (layer 2) or a permission dialog. */
    fun window(id: Int, packageName: String, bounds: IntRect, kind: WindowKind = WindowKind.APPLICATION, layer: Int = 2, active: Boolean = false) {
        if (active) windows.replaceAll { it.copy(active = false) }
        windows += WindowSummary(id, kind, packageName, null, bounds, layer, active)
    }

    fun index(key: String): Int = elements.indexOfFirst { it.key == key }

    fun snapshot(): ScreenSnapshot = ScreenSnapshot(0, windows.toList(), elements.toList())
}
