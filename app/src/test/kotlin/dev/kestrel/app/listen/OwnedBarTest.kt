package dev.kestrel.app.listen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feedback bar's one writer (design 6.6 and 10.5 of sub-project 7): a goal's progress never hides another text, and shows after it. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnedBarTest {
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private var releases = 0

    private fun TestScope.bar() = OwnedBar(
        object : FeedbackBar {
            override fun show(text: String, keep: Boolean) {
                shown += text to keep
            }

            override fun release() {
                releases++
            }
        },
        backgroundScope,
    ) { testScheduler.currentTime }

    @Test
    fun aKeptTextHoldsTheBarUntilItsOwnerReleasesItAndThenTheNewestProgressShows() = runTest {
        val bar = bar()
        bar.progress("Step 1")
        bar.show("Which account?", keep = true)
        advanceTimeBy(10_000)
        bar.progress("Step 2")
        bar.progress("Step 3")
        runCurrent()
        // Only the kept text's own owner lets it go.
        bar.release(BarOwner.Commands)
        bar.release(BarOwner.Other)

        assertEquals(listOf("Step 1" to false, "Which account?" to true, "Step 3" to false), shown)
        assertEquals(1, releases)
    }

    // Review low item b of plan 7F: "On hold" shows once the held goal's own sentence has had its time on the bar.
    @Test
    fun anotherTextKeepsTheBarForItsTimeAndThenTheNewestProgressShowsByItself() = runTest {
        val bar = bar()
        bar.show("I stopped because you touched the screen.")
        advanceTimeBy(1_000)
        bar.progress("Step 12")
        bar.progress("On hold")
        advanceTimeBy(OwnedBar.OTHERS_MILLIS - 1_001)
        runCurrent()
        assertEquals(listOf("I stopped because you touched the screen." to false), shown)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("I stopped because you touched the screen." to false, "On hold" to false), shown)
    }

    // Review A-M5 of plan 7F: a final reply holds the bar against a goal's progress until its follow-up window's countdown
    // has taken it over and let go, so that the progress never cuts the countdown.
    @Test
    fun aFinalReplyKeepsTheBarForItsFollowUpCountdown() = runTest {
        val bar = bar()
        bar.show("Done.", owner = BarOwner.FinalReply(3))
        bar.progress("On hold")
        advanceTimeBy(OwnedBar.OTHERS_MILLIS * 2)
        runCurrent()
        assertTrue(bar.takeOver("Go on… 5", BarOwner.FollowUp, over = BarOwner.FinalReply(3)))
        bar.release(BarOwner.FollowUp)
        advanceTimeBy(OwnedBar.OTHERS_MILLIS)
        runCurrent()

        assertEquals(listOf("Done." to false, "Go on… 5" to true, "On hold" to false), shown)
    }

    // Review A-R2 of plan 7F: a final reply that gets no follow-up window (unspoken, Kestrel off, or no window could open)
    // is let go by listening, and a later progress then shows.
    @Test
    fun aFinalReplyWithoutACountdownLetsALaterProgressShowOnceItIsLetGo() = runTest {
        val bar = bar()
        bar.show("Done.", owner = BarOwner.FinalReply(3))
        bar.progress("Step 1")
        advanceTimeBy(OwnedBar.OTHERS_MILLIS * 2)
        runCurrent()
        assertEquals(listOf("Done." to false), shown)

        bar.release(BarOwner.FinalReply(3))
        runCurrent()

        assertEquals(listOf("Done." to false, "Step 1" to false), shown)
    }
    // Review L1 of plan 7F: a progress line that waits for the bar never shows once its goal has ended.
    @Test
    fun aWaitingProgressIsDroppedWhenItsGoalEnds() = runTest {
        val bar = bar()
        bar.show("Which account?", keep = true)
        bar.progress("Step 3")
        bar.progress(null)
        bar.release(BarOwner.Other)
        bar.show("Done.")
        bar.progress("Step 1")
        bar.progress(null)
        advanceTimeBy(OwnedBar.OTHERS_MILLIS * 2)
        runCurrent()

        assertEquals(listOf("Which account?" to true, "Done." to false), shown)
    }
}
