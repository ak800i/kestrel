package dev.kestrel.app.debug

import dev.kestrel.agent.observe.SystemUiParts
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
    private val buttons = NoticeButtons(SystemUiParts(setOf(TILE), emptySet(), setOf(ROW), emptySet()))
    private val shade = WindowSummary(id = 1, kind = WindowKind.SYSTEM, packageName = SYSTEM_UI, bounds = IntRect(0, 0, 1080, 2400))

    private fun element(
        key: String,
        parent: Int?,
        text: String? = null,
        clickable: Boolean = false,
        actions: Set<ElementAction> = emptySet(),
        packageName: String = SYSTEM_UI,
        viewId: String? = null,
    ) = ScreenElement(
        key = key,
        windowId = 1,
        parent = parent,
        packageName = packageName,
        text = text,
        viewId = viewId?.let { "$SYSTEM_UI:id/$it" },
        bounds = IntRect(0, 0, 10, 10),
        actions = actions,
        clickable = clickable,
    )

    private fun row(key: String, parent: Int?, action: ElementAction) = element(key, parent, actions = setOf(action), viewId = ROW)

    @Test
    fun theButtonIsTheOneInKestrelsRowNotAnotherAppsWithTheSameLabel() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                element("shade", null),
                row("row-other", 0, ElementAction.COLLAPSE),
                element("other-title", 1, text = "Download paused"),
                element("other-continue", 1, text = "Continue", clickable = true),
                row("row-kestrel", 0, ElementAction.COLLAPSE),
                element("kestrel-title", 4, text = "Continue the goal?"),
                // Some phones write a notification's buttons in capitals.
                element("kestrel-continue", 4, text = "CONTINUE", clickable = true),
            ),
        )

        assertEquals("kestrel-continue", buttons.button(snapshot, "Continue the goal?", "Continue"))
        assertNull(buttons.collapsedRow(snapshot, "Continue the goal?"))
        assertNull(buttons.button(snapshot, "Kestrel is working", "Stop"))
        // The press checks the element again: SystemUI's clickable element with the label, nothing else.
        assertTrue(buttons.isButton(snapshot.elements[6], "Continue"))
        assertFalse(buttons.isButton(snapshot.elements[5], "Continue"))
        assertFalse(buttons.isButton(element("app-continue", null, text = "Continue", clickable = true, packageName = "com.example"), "Continue"))
    }

    @Test
    fun aCollapsedRowIsNamedForExpandingAndShowsNoButtonYet() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                element("shade", null),
                row("row-kestrel", 0, ElementAction.EXPAND),
                element("kestrel-title", 1, text = "Kestrel is working"),
            ),
        )

        assertNull(buttons.button(snapshot, "Kestrel is working", "Stop"))
        assertEquals("row-kestrel", buttons.collapsedRow(snapshot, "Kestrel is working"))
    }

    @Test
    fun aCollapsedGroupOfKestrelsNotificationsIsExpandedBeforeTheNotificationsOwnRow() {
        // While Android's group is collapsed, its rows show one line each, and an expanded row in it still shows no buttons.
        fun shade(group: ElementAction, kestrel: ElementAction, buttons: Boolean) = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOfNotNull(
                row("group", null, group),
                row("row-kestrel", 0, kestrel),
                element("kestrel-title", 1, text = "Continue the goal?"),
                row("row-listening", 0, ElementAction.EXPAND),
                element("listening-title", 3, text = "Kestrel is listening"),
                element("kestrel-continue", 1, text = "Continue", clickable = true).takeIf { buttons },
            ),
        )

        val closed = shade(ElementAction.EXPAND, ElementAction.EXPAND, buttons = false)
        assertNull(buttons.button(closed, "Continue the goal?", "Continue"))
        assertEquals("group", buttons.collapsedRow(closed, "Continue the goal?"))
        assertEquals("group", buttons.collapsedRow(shade(ElementAction.EXPAND, ElementAction.COLLAPSE, buttons = false), "Continue the goal?"))
        assertEquals("row-kestrel", buttons.collapsedRow(shade(ElementAction.COLLAPSE, ElementAction.EXPAND, buttons = false), "Continue the goal?"))
        val open = shade(ElementAction.COLLAPSE, ElementAction.COLLAPSE, buttons = true)
        assertNull(buttons.collapsedRow(open, "Continue the goal?"))
        assertEquals("kestrel-continue", buttons.button(open, "Continue the goal?", "Continue"))
    }

    @Test
    fun aContainerOfSystemUisThatOffersExpandIsNeverARow() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                // Such as the panel or Quick Settings, which may offer expand on some phones.
                element("panel", null, actions = setOf(ElementAction.EXPAND)),
                row("row-kestrel", 0, ElementAction.COLLAPSE),
                element("kestrel-title", 1, text = "Continue the goal?"),
                element("kestrel-continue", 1, text = "Continue", clickable = true),
            ),
        )

        assertNull(buttons.collapsedRow(snapshot, "Continue the goal?"))
        assertEquals("kestrel-continue", buttons.button(snapshot, "Continue the goal?", "Continue"))
    }

    @Test
    fun theShadeIsOpenWhileOneOfSystemUisWindowsIsActiveAndNotTheLockScreenOrTheBouncer() {
        val statusBar = WindowSummary(id = 2, kind = WindowKind.SYSTEM, packageName = SYSTEM_UI, bounds = IntRect(0, 0, 1080, 132))
        val app = WindowSummary(id = 3, kind = WindowKind.APPLICATION, packageName = "com.example", bounds = IntRect(0, 0, 1080, 2400), active = true)
        val active = listOf(shade.copy(active = true))
        val tiles = listOf(element("tile", null, clickable = true, viewId = TILE))

        val bare = ScreenSnapshot(takenAtMillis = 0, windows = active, elements = emptyList())

        assertTrue(buttons.shadeOpen(bare, keyguardLocked = false, tilesKnown = true))
        assertFalse(buttons.shadeOpen(ScreenSnapshot(takenAtMillis = 0, windows = listOf(statusBar, app), elements = emptyList()), keyguardLocked = false, tilesKnown = true))
        // Locked: SystemUI's active window is the lock screen or the bouncer, unless it shows Quick Settings' tiles.
        assertFalse(buttons.shadeOpen(bare, keyguardLocked = true, tilesKnown = true))
        assertTrue(buttons.shadeOpen(ScreenSnapshot(takenAtMillis = 0, windows = active, elements = tiles), keyguardLocked = true, tilesKnown = true))
        assertTrue(buttons.showsTiles(ScreenSnapshot(takenAtMillis = 0, windows = active, elements = tiles)))
        assertFalse(buttons.showsTiles(bare))
    }

    @Test
    fun aLockedPhoneWhoseShadeShowedNoKnownTileCountsAsOpenBecauseTheShadeCannotBeToldFromTheLockScreen() {
        // As on the A71, whose tile IDs systemui-parts.json lacks: its open shade and its lock screen both show no tile
        // that Kestrel knows, so the answer is the safe one, after which a caller collapses the shade.
        val locked = ScreenSnapshot(takenAtMillis = 0, windows = listOf(shade.copy(active = true)), elements = emptyList())

        assertTrue(buttons.shadeOpen(locked, keyguardLocked = true, tilesKnown = false))
        // Without SystemUI's active window the shade is closed, also on such a phone.
        assertFalse(buttons.shadeOpen(locked.copy(windows = listOf(shade)), keyguardLocked = true, tilesKnown = false))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val ROW = "expandableNotificationRow"
        const val TILE = "qs_tile_large"
    }
}
