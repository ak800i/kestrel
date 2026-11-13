package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ScrollDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    private suspend fun firstRowIsHidden() {
        screen.awaitElement { it.label == "Row 25" }
        assertFalse(screen.session.snapshots.current().snapshot.elements.any { it.label == "Row 1" })
    }

    @Test
    fun scrollUsesTheListsScrollAction() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.DOWN))
        firstRowIsHidden()
    }

    @Test
    fun scrollOnARowScrollsTheListAroundIt() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")
        val row = screen.awaitElement { it.label == "Row 2" }

        assertEquals(ActionResult.Done(ActionMethod.PARENT_ACTION), actions.scroll(ScrollDirection.DOWN, row.key))
        firstRowIsHidden()
    }

    @Test
    fun scrollSwipesAcrossTheListWhenTheListOffersNoScrollActions() = runBlocking {
        screen.open("ScrollActivity", "Swipe-only scroll fixture", extras = "--ez swipeOnly true")

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.scroll(ScrollDirection.DOWN))
        // The list fills only the upper half of the page, so a swipe across the whole window would miss it.
        withTimeout(5_000) {
            while (screen.session.snapshots.current().snapshot.elements.any { it.label == "Row 1" }) delay(100)
        }
    }

    @Test
    fun scrollDownScrollsTheListInsideAPagerAndNotThePager() = runBlocking {
        screen.open("ScrollActivity", "Pager scroll fixture", extras = "--ez pages true")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.DOWN))
        firstRowIsHidden()
    }

    @Test
    fun scrollRightShowsThePagersNextPage() = runBlocking {
        screen.open("ScrollActivity", "Pager scroll fixture", extras = "--ez pages true")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.RIGHT))
        screen.awaitElement { it.label == "Second page row 1" }
        Unit
    }
}
