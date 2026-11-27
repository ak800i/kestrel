package dev.kestrel.app.screen

import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import dev.kestrel.screen.overlay.CARD_TITLE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridBarAndCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    // The card's stamp, which it takes on the main thread at the tap (plan 7F): the clock then. An answer whose stamp is
    // no earlier than the clock just before the tap was sent proves that the stamp was taken at the tap, not when the
    // question was asked.
    private val atTap: () -> Long = {
        check(Looper.getMainLooper().isCurrentThread) { "The card stamped its tap off the main thread" }
        SystemClock.uptimeMillis()
    }

    // The stamp of answer [end], which must be from the tap that was sent at [before] or later.
    private fun stampAfter(before: Long, end: CardEnd<Long>): Long {
        val stamp = (end as? CardEnd.Answered<Long>)?.stamp ?: throw AssertionError("The card gave no answer: $end")
        assertTrue("The answer's stamp $stamp is from before the tap at $before", stamp >= before)
        return stamp
    }

    private suspend fun eventually(timeoutMillis: Long = 5_000, check: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!check()) delay(100)
    }

    private fun confirmation(key: Int, text: String) = CardQuestion(key, text, listOf("Confirm", "Cancel"))

    @Test
    fun theGridCoversTheScreenAndLetsTouchesThrough() = runBlocking {
        screen.open("GesturesActivity", "Gestures fixture")
        val pad = screen.awaitElement { it.label == "Gesture pad" }.bounds

        session.showGrid()

        val grid = session.grid!!
        assertEquals(4, grid.columns)
        eventually { screen.showsWindow("Kestrel grid") }
        val cell = (1..grid.cellCount).map(grid::cell).first { pad.intersect(it) == it }
        session.actions.tapAt(cell.centerX, cell.centerY)
        screen.awaitElement { it.label == "Tap" }
        session.hideGrid()
        eventually { !screen.showsWindow("Kestrel grid") }
    }

    @Test
    fun theFeedbackBarShowsTextAndFadesAway() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showFeedback("Listening")

        assertEquals("Listening", session.feedbackText)
        eventually { screen.showsWindow("Kestrel feedback bar") }
        eventually(timeoutMillis = 8_000) { session.feedbackText == null && !screen.showsWindow("Kestrel feedback bar") }
    }

    @Test
    fun theCardGivesConfirmWhenTheOwnerTapsIt() = runBlocking { answerWith("Confirm", expected = CONFIRM) }

    @Test
    fun theCardGivesCancelWhenTheOwnerTapsIt() = runBlocking { answerWith("Cancel", expected = CANCEL) }

    @Test
    fun cancellingTheQuestionRemovesTheCard() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(confirmation(1, "Delete the draft?"), atTap) }
        eventually { screen.showsWindow(CARD_TITLE) }

        answer.cancel()

        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    @Test
    fun aNewQuestionWithdrawsTheOldOne() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.ask(confirmation(1, "Send a test message?"), atTap) }
        eventually { screen.overlayButton("Confirm") != null }

        val second = async { session.ask(confirmation(2, "Delete the draft?"), atTap) }

        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { first.await() })
        // Only the second card is left, so the tap reaches its Confirm button.
        eventually { screen.overlayShows("Delete the draft?") && !screen.overlayShows("Send a test message?") }
        // A card ignores taps for its first 600 ms (design D32 item 21 of sub-project 5).
        delay(ARMED_MILLIS)
        val confirm = screen.overlayButton("Confirm")!!
        val before = SystemClock.uptimeMillis()
        screen.shell("input tap ${confirm.centerX()} ${confirm.centerY()}")
        val end = withTimeout(5_000) { second.await() }
        assertEquals(CardEnd.Tapped(CONFIRM, stampAfter(before, end)), end)
        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    @Test
    fun closingTheSessionWithdrawsTheQuestion() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(confirmation(1, "Send a test message?"), atTap) }
        eventually { screen.overlayButton("Confirm") != null }

        // As the service does when it disconnects. The rule's next start closes this session again.
        withContext(Dispatchers.Main) { session.close() }

        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    @Test
    fun snapshotsLeaveTheCardOut() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(confirmation(1, "Send a test message?"), atTap) }
        try {
            eventually { screen.overlayButton("Confirm") != null }

            // The card is now in Android's list of interactive windows. A snapshot read after this point must still
            // leave it out, or Kestrel could tap its own Confirm button.
            session.snapshots.invalidate()
            val snapshot = session.snapshots.current().snapshot
            assertEquals(emptyList<WindowSummary>(), snapshot.windows.filter { it.packageName == ScreenServiceRule.KESTREL })
            assertEquals(emptyList<ScreenElement>(), snapshot.elements.filter { it.packageName == ScreenServiceRule.KESTREL })
        } finally {
            answer.cancel()
        }
    }

    private suspend fun CoroutineScope.answerWith(button: String, expected: Int) {
        screen.open("ViewsActivity", "Views fixture")
        // The question contains both buttons' labels, so the tests also check that the rule finds the buttons.
        val answer = async { session.ask(confirmation(1, "Confirm or cancel a test message?"), atTap) }
        val bounds = withTimeout(5_000) {
            var found = screen.overlayButton(button)
            while (found == null) {
                delay(100)
                found = screen.overlayButton(button)
            }
            found
        }

        delay(ARMED_MILLIS)
        val before = SystemClock.uptimeMillis()
        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

        val end = withTimeout(5_000) { answer.await() }
        assertEquals(CardEnd.Tapped(expected, stampAfter(before, end)), end)
        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    private companion object {
        /** A little more than the 600 ms in which a card ignores taps. */
        const val ARMED_MILLIS = CARD_ARMING_MILLIS + 100

        const val CONFIRM = 0
        const val CANCEL = 1
    }
}
