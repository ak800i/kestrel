package dev.kestrel.agent.testing

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary

/**
 * Builds a one-window snapshot for the JVM tests of `:core:agent` (design D27), with elements at given rows of a
 * 1080 × 2400 screen. Each element's key is `package|viewId|label|class|row`, which is unique within a snapshot, so a
 * test can find an element again by its label. Only the parts that a test names are set.
 */
class ScreenBuilder(private val packageName: String, private val windowTitle: String? = null, private val windowId: Int = 1) {
    private val elements = mutableListOf<ScreenElement>()

    /** A text without an action, at [top] (pixels); returns its index for [parent]. */
    fun text(label: String, top: Int, viewId: String? = null, parent: Int? = null, className: String = "android.widget.TextView"): Int =
        add(label, top, viewId, parent, className)

    fun button(
        label: String?,
        top: Int,
        viewId: String? = null,
        parent: Int? = null,
        className: String = "android.widget.Button",
        left: Int = 40,
        right: Int = 1040,
        suggestedName: String? = null,
    ): Int = add(label, top, viewId, parent, className, clickable = true, left = left, right = right, suggestedName = suggestedName,
        actions = setOf(ElementAction.CLICK))

    /** An icon button that only its content description names, such as "Navigate up". */
    fun icon(description: String, top: Int, viewId: String? = null, parent: Int? = null, left: Int = 40, right: Int = 140): Int =
        add(description, top, viewId, parent, "android.widget.ImageButton", clickable = true, left = left, right = right, description = description,
            actions = setOf(ElementAction.CLICK))

    fun field(hint: String, value: String?, top: Int, viewId: String? = null, focused: Boolean = false, password: Boolean = false): Int =
        add(
            label = value?.takeIf { it.isNotEmpty() } ?: hint, top = top, viewId = viewId, parent = null, className = "android.widget.EditText",
            editable = true, focused = focused, password = password, text = if (password) null else value, hint = hint,
            actions = setOf(ElementAction.CLICK, ElementAction.SET_TEXT),
        )

    fun switch(label: String, checked: Boolean, top: Int, viewId: String? = null): Int =
        add(label, top, viewId, null, "android.widget.Switch", clickable = true, checkable = true, checked = checked,
            actions = setOf(ElementAction.CLICK))

    fun list(label: String?, top: Int, bottom: Int, viewId: String? = null): Int =
        add(label, top, viewId, null, "androidx.recyclerview.widget.RecyclerView", scrollable = true, bottom = bottom,
            scrollAxis = ScrollAxis.VERTICAL, actions = setOf(ElementAction.SCROLL_FORWARD, ElementAction.SCROLL_BACKWARD))

    /** A clickable row of a list; [checked] makes it checkable, as a picker's row is once it is picked. */
    fun row(label: String, top: Int, parent: Int, viewId: String? = null, checked: Boolean? = null): Int =
        add(label, top, viewId, parent, "android.view.ViewGroup", clickable = true, checkable = checked != null, checked = checked == true,
            actions = setOf(ElementAction.CLICK))

    /** A container without a label or an action, such as a toolbar, from [top] to [bottom]. */
    fun group(viewId: String, top: Int, bottom: Int, parent: Int? = null, className: String = "android.view.ViewGroup"): Int =
        add(null, top, viewId, parent, className, bottom = bottom)

    /** A picture without a label. */
    fun image(top: Int, viewId: String? = null, parent: Int? = null): Int = add(null, top, viewId, parent, "android.widget.ImageView")

    private fun add(
        label: String?,
        top: Int,
        viewId: String?,
        parent: Int?,
        className: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        password: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        scrollable: Boolean = false,
        text: String? = label,
        hint: String? = null,
        description: String? = null,
        left: Int = 40,
        right: Int = 1040,
        bottom: Int = top + ROW,
        suggestedName: String? = null,
        scrollAxis: ScrollAxis? = null,
        actions: Set<ElementAction> = emptySet(),
    ): Int {
        val id = viewId?.let { "$packageName:id/$it" }
        elements += ScreenElement(
            key = "$packageName|${viewId.orEmpty()}|${label.orEmpty().take(40)}|$className|$top",
            windowId = windowId,
            parent = parent,
            packageName = packageName,
            className = className,
            text = if (editable) text else label.takeIf { description == null },
            contentDescription = description,
            hint = hint,
            viewId = id,
            label = label,
            suggestedName = suggestedName,
            bounds = IntRect(left, top, right, bottom),
            actions = actions,
            clickable = clickable,
            editable = editable,
            checkable = checkable,
            checked = checked,
            scrollable = scrollable,
            focused = focused,
            password = password,
            scrollAxis = scrollAxis,
        )
        return elements.size - 1
    }

    fun build(takenAtMillis: Long = 0): ScreenSnapshot = ScreenSnapshot(
        takenAtMillis = takenAtMillis,
        windows = listOf(WindowSummary(windowId, WindowKind.APPLICATION, packageName, windowTitle, IntRect(0, 0, WIDTH, HEIGHT), active = true, focused = true)),
        elements = elements.toList(),
    )

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
        const val ROW = 120

        fun screen(packageName: String, windowTitle: String? = null, build: ScreenBuilder.() -> Unit): ScreenSnapshot =
            ScreenBuilder(packageName, windowTitle).apply(build).build()
    }
}
