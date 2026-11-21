package dev.kestrel.screen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTreeTest {
    private val area = IntRect(0, 0, 900, 1800)

    @Test
    fun aRegionIsTheCentresCellInAThreeByThreeGrid() {
        assertEquals(Region.TOP_LEFT, Region.of(IntRect(0, 0, 100, 100), area))
        assertEquals(Region.MIDDLE, Region.of(IntRect(400, 850, 500, 950), area))
        assertEquals(Region.BOTTOM_RIGHT, Region.of(IntRect(800, 1700, 900, 1800), area))
        // A centre outside the area takes the nearest cell.
        assertEquals(Region.TOP, Region.of(IntRect(400, -300, 500, -100), area))
        assertEquals("bottom-left", Region.of(IntRect(0, 1700, 100, 1800), area).label)
    }

    @Test
    fun theFrontWindowIsTheActiveAppWindow() {
        val bounds = IntRect(0, 0, 100, 100)
        val keyboard = WindowSummary(3, WindowKind.INPUT_METHOD, "ime", bounds = bounds, active = false)
        val first = WindowSummary(1, WindowKind.APPLICATION, "a", bounds = bounds)
        val active = WindowSummary(2, WindowKind.APPLICATION, "b", bounds = bounds, active = true)

        assertEquals(active, ScreenTree.frontWindow(ScreenSnapshot(0, listOf(keyboard, first, active), emptyList())))
        assertEquals(first, ScreenTree.frontWindow(ScreenSnapshot(0, listOf(keyboard, first), emptyList())))
        assertNull(ScreenTree.frontWindow(ScreenSnapshot(0, listOf(keyboard), emptyList())))
    }

    @Test
    fun aVerticalListIsKnownByItsAxisItsActionsOrItsClass() {
        val screen = TestScreen()
        val vertical = screen.add(0, scrollable = true, axis = ScrollAxis.VERTICAL)
        val row = screen.add(100, text = "Row", parent = vertical)
        val pager = screen.add(200, scrollable = true, axis = ScrollAxis.HORIZONTAL)
        val page = screen.add(300, text = "Page", parent = pager)
        val down = screen.add(400, scrollable = true, actions = setOf(ElementAction.SCROLL_DOWN))
        val recycler = screen.add(500, scrollable = true, className = "androidx.recyclerview.widget.RecyclerView")
        val carousel = screen.add(600, scrollable = true, className = "android.widget.HorizontalScrollView")
        val snapshot = screen.snapshot()

        assertTrue(ScreenTree.insideVerticalList(snapshot, row))
        assertFalse(ScreenTree.insideVerticalList(snapshot, page))
        assertTrue(ScreenTree.insideScrollable(snapshot, page))
        assertTrue(ScreenTree.isVerticalList(snapshot.elements[down]))
        assertTrue(ScreenTree.isVerticalList(snapshot.elements[recycler]))
        assertFalse(ScreenTree.isVerticalList(snapshot.elements[carousel]))
        assertEquals(listOf(vertical), ScreenTree.ancestors(snapshot, row))
    }

    @Test
    fun aToolbarIsAViewIdThatHoldsToolbarOrActionBar() {
        val screen = TestScreen()
        val bar = screen.add(0, id = "action_bar_container")
        val title = screen.add(10, text = "Chats", parent = bar)
        val body = screen.add(500, text = "Body", id = "body")
        val snapshot = screen.snapshot()

        assertTrue(ScreenTree.inToolbar(snapshot, title))
        assertFalse(ScreenTree.inToolbar(snapshot, body))
        assertEquals("body", ScreenTree.idName(snapshot.elements[body].viewId))
        assertEquals("search_box", ScreenTree.idName("search_box"))
    }

    @Test
    fun theNewFieldsAreReadFromOldFilesAndKeptWhenSet() {
        val old = """{"takenAtMillis":1,"windows":[],"elements":[{"key":"k","windowId":1,"bounds":{"left":0,"top":0,"right":10,"bottom":10}}]}"""
        val element = ScreenJson.decode(old).elements.single()

        assertNull(element.tooltip)
        assertNull(element.scrollAxis)
        val set = ScreenSnapshot(1, emptyList(), listOf(element.copy(tooltip = "More options", scrollAxis = ScrollAxis.HORIZONTAL)))
        assertEquals(set, ScreenJson.decode(ScreenJson.encode(set)))
        assertFalse("tooltip" in ScreenJson.encode(ScreenSnapshot(1, emptyList(), listOf(element))))
    }

    @Test
    fun theModelPrintsOnlyCountsAndLengthsNeverScreenText() {
        val marker = "Secret483920"
        val element = ScreenElement(
            key = marker, windowId = 1, packageName = marker, className = marker, text = marker, contentDescription = marker, hint = marker,
            supplementalDescription = marker, stateDescription = marker, viewId = marker, label = marker, suggestedName = marker,
            bounds = IntRect(0, 0, 10, 10), tooltip = marker,
        )
        val window = WindowSummary(1, WindowKind.APPLICATION, marker, marker, IntRect(0, 0, 10, 10))
        val printed = listOf(element.toString(), window.toString(), ScreenSnapshot(1, listOf(window), listOf(element)).toString())

        assertTrue(printed.none { marker in it })
        assertEquals("ScreenSnapshot(windows=1, elements=1)", printed[2])
    }
}
