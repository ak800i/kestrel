package dev.kestrel.app.commands

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone part's decisions on the global actions, the previous app and Circle to Search, without a device. */
class PhoneCommandsTest {
    private val done = ActionResult.Done(ActionMethod.GLOBAL_ACTION)
    private val refused = ActionResult.Failed("Android refused to open the power menu.")

    @Test
    fun everyGlobalActionIsRepeatableExceptTheLockScreen() = runTest {
        for (action in GlobalCommand.entries) {
            val result = globalCommand(action, {}) { done }

            assertEquals(action.name, CommandResult(Outcome.DONE, repeatable = action != GlobalCommand.LOCK_SCREEN), result)
        }
    }

    @Test
    fun aScreenshotHidesTheFeedbackBarBeforeTheActionAndNothingElseDoes() = runTest {
        val steps = mutableListOf<String>()

        globalCommand(GlobalCommand.SCREENSHOT, { steps += "hide" }) { steps += it.name; done }
        globalCommand(GlobalCommand.BACK, { steps += "hide" }) { steps += it.name; done }

        assertEquals(listOf("hide", GlobalAction.SCREENSHOT.name, GlobalAction.BACK.name), steps)
    }

    @Test
    fun aRefusedOpenAppsIsSaidAndTheOtherRefusalsAreShown() = runTest {
        assertEquals(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_APP_DRAWER), globalCommand(GlobalCommand.ALL_APPS, {}) { refused })
        assertEquals(CommandResult(Outcome.FAILED, show = refused.reason), globalCommand(GlobalCommand.POWER_MENU, {}) { refused })
    }

    @Test
    fun circleToSearchActsOnlyWhenAndroidListsIt() = runTest {
        val performed = mutableListOf<Int>()

        assertEquals(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_CIRCLE_TO_SEARCH), circleToSearch(setOf(14)) { performed += it; done })
        assertTrue(performed.isEmpty())
        assertEquals(CommandResult(Outcome.DONE), circleToSearch(setOf(14, 500)) { performed += it; done })
        assertEquals(listOf(PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION), performed)
    }

    @Test
    fun aFailedSwitchIsShownAndASwitchIsRepeatable() {
        assertEquals(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_SWITCHED), previousAppResult(ActionResult.Failed("No other app came to the front")))
        assertEquals(CommandResult(Outcome.DONE, repeatable = true), previousAppResult(done))
    }
}
