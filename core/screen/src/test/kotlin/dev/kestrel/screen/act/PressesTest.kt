package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressesTest {
    private fun element(key: String, window: Int, text: String) = ScreenElement(key = key, windowId = window, text = text, label = text, bounds = IntRect(0, 0, 10, 10))

    private fun snapshot(at: Long, vararg elements: ScreenElement) = ScreenSnapshot(at, emptyList(), elements.toList())

    @Test
    fun theWindowIsUnchangedWhenItsElementsAreTheSameWhateverTheTimeAndTheOtherWindows() {
        val before = snapshot(1, element("send", 1, "Send"), element("status", 1, "Ready"), element("clock", 2, "10:20"))
        val after = snapshot(2, element("send", 1, "Send"), element("status", 1, "Ready"), element("clock", 2, "10:21"))

        assertTrue(Presses.unchanged(before, after, 1))
    }

    @Test
    fun aChangedTextOrAnElementThatCameOrWentIsAChange() {
        val before = snapshot(1, element("send", 1, "Send"), element("status", 1, "Ready"))

        assertFalse(Presses.unchanged(before, snapshot(2, element("send", 1, "Send"), element("status", 1, "Pressed 1")), 1))
        assertFalse(Presses.unchanged(before, snapshot(2, element("send", 1, "Send")), 1))
        assertFalse(Presses.unchanged(before, snapshot(2, element("send", 1, "Send"), element("status", 1, "Ready"), element("new", 1, "Sent")), 1))
    }
}
