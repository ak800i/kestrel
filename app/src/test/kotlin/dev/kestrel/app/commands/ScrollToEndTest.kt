package dev.kestrel.app.commands

import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollToEndTest {
    @Test
    fun scrollToTheEndStopsOnceTheNamedListHasGone() = runTest {
        val done = ActionResult.Done(ActionMethod.ACTION)
        var scrolls = 0

        // The content changes with every scroll, so only the check of the list ends the loop before its 30 scrolls.
        val result = ScrollToEnd.scroll(scroll = { ++scrolls; done }, content = { scrolls }, stillThere = { scrolls < 3 })

        assertEquals(done, result)
        assertEquals(3, scrolls)
    }
}
