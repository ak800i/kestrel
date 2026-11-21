package dev.kestrel.agent.observe

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary

/**
 * Builds the observation tests' snapshots: an app window of 1080 × 2400 pixels (ID 1, layer 1, active) and its elements
 * in the order of [add]. [add] returns the element's index, which [add]'s `parent` takes.
 */
internal class TestTree(val packageName: String = "com.example.chat", title: String? = null) {
    private val windows = mutableListOf(WindowSummary(1, WindowKind.APPLICATION, packageName, title, IntRect(0, 0, 1080, 2400), layer = 1, active = true))
    private val elements = mutableListOf<ScreenElement>()

    fun add(
        top: Int,
        text: String? = null,
        description: String? = null,
        hint: String? = null,
        id: String? = null,
        parent: Int? = null,
        className: String = "android.widget.TextView",
        left: Int = 0,
        right: Int = 1080,
        height: Int = 100,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        focused: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        scrollable: Boolean = false,
        actions: Set<ElementAction> = emptySet(),
        axis: ScrollAxis? = null,
        suggested: String? = null,
        window: Int = 1,
        key: String? = null,
    ): Int {
        val pkg = windows.first { it.id == window }.packageName
        elements += ScreenElement(
            key = key ?: "$pkg|${elements.size}",
            windowId = window,
            parent = parent,
            packageName = pkg,
            className = className,
            text = text,
            contentDescription = description,
            hint = hint,
            viewId = id?.let { "$pkg:id/$it" },
            label = text ?: description ?: hint,
            suggestedName = suggested,
            bounds = IntRect(left, top, right, top + height),
            actions = actions,
            clickable = clickable,
            editable = editable,
            checkable = checkable,
            checked = checked,
            scrollable = scrollable,
            focused = focused,
            password = password,
            scrollAxis = axis,
        )
        return elements.lastIndex
    }

    fun window(id: Int, packageName: String?, bounds: IntRect, kind: WindowKind = WindowKind.APPLICATION, layer: Int = 2, active: Boolean = false) {
        if (active) windows.replaceAll { it.copy(active = false) }
        windows.add(0, WindowSummary(id, kind, packageName, null, bounds, layer, active))
    }

    fun key(index: Int): String = elements[index].key

    fun snapshot(): ScreenSnapshot = ScreenSnapshot(0, windows.toList(), elements.toList())
}
