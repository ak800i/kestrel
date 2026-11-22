package dev.kestrel.app.screen

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ScrollDirection
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The confirmation card's safety (design D32 items 20 and 21 of sub-project 5): it ignores taps for its first 600 ms,
 * comes no sooner than 600 ms after the previous card closed, and while it shows Kestrel performs no gesture and no node
 * action.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun awaitConfirm(): Rect = withTimeout(5_000) {
        var found = screen.overlayButton("Confirm")
        while (found == null) {
            delay(20)
            found = screen.overlayButton("Confirm")
        }
        found
    }

    @Test
    fun aTapInTheCardsFirst600MillisecondsDoesNothing() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val asked = SystemClock.uptimeMillis()
        val answer = async { session.confirm("Delete the test draft?") }
        val confirm = awaitConfirm()

        screen.shell("input tap ${confirm.centerX()} ${confirm.centerY()}")
        // The card was on the screen for less time than since the question; a slow emulator cannot check the delay.
        if (SystemClock.uptimeMillis() - asked >= ARMING_MILLIS) {
            answer.cancel()
            assumeTrue("The tap came too late to check the delay", false)
        }
        delay(300)
        assertTrue("A tap within the card's first 600 ms answered it", answer.isActive)

        delay(ARMING_MILLIS)
        screen.shell("input tap ${confirm.centerX()} ${confirm.centerY()}")
        assertTrue(withTimeout(5_000) { answer.await() })
    }

    @Test
    fun aNewCardComesNoSoonerThan600MillisecondsAfterThePreviousOneClosed() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.confirm("Send a test message?") }
        awaitConfirm()

        val replaced = SystemClock.uptimeMillis()
        val second = async { session.confirm("Delete the test draft?") }
        assertFalse(withTimeout(5_000) { first.await() })
        withTimeout(5_000) { while (!screen.overlayShows("Delete the test draft?")) delay(20) }
        val after = SystemClock.uptimeMillis() - replaced
        second.cancel()

        assertTrue("The new card came after $after ms", after >= ARMING_MILLIS)
    }

    @Test
    fun aQuestionAskedDuringAnotherOnesArmingDelayReplacesIt() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.confirm("Send a test message?") }
        awaitConfirm()
        // The second question waits out the arming delay after the first card closed; the third comes during that wait.
        val second = async { session.confirm("Delete the test draft?") }
        assertFalse(withTimeout(5_000) { first.await() })
        val third = async { session.confirm("Call the test number?") }
        try {
            assertFalse("The waiting question was not replaced", withTimeout(5_000) { second.await() })
            withTimeout(5_000) { while (!screen.overlayShows("Call the test number?")) delay(20) }
            assertFalse("The replaced question showed its card", screen.overlayShows("Delete the test draft?"))
        } finally {
            second.cancel()
            third.cancel()
        }
        withTimeout(5_000) { while (session.cardShowing) delay(50) }
    }

    @Test
    fun noGestureAndNoNodeActionRunWhileTheCardShows() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.viewId == "$FIXTURES:id/send_button" }
        val field = screen.awaitElement { it.viewId == "$FIXTURES:id/message_field" }
        val answer = async { session.confirm("Delete the test draft?") }
        try {
            awaitConfirm()
            assertTrue(session.cardShowing)

            assertEquals(ActionResult.CARD_OPEN, session.actions.tap(send.key))
            assertEquals(ActionResult.CARD_OPEN, session.actions.longPress(send.key))
            assertEquals(ActionResult.CARD_OPEN, session.actions.tapAt(send.bounds.centerX, send.bounds.centerY))
            assertEquals(ActionResult.CARD_OPEN, session.actions.setText(field.key, "Hello"))
            assertEquals(ActionResult.CARD_OPEN, session.actions.scroll(ScrollDirection.DOWN))
        } finally {
            answer.cancel()
        }
        withTimeout(5_000) { while (session.cardShowing) delay(50) }
        assertTrue(session.snapshots.current().snapshot.elements.any { it.label == "Ready" })
    }

    private companion object {
        const val ARMING_MILLIS = 600L
    }
}
