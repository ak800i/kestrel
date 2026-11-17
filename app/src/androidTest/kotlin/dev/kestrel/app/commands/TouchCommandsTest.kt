package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun checkAndSwitchClickOnlyWhenTheStateDiffers() = runBlocking<Unit> {
        assertEquals(Outcome.DONE, CommandTests.say("check Subscribe").outcome)
        awaitStatus("Checked Subscribe")
        assertEquals(Outcome.DONE, CommandTests.say("switch on Subscribe").outcome)
        // Already on: Kestrel clicks nothing and says so on the bar.
        awaitBar(CommandTexts.ALREADY_ON)
        awaitStatus("Checked Subscribe")
        assertEquals(Outcome.DONE, CommandTests.say("uncheck Subscribe").outcome)
        awaitStatus("Unchecked Subscribe")
        assertEquals(Outcome.DONE, CommandTests.say("check Wi-Fi").outcome)
        awaitStatus(WIFI_ON)

        // Without a name, with two checkable elements on the screen, Kestrel asks which one.
        assertEquals(CommandTexts.WHICH_ONE, CommandTests.ask("switch off"))
        val wifi = screen.awaitElement { it.label == WIFI && it.checkable }
        assertEquals(Outcome.DONE, CommandTests.answer("${badge(wifi.key)}").outcome)
        awaitStatus(WIFI_OFF)

        // A number can name an element that does not check: Kestrel refuses it and clicks nothing.
        screen.session.showNumbers()
        val save = screen.awaitElement { it.label == "Save" }
        assertEquals(Outcome.FAILED, CommandTests.say("check ${badge(save.key)}").outcome)
        awaitBar(CommandTexts.NOT_CHECKABLE)
        awaitStatus(WIFI_OFF)
    }

    @Test
    fun incrementAndDecrementMoveASeekBarAndAComposeSliderOneStep() = runBlocking<Unit> {
        val seekBar = screen.awaitElement { it.viewId == "$FIXTURES:id/seek_bar" }
        val speed = screen.awaitElement { it.slider && it.packageName == FIXTURES && it.key != seekBar.key }
        screen.session.showNumbers()

        assertEquals(Outcome.DONE, CommandTests.say("increment ${badge(seekBar.key)}").outcome)
        awaitStatus("Seek bar 55")
        assertEquals(Outcome.DONE, CommandTests.say("decrement ${badge(seekBar.key)}").outcome)
        awaitStatus("Seek bar 50")
        assertEquals(Outcome.DONE, CommandTests.say("increment ${badge(speed.key)}").outcome)
        awaitStatus("Slider 0.55")
    }

    @Test
    fun aSliderGetsANumberAndAProgressBarDoesNot() = runBlocking<Unit> {
        val seekBar = screen.awaitElement { it.viewId == "$FIXTURES:id/seek_bar" }

        screen.session.showNumbers()

        val numbered = screen.session.numberBadges.map { it.key }
        assertTrue("The seek bar has no number", seekBar.key in numbered)
        val progress = screen.session.snapshots.current().snapshot.elements.filter { it.viewId == "$FIXTURES:id/progress_bar" }
        assertTrue("The progress bar has a number", progress.none { it.key in numbered })
    }

    @Test
    fun expandAndCollapseUseTheRowsOwnActions() = runBlocking<Unit> {
        assertEquals(Outcome.DONE, CommandTests.say("expand Details").outcome)
        awaitStatus("Expanded Details")
        // Expanded, the row offers only collapse: Kestrel clicks nothing and says so on the bar.
        assertEquals(Outcome.DONE, CommandTests.say("expand Details").outcome)
        awaitBar(CommandTexts.ALREADY_EXPANDED)
        awaitStatus("Expanded Details")
        assertEquals(Outcome.DONE, CommandTests.say("collapse Details").outcome)
        awaitStatus("Collapsed Details")
    }

    @Test
    fun aNotificationInTheShadeExpandsCollapsesAndGoesByItsNameAndByItsNumber() = runBlocking<Unit> {
        postNotification()
        try {
            screen.session.actions.global(GlobalAction.NOTIFICATIONS)
            // The shade may show the newest notification expanded already.
            val wasExpanded = ElementAction.COLLAPSE in notificationRow().actions
            val first = if (wasExpanded) "collapse" else "expand"
            val second = if (wasExpanded) "expand" else "collapse"

            // By the name that the labels overlay would show for it.
            assertEquals(Outcome.DONE, CommandTests.say("$first ${checkNotNull(notificationRow().label)}").outcome)
            notificationRow { (ElementAction.COLLAPSE in it.actions) != wasExpanded }
            // By its number.
            screen.session.showNumbers()
            assertEquals(Outcome.DONE, CommandTests.say("$second ${badge(notificationRow().key)}").outcome)
            notificationRow { (ElementAction.COLLAPSE in it.actions) == wasExpanded }
            // Said again, it changes nothing: the row already is as the owner asks.
            assertEquals(Outcome.DONE, CommandTests.say("$second ${badge(notificationRow().key)}").outcome)
            awaitBar(if (wasExpanded) CommandTexts.ALREADY_EXPANDED else CommandTexts.ALREADY_COLLAPSED)
            // By its title, which is what the owner reads in the shade.
            assertEquals(Outcome.DONE, CommandTests.say("$first $NOTIFICATION_TITLE").outcome)
            notificationRow { (ElementAction.COLLAPSE in it.actions) != wasExpanded }

            // Dismiss by its name,
            assertEquals(Outcome.DONE, CommandTests.say("dismiss ${checkNotNull(notificationRow().label)}").outcome)
            withTimeout(5_000) { while (notificationPosted()) delay(100) }
            // and, posted again, by its number.
            postNotification()
            assertEquals(Outcome.DONE, CommandTests.say("dismiss ${badge(notificationRow().key)}").outcome)
            withTimeout(5_000) { while (notificationPosted()) delay(100) }
        } finally {
            removeNotification()
        }
    }

    @Test
    fun swipeAwayDismissesANotificationInTheShade() = runBlocking<Unit> {
        postNotification()
        try {
            screen.session.actions.global(GlobalAction.NOTIFICATIONS)

            // The fallback of "dismiss" for a notification without a dismiss action; the fixture's offers one, so the test
            // calls the fallback itself.
            assertTrue(screen.session.actions.swipeAway(notificationRow().key) is ActionResult.Done)

            withTimeout(5_000) { while (notificationPosted()) delay(100) }
        } finally {
            removeNotification()
        }
    }

    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }

    /** The number on the element with [key], once the numbers have followed the screen. */
    private suspend fun badge(key: String): Int = withTimeout(5_000) {
        var badge = screen.session.numberBadges.firstOrNull { it.key == key }
        while (badge == null) {
            delay(50)
            badge = screen.session.numberBadges.firstOrNull { it.key == key }
        }
        badge.number
    }

    /** Posts the test app's notification and waits until Android lists it. The tests grant, command-test.ps1 revokes. */
    private suspend fun postNotification() {
        screen.shell("pm grant $FIXTURES android.permission.POST_NOTIFICATIONS")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity")
        withTimeout(5_000) { while (!notificationPosted()) delay(100) }
    }

    /** Removes the test app's notification, if it is still there, and closes the shade. */
    private suspend fun removeNotification() {
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez cancel true")
        screen.session.actions.global(GlobalAction.DISMISS_SHADE)
    }

    /** Whether Android lists a notification of the test app. The answer holds only keys, never a notification's text. */
    private fun notificationPosted(): Boolean = screen.shell("cmd notification list").contains("|$FIXTURES|")

    /**
     * The test app's notification in the shade: the nearest element above its title that offers to be dismissed, once it
     * matches [accept].
     */
    private suspend fun notificationRow(accept: (ScreenElement) -> Boolean = { true }): ScreenElement = withTimeout(5_000) {
        var row: ScreenElement? = null
        while (row == null) {
            val elements = screen.session.snapshots.current().snapshot.elements
            val title = elements.firstOrNull { it.packageName == SYSTEM_UI && it.label == NOTIFICATION_TITLE }
            row = generateSequence(title?.parent) { elements[it].parent }
                .map { elements[it] }
                .firstOrNull { ElementAction.DISMISS in it.actions }
                ?.takeIf(accept)
            if (row == null) delay(100)
        }
        row
    }

    private companion object {
        const val WIFI = "Wi\u2011Fi"
        const val WIFI_ON = "Wi\u2011Fi on"
        const val WIFI_OFF = "Wi\u2011Fi off"
        const val SYSTEM_UI = "com.android.systemui"

        /** NotificationActivity.TITLE in the test app. */
        const val NOTIFICATION_TITLE = "Fixture reminder"
    }
}
