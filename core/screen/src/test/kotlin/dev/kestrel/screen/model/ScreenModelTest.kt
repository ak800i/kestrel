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
}
