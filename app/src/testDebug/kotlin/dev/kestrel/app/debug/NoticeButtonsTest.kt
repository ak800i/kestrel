package dev.kestrel.app.debug

import dev.kestrel.agent.observe.SystemUiParts
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.test.runTest
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

    // Kestrel's held goal in the group that Android put it in, with listening's notification: each row expanded or collapsed,
    // and Continue only while both are expanded, as SystemUI shows it.
    private fun grouped(group: ElementAction, kestrel: ElementAction) = ScreenSnapshot(
        takenAtMillis = 0,
        windows = listOf(shade),
        elements = listOfNotNull(
            row("group", null, group),
            row("row-kestrel", 0, kestrel),
            element("kestrel-title", 1, text = "Continue the goal?"),
            row("row-listening", 0, ElementAction.EXPAND),
            element("listening-title", 3, text = "Kestrel is listening"),
            element("kestrel-continue", 1, text = "Continue", clickable = true)
                .takeIf { group == ElementAction.COLLAPSE && kestrel == ElementAction.COLLAPSE },
        ),
    )

    // The search and press of the hook goal-control over [shown], one snapshot after another, with the rows that it asked to
    // expand; [refusal] is the press's answer, null when the press worked.
    private suspend fun find(vararg shown: ScreenSnapshot, refusal: String? = null): Pair<NoticeButtons.Tapped, List<String>> {
        val looks = shown.iterator()
        val asked = mutableListOf<String>()
        val tapped = buttons.findAndPress(
            "Continue the goal?",
            "Continue",
            tries = 5,
            look = { looks.next() },
            expand = { asked += it },
            press = { key ->
                pressed += key
                refusal
            },
        )
        return tapped to asked
    }

    private val pressed = mutableListOf<String>()

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
        assertEquals(NoticeButtons.Collapsed("row-kestrel", ShadeExpansion.ROW), buttons.collapsedRow(snapshot, "Kestrel is working"))
    }

    @Test
    fun aCollapsedGroupOfKestrelsNotificationsIsExpandedBeforeTheNotificationsOwnRow() {
        // While Android's group is collapsed, its rows show one line each, and an expanded row in it still shows no buttons.
        val closed = grouped(ElementAction.EXPAND, ElementAction.EXPAND)
        assertNull(buttons.button(closed, "Continue the goal?", "Continue"))
        assertEquals(NoticeButtons.Collapsed("group", ShadeExpansion.GROUP), buttons.collapsedRow(closed, "Continue the goal?"))
        assertEquals(
            NoticeButtons.Collapsed("group", ShadeExpansion.GROUP),
            buttons.collapsedRow(grouped(ElementAction.EXPAND, ElementAction.COLLAPSE), "Continue the goal?"),
        )
        assertEquals(
            NoticeButtons.Collapsed("row-kestrel", ShadeExpansion.ROW),
            buttons.collapsedRow(grouped(ElementAction.COLLAPSE, ElementAction.EXPAND), "Continue the goal?"),
        )
        val open = grouped(ElementAction.COLLAPSE, ElementAction.COLLAPSE)
        assertNull(buttons.collapsedRow(open, "Continue the goal?"))
        assertEquals("kestrel-continue", buttons.button(open, "Continue the goal?", "Continue"))
    }

    @Test
    fun aRowThatShowsItsButtonsAtOnceIsPressedWithoutAnyExpansion() = runTest {
        val (found, asked) = find(grouped(ElementAction.COLLAPSE, ElementAction.COLLAPSE))

        assertEquals(NoticeButtons.Tapped.Pressed(emptyList()), found)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun aPressThatWorksIsPressedAndAPressThatIsRefusedSaysWhyAndAButtonNeverShownIsNeverPressed() = runTest {
        val open = grouped(ElementAction.COLLAPSE, ElementAction.COLLAPSE)

        // The press's null is its success, never "not in the shade" (plan 7E, Task 8's Step 3 on d3f679e6).
        assertEquals(NoticeButtons.Tapped.Pressed(emptyList()), find(open).first)
        assertEquals(listOf("kestrel-continue"), pressed)
        assertEquals(NoticeButtons.Tapped.Refused("It's gone.", emptyList()), find(open, refusal = "It's gone.").first)
        pressed.clear()
        val collapsed = grouped(ElementAction.COLLAPSE, ElementAction.EXPAND)
        assertEquals(NoticeButtons.Tapped.NotShown(listOf(ShadeExpansion.ROW)), find(collapsed, collapsed, collapsed, collapsed, collapsed).first)
        assertEquals(emptyList<String>(), pressed)
    }

    @Test
    fun aCollapsedRowIsExpandedAndNamedOnceAlsoWhenSystemUiKeepsItCollapsed() = runTest {
        val collapsed = grouped(ElementAction.COLLAPSE, ElementAction.EXPAND)

        val (found, asked) = find(collapsed, grouped(ElementAction.COLLAPSE, ElementAction.COLLAPSE))

        assertEquals(NoticeButtons.Tapped.Pressed(listOf(ShadeExpansion.ROW)), found)
        assertEquals(listOf("row-kestrel"), asked)
        // Each look asks again, and the answer names the row once.
        val (never, askedEach) = find(collapsed, collapsed, collapsed, collapsed, collapsed)
        assertEquals(NoticeButtons.Tapped.NotShown(listOf(ShadeExpansion.ROW)), never)
        assertEquals(List(5) { "row-kestrel" }, askedEach)
    }

    @Test
    fun aCollapsedGroupAroundAnExpandedRowIsTheOnlyExpansion() = runTest {
        val (found, asked) = find(grouped(ElementAction.EXPAND, ElementAction.COLLAPSE), grouped(ElementAction.COLLAPSE, ElementAction.COLLAPSE))

        assertEquals(NoticeButtons.Tapped.Pressed(listOf(ShadeExpansion.GROUP)), found)
        assertEquals(listOf("group"), asked)
    }

    @Test
    fun aCollapsedGroupAndRowAreExpandedTheGroupFirst() = runTest {
        val (found, asked) = find(
            grouped(ElementAction.EXPAND, ElementAction.EXPAND),
            grouped(ElementAction.COLLAPSE, ElementAction.EXPAND),
            grouped(ElementAction.COLLAPSE, ElementAction.COLLAPSE),
        )

        assertEquals(NoticeButtons.Tapped.Pressed(listOf(ShadeExpansion.GROUP, ShadeExpansion.ROW)), found)
        assertEquals(listOf("group", "row-kestrel"), asked)
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
