package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    @Test
    fun tapUsesTheElementsClickAction() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" }

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.tap(send.key))
        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun tapOnTextInsideARowClicksTheRow() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val name = screen.awaitElement { it.label == "Ana Petrović" }

        assertEquals(ActionResult.Done(ActionMethod.PARENT_ACTION), actions.tap(name.key))
        screen.awaitElement { it.label == "Clicked contact row" }
        Unit
    }

    @Test
    fun tapUsesAGestureWhenNothingOffersAClick() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val target = screen.awaitElement { it.label == "Touch target" }

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.tap(target.key))
        screen.awaitElement { it.label == "Touched target" }
        Unit
    }

    @Test
    fun tapFindsTheElementAgainWhenItsNodeHasGone() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val seen = screen.session.snapshots.current()
        val send = seen.snapshot.elements.single { it.label == "SEND" }
        actions.tap(seen.snapshot.elements.single { it.label == "REBUILD" }.key)
        screen.awaitElement { it.label == "Rebuilt" }

        assertEquals(ActionResult.Done(ActionMethod.RETRY_ACTION), actions.tap(send.key, seen))
        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun longPressUsesTheElementsLongClickAction() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" }

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.longPress(send.key))
        screen.awaitElement { it.label == "Long-pressed Send" }
        Unit
    }

    @Test
    fun anUnknownKeyFails() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertTrue(actions.tap("no such key") is ActionResult.Failed)
    }
}
