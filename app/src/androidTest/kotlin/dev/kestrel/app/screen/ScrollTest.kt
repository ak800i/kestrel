package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ScrollDirection
import kotlinx.coroutines.runBlocking
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
    fun scrollSwipesWhenTheListOffersNoScrollActions() = runBlocking {
        screen.open("ScrollActivity", "Swipe-only scroll fixture", extras = "--ez swipeOnly true")

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.scroll(ScrollDirection.DOWN))
        firstRowIsHidden()
    }
}
