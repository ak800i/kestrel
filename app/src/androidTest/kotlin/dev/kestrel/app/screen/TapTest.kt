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
    fun aKeyFromAnOlderSnapshotTapsTheElementThatIsOnTheScreenNow() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val old = screen.session.snapshots.current().snapshot
        actions.tap(old.elements.single { it.label == "REBUILD" }.key)
        screen.awaitElement { it.label == "Rebuilt" }

        // Rebuild has replaced the Send button, so the node in the old snapshot has gone.
        // The rebuilt button is the same element; for repeated look-alikes, an older key can name another row (design spec, section 9.2).
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.tap(old.elements.single { it.label == "SEND" }.key))
        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun aDisabledElementIsNotTappedAndTheTapDoesNotReachItsParent() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val addToCart = screen.awaitElement { it.label == "ADD TO CART" }

        assertEquals(ActionResult.Failed("The element is disabled"), actions.tap(addToCart.key))
        assertTrue(screen.session.snapshots.current().snapshot.elements.any { it.label == "Ready" })
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
