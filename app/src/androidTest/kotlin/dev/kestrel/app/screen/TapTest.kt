package dev.kestrel.app.screen

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.PressDispatch
import dev.kestrel.screen.act.PressPolicy
import dev.kestrel.screen.overlay.CardQuestion
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

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
    fun aKeyThatNamesAnotherElementThanTheCallerExpectedTapsNothing() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" }

        // As when a look-alike of another row has taken the key since the caller's check (design spec, section 9.2).
        assertEquals(ActionResult.CHANGED, actions.tap(send.key, expected = { it.label != "SEND" }))
        assertEquals(ActionResult.CHANGED, actions.longPress(send.key, expected = { it.label != "SEND" }))
        assertTrue(screen.session.snapshots.current().snapshot.elements.any { it.label == "Ready" })
    }

    @Test
    fun aDisabledElementIsNotTappedAndTheTapDoesNotReachItsParent() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val addToCart = screen.awaitElement { it.label == "ADD TO CART" }

        assertEquals(ActionResult.Failed("It's disabled."), actions.tap(addToCart.key))
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
    fun aRefusedClickThatActedIsPressedOnceAndUncertain() = runBlocking {
        for (policy in PressPolicy.entries) {
            screen.open("PressActivity", "Press fixture")
            val stubborn = screen.awaitElement { it.label == "STUBBORN" }

            // The app clicked but answered no: a second press could send twice (design D32 item 29 of sub-project 5).
            assertEquals(policy.name, ActionResult.UNCERTAIN, actions.tap(stubborn.key, policy = policy))
            screen.awaitElement { it.label == "Pressed 1" }
            delay(1_000)
            assertTrue(policy.name, screen.session.snapshots.current().snapshot.elements.any { it.label == "Pressed 1" })
        }
    }

    @Test
    fun aRefusedClickThatDidNothingIsPressedOnceMoreByAGestureOnlyWhenVerified() = runBlocking {
        screen.open("PressActivity", "Press fixture")

        assertEquals(ActionResult.UNCERTAIN, actions.tap(screen.awaitElement { it.label == "DEAF" }.key, policy = PressPolicy.ONCE))
        delay(500)
        assertTrue(screen.session.snapshots.current().snapshot.elements.any { it.label == "Ready" })

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.tap(screen.awaitElement { it.label == "DEAF" }.key))
        screen.awaitElement { it.label == "Pressed 1" }
        Unit
    }

    @Test
    fun anUnknownKeyFails() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertTrue(actions.tap("no such key") is ActionResult.Failed)
    }

    // Re-check of round 12, Minor C: a press tells its [PressDispatch] once for each hand-over to Android, the node's
    // action, the fallback gesture, or an action that answered no; a press that a check or the card stops tells it nothing.
    @Test
    fun aPressTellsItsDispatchOnlyWhenItGoesToAndroid() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" }
        val refusals = CountedPress()
        assertEquals(ActionResult.CHANGED, actions.tap(send.key, expected = { it.label != "SEND" }, dispatch = refusals))
        assertEquals(ActionResult.CHANGED, actions.tap(send.key, guard = { false }, dispatch = refusals))
        assertTrue(actions.tap("no such key", dispatch = refusals) is ActionResult.Failed)
        val addToCart = screen.awaitElement { it.label == "ADD TO CART" }
        assertEquals(ActionResult.Failed("It's disabled."), actions.tap(addToCart.key, dispatch = refusals))
        val answer = async { screen.session.ask(CardQuestion(1, "Delete the test draft?", listOf("Confirm", "Cancel"))) { SystemClock.uptimeMillis() } }
        try {
            withTimeout(5_000) { while (!screen.session.cardShowing) delay(20) }
            assertEquals(ActionResult.CARD_OPEN, actions.tap(send.key, dispatch = refusals))
        } finally {
            answer.cancel()
        }
        withTimeout(5_000) { while (screen.session.cardShowing) delay(50) }
        assertEquals(0 to 0, refusals.counts)

        val node = CountedPress()
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.tap(screen.awaitElement { it.label == "SEND" }.key, dispatch = node))
        assertEquals(1 to 0, node.counts)

        screen.open("ViewsActivity", "Views fixture")
        val gesture = CountedPress()
        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.tap(screen.awaitElement { it.label == "Touch target" }.key, dispatch = gesture))
        assertEquals(1 to 0, gesture.counts)

        screen.open("PressActivity", "Press fixture")
        val once = CountedPress()
        assertEquals(ActionResult.UNCERTAIN, actions.tap(screen.awaitElement { it.label == "DEAF" }.key, policy = PressPolicy.ONCE, dispatch = once))
        assertEquals(1 to 0, once.counts)
    }

    // How often a press told its dispatch that it went to Android, and that Android refused it.
    private class CountedPress : PressDispatch {
        private val dispatched = AtomicInteger()
        private val refused = AtomicInteger()

        val counts: Pair<Int, Int> get() = dispatched.get() to refused.get()

        override fun dispatching() {
            dispatched.incrementAndGet()
        }

        override fun notDispatched() {
            refused.incrementAndGet()
        }
    }
}
