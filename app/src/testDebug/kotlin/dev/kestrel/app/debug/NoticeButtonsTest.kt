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

    @Test
    fun aCollapsedGroupOfKestrelsNotificationsIsExpandedBeforeTheNotificationsOwnRow() {
        // Android groups an app's notifications by itself (on Android 17 from the second one): while the group is collapsed,
        // its rows show one line each, and an expanded row in it still shows no buttons.
        fun shade(group: ElementAction, kestrel: ElementAction, buttons: Boolean) = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOfNotNull(
                element("group", null, actions = setOf(group)),
                element("row-kestrel", 0, actions = setOf(kestrel)),
                element("kestrel-title", 1, text = "Continue the goal?"),
                element("row-listening", 0, actions = setOf(ElementAction.EXPAND)),
                element("listening-title", 3, text = "Kestrel is listening"),
                element("kestrel-continue", 1, text = "Continue", clickable = true).takeIf { buttons },
            ),
        )

        val closed = shade(ElementAction.EXPAND, ElementAction.EXPAND, buttons = false)
        assertNull(NoticeButtons.button(closed, "Continue the goal?", "Continue"))
        assertEquals("group", NoticeButtons.collapsedRow(closed, "Continue the goal?"))
        assertEquals("group", NoticeButtons.collapsedRow(shade(ElementAction.EXPAND, ElementAction.COLLAPSE, buttons = false), "Continue the goal?"))
        assertEquals("row-kestrel", NoticeButtons.collapsedRow(shade(ElementAction.COLLAPSE, ElementAction.EXPAND, buttons = false), "Continue the goal?"))
        val open = shade(ElementAction.COLLAPSE, ElementAction.COLLAPSE, buttons = true)
        assertNull(NoticeButtons.collapsedRow(open, "Continue the goal?"))
        assertEquals("kestrel-continue", NoticeButtons.button(open, "Continue the goal?", "Continue"))
    }

    @Test
    fun theShadeIsOpenWhileOneOfSystemUisWindowsIsActive() {
        val statusBar = WindowSummary(id = 2, kind = WindowKind.SYSTEM, packageName = SYSTEM_UI, bounds = IntRect(0, 0, 1080, 132))
        val app = WindowSummary(id = 3, kind = WindowKind.APPLICATION, packageName = "com.example", bounds = IntRect(0, 0, 1080, 2400), active = true)

        assertTrue(NoticeButtons.shadeOpen(ScreenSnapshot(takenAtMillis = 0, windows = listOf(shade.copy(active = true)), elements = emptyList())))
        assertFalse(NoticeButtons.shadeOpen(ScreenSnapshot(takenAtMillis = 0, windows = listOf(statusBar, app), elements = emptyList())))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
