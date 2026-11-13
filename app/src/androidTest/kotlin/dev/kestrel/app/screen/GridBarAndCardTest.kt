package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridBarAndCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(timeoutMillis: Long = 5_000, check: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!check()) delay(100)
    }

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
    fun theCardSaysTrueWhenTheOwnerTapsConfirm() = runBlocking { answerWith("Confirm", expected = true) }

    @Test
    fun theCardSaysFalseWhenTheOwnerTapsCancel() = runBlocking { answerWith("Cancel", expected = false) }

    @Test
    fun cancellingTheQuestionRemovesTheCard() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Delete the draft?") }
        eventually { screen.showsWindow("Kestrel confirmation") }

        answer.cancel()

        eventually { !screen.showsWindow("Kestrel confirmation") }
    }

    @Test
    fun snapshotsLeaveTheCardOut() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Send a test message?") }
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

    private suspend fun CoroutineScope.answerWith(button: String, expected: Boolean) {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Send a test message?") }
        val bounds = withTimeout(5_000) {
            var found = screen.overlayButton(button)
            while (found == null) {
                delay(100)
                found = screen.overlayButton(button)
            }
            found
        }

        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

        assertEquals(expected, withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow("Kestrel confirmation") }
    }
}
