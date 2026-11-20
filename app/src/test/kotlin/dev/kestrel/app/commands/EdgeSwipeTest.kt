package dev.kestrel.app.commands

import dev.kestrel.commands.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A swipe from an edge while the navigation bar has buttons (inv-oneui-a71, 5). */
class EdgeSwipeTest {
    @Test
    fun withButtonsASwipeFromASideOrTheBottomThatChangedNothingSaysWhatToSayInstead() {
        assertEquals(CommandTexts.EDGE_BUTTONS_BACK, edgeSwipeHint(Edge.LEFT, buttons = true, changed = false))
        assertEquals(CommandTexts.EDGE_BUTTONS_BACK, edgeSwipeHint(Edge.RIGHT, buttons = true, changed = false))
        assertEquals(CommandTexts.EDGE_BUTTONS_HOME, edgeSwipeHint(Edge.BOTTOM, buttons = true, changed = false))
        // The shade comes from the top in every mode, a swipe that changed the screen did something, and gestures go back.
        assertNull(edgeSwipeHint(Edge.TOP, buttons = true, changed = false))
        assertNull(edgeSwipeHint(Edge.LEFT, buttons = true, changed = true))
        assertNull(edgeSwipeHint(Edge.LEFT, buttons = false, changed = false))
        assertNull(edgeSwipeHint(Edge.LEFT, buttons = null, changed = false))
        // Android's navigation modes: 0 three buttons, 1 two buttons, 2 gestures.
        assertEquals(true, navigationButtons(0))
        assertEquals(true, navigationButtons(1))
        assertEquals(false, navigationButtons(2))
        assertNull(navigationButtons(null))
        assertNull(navigationButtons(7))
    }
}
