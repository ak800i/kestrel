package dev.kestrel.screen.model

/**
 * Builds small snapshots for the tests: one window of 1000 × 2000 pixels and its elements, in the order of [add]. An
 * element is 100 pixels high and as wide as the window unless the test says otherwise; its view ID is written as
 * `<package>:id/<id>` unless it already holds `:id/`. With [keyboardTop], a keyboard window covers the window from there
 * down.
 */
internal class TestScreen(
    private val packageName: String = "dev.example",
    private val windowTitle: String? = null,
    private val windowId: Int = 1,
    private val keyboardTop: Int? = null,
) {
    private val elements = mutableListOf<ScreenElement>()

    fun add(
        top: Int,
        text: String? = null,
        id: String? = null,
        parent: Int? = null,
        className: String = "android.widget.TextView",
        left: Int = 0,
        right: Int = 1000,
        height: Int = 100,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        description: String? = null,
        axis: ScrollAxis? = null,
        actions: Set<ElementAction> = emptySet(),
    ): Int {
        elements += ScreenElement(
            key = "$packageName|${elements.size}",
            windowId = windowId,
            parent = parent,
            packageName = packageName,
            className = className,
            text = text,
            contentDescription = description,
            viewId = id?.let { if (":id/" in it) it else "$packageName:id/$it" },
            label = text ?: description,
            bounds = IntRect(left, top, right, top + height),
            actions = actions,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            scrollAxis = axis,
        )
        return elements.lastIndex
    }

    fun window(): WindowSummary =
        WindowSummary(windowId, WindowKind.APPLICATION, packageName, windowTitle, IntRect(0, 0, 1000, 2000), active = true)

    fun snapshot(): ScreenSnapshot {
        val keyboard = keyboardTop?.let { WindowSummary(99, WindowKind.INPUT_METHOD, "com.example.keyboard", null, IntRect(0, it, 1000, 2000), layer = 2) }
        return ScreenSnapshot(0, listOfNotNull(keyboard, window()), elements.toList())
    }
}
