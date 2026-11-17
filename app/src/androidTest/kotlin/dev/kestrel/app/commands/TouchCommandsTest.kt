package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tap and long press by label and by number, "Which one?", "I can't find X", bare labels and "again" (design D6). */
@RunWith(AndroidJUnit4::class)
class TouchCommandsTest {
    // Before ScreenServiceRule, as in RefusedReplyTest: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun listenOnTheFixture() {
        CommandTests.start()
        screen.open("CommandsActivity", "Commands fixture")
    }

    @After
    fun stopListening() = CommandTests.stop()

    private fun awaitStatus(text: String) = runBlocking { screen.awaitElement { it.label == text } }

    @Test
    fun tapByLabelTapsTheButton() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Send").outcome)
        awaitStatus("Clicked Send")
    }

    @Test
    fun theExactLabelWinsOverASimilarOne() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Safe").outcome)
        awaitStatus("Clicked Safe")
        assertEquals(Outcome.DONE, CommandTests.say("tap Save").outcome)
        awaitStatus("Clicked Save")
    }

    @Test
    fun whichOneAsksAndTheAnsweredNumberActs() {
        assertEquals(CommandTexts.WHICH_ONE, CommandTests.ask("tap Delete"))
        assertEquals(2, screen.session.numberBadges.size)

        // The rows are numbered from the top: Ana's row is 1 and Dan's row is 2.
        assertEquals(Outcome.DONE, CommandTests.answer("number two").outcome)
        awaitStatus("Deleted Dan")
        assertTrue(screen.session.numberBadges.isEmpty())
    }

    @Test
    fun anAnswerThatIsNoNumberRunsAsANewCommand() {
        CommandTests.ask("tap Delete")

        assertEquals(Outcome.CANCELLED, CommandTests.answer("tap Send").outcome)
        assertEquals(Outcome.DONE, CommandTests.next().outcome)
        awaitStatus("Clicked Send")
    }

    @Test
    fun aLabelThatNothingMatchesSaysSoAndLeavesNumbersForTheNextTap() {
        assertEquals(Outcome.FAILED, CommandTests.say("tap Weather").outcome)
        CommandTests.awaitSaid("I can't find Weather.")
        val send = runBlocking { screen.awaitElement { it.label == "Send" } }
        val number = screen.session.numberBadges.first { it.key == send.key }.number

        assertEquals(Outcome.DONE, CommandTests.say("tap $number").outcome)
        awaitStatus("Clicked Send")
    }

    @Test
    fun aBareLabelTapsOnlyAClearMatchAndTwoEqualLabelsAreIgnored() {
        assertEquals(Outcome.DONE, CommandTests.say("Send later").outcome)
        awaitStatus("Clicked Send later")

        assertEquals(Outcome.IGNORED, CommandTests.say("Delete").outcome)
        assertFalse(CommandTests.asked)
        // Nothing was tapped, and Kestrel said nothing.
        awaitStatus("Clicked Send later")
        assertTrue(CommandTests.spoken.isEmpty())
    }

    @Test
    fun deleteInADialogAndAgainTapItsOnlyDeleteButtonTwice() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Open dialog").outcome)
        awaitStatus("Draft")

        assertEquals(Outcome.DONE, CommandTests.say("Delete").outcome)
        awaitStatus("Deleted 1 time")
        assertEquals(Outcome.DONE, CommandTests.say("again").outcome)
        awaitStatus("Deleted 2 times")
    }

    @Test
    fun aNumberWithoutNumbersOnTheScreenAsksForThem() {
        assertEquals(Outcome.FAILED, CommandTests.say("tap 7").outcome)
        CommandTests.awaitSaid(CommandTexts.SHOW_NUMBERS_FIRST)
    }

    @Test
    fun longPressByLabel() {
        assertEquals(Outcome.DONE, CommandTests.say("long press Counter").outcome)
        awaitStatus("Long-pressed 1 time")
    }

    @Test
    fun aCyrillicLabelAnswersToItsLatinSpelling() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Djordje").outcome)
        awaitStatus("Clicked Ђорђе")
    }

    @Test
    fun whichOneEndsWhenTheScreenChanges() {
        CommandTests.ask("tap Delete")

        // Another activity changes the screen without a touch, so only the change of the screen can end the question.
        screen.shell("am start -W -n ${ScreenServiceRule.FIXTURES}/.ViewsActivity")

        // Well before the question's own 15 s timeout.
        assertEquals(Outcome.FAILED, CommandTests.next(5_000).outcome)
        CommandTests.awaitSaid(CommandTexts.SCREEN_CHANGED)
    }

    @Test
    fun stopEndsWhichOneAndRemovesItsNumbers() {
        CommandTests.ask("tap Delete")

        assertEquals(Outcome.CANCELLED, CommandTests.say("stop", 5_000).outcome)
        // The job removes its numbers before it completes, and the report comes after that.
        assertTrue(screen.session.numberBadges.isEmpty())
    }

    @Test
    fun theOwnersTouchCancelsWhichOne() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            val title = screen.awaitElement { it.label == "Commands fixture" }.bounds
            CommandTests.ask("tap Delete")
            screen.awaitInputWindow("Kestrel touch watcher")

            finger.tap(title.centerX, title.centerY)

            // Well before the question's own 15 s timeout, which would also end it as cancelled.
            assertEquals(Outcome.CANCELLED, CommandTests.next(5_000).outcome)
        }
    }
}
