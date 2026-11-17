package dev.kestrel.app.commands

import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Scrolling, swipes, pinches and drags by voice (design spec, Appendix A, "Touch gestures" and "Swipe gestures"),
 * through the same path as speech. Like every command test, they run only through scripts\command-test.ps1.
 */
class GestureCommandsTest {
    // Before ScreenServiceRule, as in RefusedReplyTest: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() = CommandTests.stop()

    @Test
    fun scrollMovesTheListAndScrollToTheEndsGoesAllTheWay() = runBlocking<Unit> {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(Outcome.DONE, CommandTests.say("scroll down").outcome)
        screen.awaitElement { it.label == "Row 25" }
        assertFalse(shows("Row 1"))
        assertEquals(Outcome.DONE, CommandTests.say("scroll to bottom").outcome)
        screen.awaitElement { it.label == "Row 60" }
        assertEquals(Outcome.DONE, CommandTests.say("scroll to top").outcome)
        screen.awaitElement { it.label == "Row 1" }
    }

    @Test
    fun scrollOnANamedListScrollsThatList() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        // The pager has no name of its own; Kestrel names it after its view ID, `pager`.
        assertEquals(Outcome.DONE, CommandTests.say("scroll right on pager").outcome)
        awaitStatus("Showing page 2")
    }

    @Test
    fun swipeForwardsAndBackwardsTurnThePagesOfAPager() = runBlocking<Unit> {
        screen.open("ScrollActivity", "Pager scroll fixture", extras = "--ez pages true")

        assertEquals(Outcome.DONE, CommandTests.say("swipe forwards").outcome)
        screen.awaitElement { it.label == "Second page row 1" }
        assertEquals(Outcome.DONE, CommandTests.say("swipe backwards").outcome)
        // Part of the first page stays in sight beside the second, so the test waits until the second page has gone.
        withTimeout(5_000) { while (shows("Second page row 1")) delay(100) }
    }

    @Test
    fun swipeForwardsSaysSoWhenNothingMovesSideways() = runBlocking<Unit> {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(Outcome.FAILED, CommandTests.say("swipe forwards").outcome)
        withTimeout(5_000) { while (screen.session.feedbackText != CommandTexts.NOTHING_SWIPES_SIDEWAYS) delay(50) }
    }

    @Test
    fun aSwipeGoesInTheDirectionThatTheOwnerNames() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        assertEquals(Outcome.DONE, CommandTests.say("swipe up").outcome)
        awaitStatus("Swipe up")
        assertEquals(Outcome.DONE, CommandTests.say("swipe left on gesture pad").outcome)
        awaitStatus("Swipe left")
    }

    @Test
    fun pinchesOpenAndCloseAroundTheCentre() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        assertEquals(Outcome.DONE, CommandTests.say("pinch out").outcome)
        awaitStatus("Pinch out")
        assertEquals(Outcome.DONE, CommandTests.say("pinch in on gesture pad").outcome)
        awaitStatus("Pinch in")
    }

    @Test
    fun dragMovesAnElementToTheTargetThatTheOwnerNamesOrGivesWhenAsked() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("drag Drag me to Drop here").outcome)
        awaitStatus("Dropped on Drop here")

        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(CommandTexts.DRAG_WHERE, CommandTests.ask("drag Drag me"))
        assertEquals(Outcome.DONE, CommandTests.answer("to Drop here").outcome)
        awaitStatus("Dropped on Drop here")
    }

    @Test
    fun edgeSwipesReachTheSystemsGestures() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        // With gesture navigation, as on kestrel-api37, a swipe from the top edge opens the notification shade,
        assertEquals(Outcome.DONE, CommandTests.say("swipe from the top edge").outcome)
        try {
            withTimeout(5_000) { while (!shadeOpen()) delay(100) }
        } finally {
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
        withTimeout(5_000) { while (shadeOpen()) delay(100) }
        // and one from the left edge goes back, which closes the fixture.
        assertEquals(Outcome.DONE, CommandTests.say("swipe from the left edge").outcome)
        withTimeout(5_000) {
            while (screen.session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES }) {
                delay(100)
            }
        }
    }

    private suspend fun shows(label: String): Boolean = screen.session.snapshots.current().snapshot.elements.any { it.label == label }

    private suspend fun awaitStatus(text: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/status" && it.label == text }

    /** Whether the notification shade has the input focus. `dumpsys window` names the window, never its content. */
    private fun shadeOpen(): Boolean =
        screen.shell("dumpsys window").lineSequence().any { "mCurrentFocus" in it && "NotificationShade" in it }
}
