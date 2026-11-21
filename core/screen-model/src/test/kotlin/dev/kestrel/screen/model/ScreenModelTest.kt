package dev.kestrel.screen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    private val bounds = IntRect(0, 0, 100, 50)

    @Test
    fun intersectReturnsTheOverlapOrNull() {
        assertEquals(IntRect(50, 20, 100, 50), bounds.intersect(IntRect(50, 20, 300, 300)))
        assertNull(bounds.intersect(IntRect(100, 0, 200, 50)))
    }

    @Test
    fun onlyEnabledElementsThatCanBeTappedTypedOrCheckedAreActionable() {
        val element = ScreenElement(key = "k", windowId = 1, bounds = bounds)

        assertFalse(element.isActionable)
        assertFalse(element.copy(scrollable = true).isActionable)
        assertTrue(element.copy(clickable = true).isActionable)
        assertTrue(element.copy(editable = true).isActionable)
        assertTrue(element.copy(checkable = true).isActionable)
        assertFalse(element.copy(clickable = true, enabled = false).isActionable)
    }

    @Test
    fun jsonLeavesOutDefaultsAndReadsBackTheSameSnapshot() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 42,
            windows = listOf(WindowSummary(id = 7, kind = WindowKind.APPLICATION, packageName = "dev.app", bounds = bounds)),
            elements = listOf(
                ScreenElement(
                    key = "dev.app|send|Send|Button|0",
                    windowId = 7,
                    label = "Send",
                    bounds = bounds,
                    actions = setOf(ElementAction.CLICK),
                    clickable = true,
                ),
            ),
        )

        val json = ScreenJson.encode(snapshot)

        assertFalse("defaults are left out: $json", json.contains("\"password\""))
        assertFalse("nulls are left out: $json", json.contains("null"))
        assertEquals(snapshot, ScreenJson.decode(json))
    }

    @Test
    fun aFingerReachesAnElementOutsideTheKeyboardThatNoWindowAboveCoversWhole() {
        val app = WindowSummary(id = 1, kind = WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 0)
        val dialog = WindowSummary(id = 2, kind = WindowKind.APPLICATION, bounds = IntRect(0, 800, 1080, 1400), layer = 1)
        val keyboard = WindowSummary(id = 3, kind = WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2)
        val snapshot = ScreenSnapshot(0, listOf(keyboard, dialog, app), emptyList())
        fun element(window: Int, top: Int) = ScreenElement(key = "k", windowId = window, bounds = IntRect(0, top, 1080, top + 100))

        assertTrue(snapshot.reachable(element(1, 100)))
        // Behind the dialog, whole and in part.
        assertFalse(snapshot.reachable(element(1, 900)))
        assertTrue(snapshot.reachable(element(1, 750)))
        assertTrue(snapshot.reachable(element(2, 900)))
        assertFalse(snapshot.reachable(element(3, 1700)))
        // An element of a window that the snapshot does not list has nothing above it.
        assertTrue(snapshot.reachable(element(9, 900)))

        assertEquals(listOf(keyboard.bounds, dialog.bounds), snapshot.windowsAbove(1))
        assertEquals(listOf(keyboard.bounds), snapshot.windowsAbove(2))
        assertEquals(emptyList<IntRect>(), snapshot.windowsAbove(9))
    }
}
