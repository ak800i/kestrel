package dev.kestrel.app.screen

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
import dev.kestrel.screen.overlay.NumberPad
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
 * The question card's safety (design D32 items 20 and 21 of sub-project 5; design 10.3 of sub-project 7): it ignores taps
 * for its first 600 ms, comes no sooner than 600 ms after the previous card closed, and while it shows Kestrel performs no
 * gesture and no node action. Each button gives its own number, and the keypad gives the number that the owner typed.
 */
@RunWith(AndroidJUnit4::class)
class QuestionCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private fun question(key: Int, text: String, buttons: List<String> = listOf("Confirm", "Cancel")) = CardQuestion(key, text, buttons)

    private suspend fun awaitButton(label: String): Rect = withTimeout(5_000) {
        var found = screen.overlayButton(label)
        while (found == null) {
            delay(20)
            found = screen.overlayButton(label)
        }
        found
    }

    private fun tap(bounds: Rect) {
        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    @Test
    fun aTapInTheCardsFirst600MillisecondsDoesNothing() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val asked = SystemClock.uptimeMillis()
        val answer = async { session.ask(question(1, "Delete the test draft?")) }
        val confirm = awaitButton("Confirm")

        tap(confirm)
        // The card was on the screen for less time than since the question; a slow emulator cannot check the delay.
        if (SystemClock.uptimeMillis() - asked >= CARD_ARMING_MILLIS) {
            answer.cancel()
            assumeTrue("The tap came too late to check the delay", false)
        }
        delay(300)
        assertTrue("A tap within the card's first 600 ms answered it", answer.isActive)

        delay(CARD_ARMING_MILLIS)
        tap(confirm)
        assertEquals(CardEnd.Tapped(0), withTimeout(5_000) { answer.await() })
    }

    @Test
    fun aNewCardComesNoSoonerThan600MillisecondsAfterThePreviousOneClosed() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.ask(question(1, "Send a test message?")) }
        awaitButton("Confirm")

        val replaced = SystemClock.uptimeMillis()
        val second = async { session.ask(question(2, "Delete the test draft?")) }
        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { first.await() })
        withTimeout(5_000) { while (!screen.overlayShows("Delete the test draft?")) delay(20) }
        val after = SystemClock.uptimeMillis() - replaced
        second.cancel()

        assertTrue("The new card came after $after ms", after >= CARD_ARMING_MILLIS)
    }

    @Test
    fun aQuestionAskedDuringAnotherOnesArmingDelayReplacesIt() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.ask(question(1, "Send a test message?")) }
        awaitButton("Confirm")
        // The second question waits out the arming delay after the first card closed; the third comes during that wait.
        val second = async { session.ask(question(2, "Delete the test draft?")) }
        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { first.await() })
        val third = async { session.ask(question(3, "Call the test number?")) }
        try {
            assertEquals("The waiting question was not replaced", CardEnd.Withdrawn, withTimeout(5_000) { second.await() })
            withTimeout(5_000) { while (!screen.overlayShows("Call the test number?")) delay(20) }
            assertTrue("The replaced question showed its card", !screen.overlayShows("Delete the test draft?"))
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
        val answer = async { session.ask(question(1, "Delete the test draft?")) }
        try {
            awaitButton("Confirm")
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

    // Design 10.3 of sub-project 7: a pick over many numbers is answered on the card's own keypad, so that the owner sees
    // the numbers while he enters one; it keeps to the pick's range, and its Move key takes the card off the numbers.
    @Test
    fun theKeypadGivesTheNumberThatTheOwnerTyped() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(CardQuestion(4, "Which number should I use?", listOf("Cancel"), NumberPad("Delete digit", "OK", "Move", highest = 20))) }
        val one = awaitButton("1")
        delay(CARD_ARMING_MILLIS)

        tap(one)
        tap(awaitButton("2"))
        tap(awaitButton("9"))
        // 129 is above the pick's highest number, 20: OK gives nothing.
        tap(awaitButton("OK"))
        delay(SETTLE_MILLIS)
        assertFalse(answer.isCompleted)
        tap(awaitButton("Delete digit"))
        // The card moves to the other end of the screen, off the numbers that it covered, and keeps the number.
        val bottom = awaitButton("OK")
        val moved = SystemClock.uptimeMillis()
        tap(awaitButton("Move"))
        val top = withTimeout(5_000) {
            var found = awaitButton("OK")
            while (found.top >= bottom.top) {
                delay(20)
                found = awaitButton("OK")
            }
            found
        }
        // Review L2: the card arms again where it moved to, so that a tap aimed at what was there answers nothing. The clock
        // is read once the tap has landed, from before the Move tap, so the moved card was showing for less than that; a
        // slow emulator that finds the moved card or lands the tap too late cannot check it.
        tap(top)
        val early = SystemClock.uptimeMillis() - moved < CARD_ARMING_MILLIS
        delay(SETTLE_MILLIS / 5)
        if (early) assertFalse("A tap in the moved card's first 600 ms answered it", answer.isCompleted)
        delay(CARD_ARMING_MILLIS)
        if (!answer.isCompleted) tap(top)

        assertEquals(CardEnd.Number(12), withTimeout(5_000) { answer.await() })
    }

    @Test
    fun aCardsButtonGivesItsOwnNumber() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(question(5, "Which chat?", listOf("Ana", "Mila", "Marko", "Jovana", "Cancel"))) }
        val marko = awaitButton("Marko")

        delay(CARD_ARMING_MILLIS)
        tap(marko)

        assertEquals(CardEnd.Tapped(2), withTimeout(5_000) { answer.await() })
    }

    private companion object {
        /** How long the test waits for the card to settle after a tap that changes nothing, or a move. */
        const val SETTLE_MILLIS = 500L
    }
}
