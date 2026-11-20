package dev.kestrel.app.debug

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hook H3 of plan 4D: which elements match, and an answer of counts, numbers, bounds and hashes only. */
class ScreenQueryTest {
    private val settings = "com.android.settings"
    private val app = WindowSummary(1, WindowKind.APPLICATION, packageName = settings, title = "Display", bounds = IntRect(0, 0, 1080, 2400), active = true)
    private val keyboard = WindowSummary(
        2, WindowKind.INPUT_METHOD, packageName = "com.samsung.android.honeyboard", bounds = IntRect(0, 1600, 1080, 2400), layer = 2,
    )
    private val elements = listOf(
        ScreenElement(
            key = "list", windowId = 1, packageName = settings, className = "androidx.recyclerview.widget.RecyclerView",
            viewId = "$settings:id/recycler_view", bounds = IntRect(0, 200, 1080, 1600), scrollable = true,
        ),
        ScreenElement(
            key = "row", windowId = 1, parent = 0, packageName = settings, className = "android.widget.LinearLayout",
            label = "Brightness level", bounds = IntRect(0, 200, 1080, 400), clickable = true,
        ),
        ScreenElement(
            key = "slider", windowId = 1, parent = 0, packageName = settings, className = "android.widget.SeekBar",
            bounds = IntRect(0, 400, 1080, 500), slider = true,
        ),
        ScreenElement(
            key = "dark", windowId = 1, parent = 0, packageName = settings, className = "android.widget.Switch", label = "Dark mode",
            bounds = IntRect(0, 500, 1080, 600), checkable = true, checked = true, clickable = true,
        ),
        ScreenElement(
            key = "key-q", windowId = 2, packageName = "com.samsung.android.honeyboard", label = "q", bounds = IntRect(0, 1700, 100, 1800),
            clickable = true,
        ),
    )
    private val snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements)

    private fun keys(filter: ScreenQuery.Filter) = ScreenQuery.matches(snapshot, filter).map { it.key }

    @Test
    fun aFilterMatchesByPackageViewIdNameClassAndStateAndNeverInTheKeyboard() {
        assertEquals(listOf("list", "row", "slider", "dark"), keys(ScreenQuery.Filter(packageName = settings)))
        assertEquals(listOf("list"), keys(ScreenQuery.Filter(viewId = "recycler")))
        assertEquals(listOf("dark"), keys(ScreenQuery.Filter(label = "dark MODE.")))
        assertEquals(listOf("row"), keys(ScreenQuery.Filter(contains = "brightness")))
        assertEquals(emptyList<String>(), keys(ScreenQuery.Filter(contains = "level brightness")))
        assertEquals(listOf("slider"), keys(ScreenQuery.Filter(className = "SeekBar")))
        assertEquals(listOf("dark"), keys(ScreenQuery.Filter(states = setOf("checked", "clickable"))))
        assertEquals(listOf("slider"), keys(ScreenQuery.Filter(states = setOf("slider"))))
        assertEquals(emptyList<String>(), keys(ScreenQuery.Filter(label = "q")))
    }

    @Test
    fun theAnswerHoldsTheFirstMatchsNumberBoundsAndInnerNameAsAHashAndThePageInFront() {
        val badges = listOf(Badge(3, "slider", elements[2].bounds), Badge(4, "dark", elements[3].bounds))
        val page = "app=$settings appTitle=${CommandTags.hash("Display")} windows=2"

        assertEquals(
            "count=1 number=none bounds=0,200,1080,1600 first=${CommandTags.hash("Brightness level")} $page",
            ScreenQuery.answer(snapshot, badges, ScreenQuery.Filter(states = setOf("scrollable"))),
        )
        assertEquals(
            "count=1 number=3 bounds=0,400,1080,500 first=none $page",
            ScreenQuery.answer(snapshot, badges, ScreenQuery.Filter(className = "SeekBar")),
        )
        assertEquals(
            "count=0 number=none bounds=none first=none $page",
            ScreenQuery.answer(snapshot, badges, ScreenQuery.Filter(label = "Bluetooth")),
        )
    }

    @Test
    fun aTemplatesLabelIsTheFirstNameOfAnElementAndTheStatesAreAFixedList() {
        assertEquals("Dark mode", ScreenQuery.firstName(elements[3]))
        assertNull(ScreenQuery.firstName(elements[2]))
        assertEquals(setOf("checked", "slider"), ScreenQuery.states("checked, slider"))
        assertEquals(emptySet<String>(), ScreenQuery.states(null))
        assertTrue(runCatching { ScreenQuery.states("shiny") }.exceptionOrNull() is IllegalArgumentException)
    }
}
