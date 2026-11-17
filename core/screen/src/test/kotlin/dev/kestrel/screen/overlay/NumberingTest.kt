package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberingTest {
    private val screen = IntRect(0, 0, 1080, 2400)

    private fun element(key: String, left: Int, top: Int, label: String? = key, window: Int = 1, clickable: Boolean = true) =
        ScreenElement(key = key, windowId = window, label = label, bounds = IntRect(left, top, left + 100, top + 50), clickable = clickable)

    private val snapshot = ScreenSnapshot(
        takenAtMillis = 0,
        windows = listOf(
            WindowSummary(id = 2, kind = WindowKind.INPUT_METHOD, bounds = screen),
            WindowSummary(id = 1, kind = WindowKind.APPLICATION, bounds = screen),
        ),
        elements = listOf(
            element("send", left = 600, top = 100),
            element("back", left = 0, top = 100),
            element("title", left = 0, top = 0, clickable = false),
            element("icon", left = 0, top = 300, label = null),
            element("key q", left = 0, top = 2000, window = 2),
        ),
    )

    @Test
    fun actionableElementsAreNumberedInReadingOrderWithoutTheKeyboard() {
        val badges = Numbering.forScreen(snapshot, NumbersMode.ALL)

        assertEquals(listOf(1 to "back", 2 to "send", 3 to "icon"), badges.map { it.number to it.key })
    }

    @Test
    fun unlabeledModeNumbersOnlyElementsWithoutAName() {
        assertEquals(listOf("icon"), Numbering.forScreen(snapshot, NumbersMode.UNLABELED).map { it.key })
    }

    @Test
    fun candidatesAreNumberedFromOne() {
        val badges = Numbering.forCandidates(snapshot.elements.filter { it.key in setOf("send", "back") })

        assertEquals(listOf(1 to "back", 2 to "send"), badges.map { it.number to it.key })
    }

    @Test
    fun aBadgeThatOnlyMovedKeepsItsNumbers() {
        val badges = Numbering.forScreen(snapshot, NumbersMode.ALL)
        val moved = badges.map { it.copy(bounds = IntRect(it.bounds.left, it.bounds.top + 40, it.bounds.right, it.bounds.bottom + 40)) }

        assertTrue(Numbering.sameNumbers(badges, moved))
        assertFalse(Numbering.sameNumbers(badges, moved.map { if (it.number == 2) it.copy(key = "icon") else it }))
        assertFalse(Numbering.sameNumbers(badges, badges.dropLast(1)))
    }
}
