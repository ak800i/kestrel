package dev.kestrel.app.debug

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The goal notification's buttons in SystemUI's shade, for the hook goal-control (plan 7E). */
class NoticeButtonsTest {
    private val shade = WindowSummary(id = 1, kind = WindowKind.SYSTEM, packageName = SYSTEM_UI, bounds = IntRect(0, 0, 1080, 2400))

    private fun element(
        key: String,
        parent: Int?,
        text: String? = null,
        clickable: Boolean = false,
        actions: Set<ElementAction> = emptySet(),
        packageName: String = SYSTEM_UI,
    ) = ScreenElement(key = key, windowId = 1, parent = parent, packageName = packageName, text = text, bounds = IntRect(0, 0, 10, 10), actions = actions, clickable = clickable)

    @Test
    fun theButtonIsTheOneInKestrelsRowNotAnotherAppsWithTheSameLabel() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                element("shade", null),
                element("row-other", 0, actions = setOf(ElementAction.COLLAPSE)),
                element("other-title", 1, text = "Download paused"),
                element("other-continue", 1, text = "Continue", clickable = true),
                element("row-kestrel", 0, actions = setOf(ElementAction.COLLAPSE)),
                element("kestrel-title", 4, text = "Continue the goal?"),
                // Some phones write a notification's buttons in capitals.
                element("kestrel-continue", 4, text = "CONTINUE", clickable = true),
            ),
        )

        assertEquals("kestrel-continue", NoticeButtons.button(snapshot, "Continue the goal?", "Continue"))
        assertNull(NoticeButtons.collapsedRow(snapshot, "Continue the goal?"))
        assertNull(NoticeButtons.button(snapshot, "Kestrel is working", "Stop"))
        // The press checks the element again: SystemUI's clickable element with the label, nothing else.
        assertTrue(NoticeButtons.isButton(snapshot.elements[6], "Continue"))
        assertFalse(NoticeButtons.isButton(snapshot.elements[5], "Continue"))
        assertFalse(NoticeButtons.isButton(element("app-continue", null, text = "Continue", clickable = true, packageName = "com.example"), "Continue"))
    }

    @Test
    fun aCollapsedRowIsNamedForExpandingAndShowsNoButtonYet() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                element("shade", null),
                element("row-kestrel", 0, actions = setOf(ElementAction.EXPAND)),
                element("kestrel-title", 1, text = "Kestrel is working"),
            ),
        )

        assertNull(NoticeButtons.button(snapshot, "Kestrel is working", "Stop"))
        assertEquals("row-kestrel", NoticeButtons.collapsedRow(snapshot, "Kestrel is working"))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
