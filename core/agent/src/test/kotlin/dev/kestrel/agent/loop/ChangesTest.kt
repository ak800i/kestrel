package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.TestTree
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design D6 of sub-project 5: the result of an action by code, over the app's windows only. */
class ChangesTest {
    /**
     * A chat named [title] in its toolbar, a list of [rows] and a quantity [count] beside a "+" button at [bottom]; with
     * the keyboard up, the app moves that row above the keyboard and its window keeps its full height.
     */
    private fun chat(
        title: String = "Ana",
        rows: List<String> = listOf("Hi", "See you"),
        count: String = "1",
        packageName: String = "com.example.chat",
        bottom: Int = 2200,
    ) = TestTree(packageName).apply {
        val bar = add(80, id = "toolbar", className = "android.view.ViewGroup", height = 160)
        add(100, title, parent = bar)
        val list = add(300, description = "Messages", scrollable = true, height = bottom - 400, key = "list")
        rows.forEachIndexed { index, row -> add(320 + index * 100, row, parent = list, key = "row$index") }
        add(bottom, "+", clickable = true, className = "android.widget.Button", key = "plus", right = 200)
        add(bottom, count, left = 200, key = "count")
    }

    @Test
    fun anotherAppAnotherShapeOrAnotherTitleIsAnotherScreen() {
        val before = chat().snapshot()
        val list = TestTree().apply {
            add(80, "Chats", id = "toolbar", height = 160)
            add(400, "Ana", clickable = true, key = "a")
            add(500, "Marko", clickable = true, key = "b")
            add(600, "Petar", clickable = true, key = "c")
        }

        assertEquals(Outcome.AnotherScreen(null), Changes.outcome(before, chat(packageName = "com.example.other").snapshot(), "plus"))
        assertEquals(Outcome.AnotherScreen(null), Changes.outcome(before, list.snapshot(), "plus"))
        assertEquals(Outcome.AnotherScreen(null), Changes.outcome(before, chat(title = "Marko").snapshot(), "plus"))
        // The same parts in a new window of the app: another screen within one step (review 1-2 N1).
        val moved = before.copy(windows = before.windows.map { it.copy(id = 9) }, elements = before.elements.map { it.copy(windowId = 9) })
        assertEquals(Outcome.AnotherScreen(null), Changes.outcome(before, moved, "plus"))
    }

    @Test
    fun aScrollWhoseFirstRowChangesOnAScreenWithoutAToolbarTitleChangesTheTarget() {
        fun page(rows: List<String>) = TestTree().apply {
            val list = add(0, description = "Settings", scrollable = true, height = 2400, key = "list")
            rows.forEachIndexed { index, row -> add(10 + index * 100, row, parent = list, key = "row$index") }
        }

        assertEquals(Outcome.TargetChanged, Changes.outcome(page(listOf("Wi-Fi", "Bluetooth")).snapshot(), page(listOf("Display", "Sound")).snapshot(), "list"))
    }

    @Test
    fun theTargetTheRestOfTheScreenOrNothing() {
        val before = chat().snapshot()

        assertEquals(Outcome.ChangedElsewhere, Changes.outcome(before, chat(count = "2").snapshot(), "plus"))
        assertEquals(Outcome.NothingChanged, Changes.outcome(before, chat().snapshot(), "plus"))
        assertEquals(Outcome.TargetChanged, Changes.outcome(before, chat(rows = listOf("Hi", "See you", "Bye")).snapshot(), "list"))
        assertEquals(Outcome.TargetChanged, Changes.outcome(before, chat(rows = listOf("Hi")).snapshot(), "row1"))
        assertEquals(Outcome.ChangedElsewhere, Changes.outcome(before, chat(count = "2").snapshot(), null))
    }

    @Test
    fun theKeyboardAndSystemUiNeverChangeAResult() {
        val before = chat().apply {
            window(5, "com.android.systemui", IntRect(0, 0, 1080, 80), kind = WindowKind.SYSTEM)
            add(10, "10:20", window = 5, key = "clock")
        }.snapshot()
        // The keyboard comes up and the app moves its bottom row just above it (review 3 I3).
        val after = chat(bottom = 1400).apply {
            window(5, "com.android.systemui", IntRect(0, 0, 1080, 80), kind = WindowKind.SYSTEM)
            add(10, "10:21", window = 5, key = "clock")
            window(6, "com.google.android.inputmethod.latin", IntRect(0, 1500, 1080, 2400), kind = WindowKind.INPUT_METHOD)
            add(1600, "q", clickable = true, window = 6, key = "q")
        }.snapshot()

        assertEquals(Outcome.NothingChanged, Changes.outcome(before, after, "plus"))
    }

    @Test
    fun aQuickSettingsTileThatTheActionFlippedChangedAndAHiddenWindowNeverCounts() {
        fun shade(checked: Boolean) = chat().apply {
            window(5, "com.android.systemui", IntRect(0, 0, 1080, 1600), kind = WindowKind.SYSTEM)
            add(100, description = "Hotspot", id = "tile", clickable = true, checkable = true, checked = checked, className = "android.widget.Switch", window = 5, key = "tile")
        }.snapshot()
        fun withBank(balance: String) = chat().apply {
            window(7, "com.example.bank", IntRect(0, 1800, 1080, 2400))
            add(1900, balance, window = 7, key = "balance")
        }.snapshot()

        assertEquals(Outcome.TargetChanged, Changes.outcome(shade(false), shade(true), "tile"))
        assertEquals(Outcome.NothingChanged, Changes.outcome(withBank("100"), withBank("90"), "plus", hidden = { it == "com.example.bank" }))
    }
}
