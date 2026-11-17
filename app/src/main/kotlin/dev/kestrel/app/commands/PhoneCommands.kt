package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A): global actions, apps, the phone's
 * settings, media, magnification and calls. The part builds its own Android services from the environment's context,
 * and touches none of them before a command needs it.
 */
internal fun phoneCommands(environment: CommandEnvironment): CommandPart = PhoneCommands(environment)

internal class PhoneCommands(private val environment: CommandEnvironment) : CommandPart {
    // Tasks 2 to 6 of plan 3C add their parts above this line.

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val screen = environment.screen() ?: return null
        return when (command) {
            is PhoneCommand.Global -> global(screen, command.action)
            PhoneCommand.PreviousApp -> when (screen.system.previousApp()) {
                is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
                is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_SWITCHED)
            }
            PhoneCommand.CircleToSearch -> circleToSearch(screen)
            // Tasks 2 to 6 of plan 3C add their commands above this line.
            else -> null
        }
    }

    // A screenshot leaves out Kestrel's feedback bar; the numbers, the labels and the grid stay (design D9).
    private suspend fun global(screen: ScreenSession, action: GlobalCommand): CommandResult {
        if (action == GlobalCommand.SCREENSHOT) screen.hideFeedback()
        return when (val result = screen.actions.global(globalAction(action))) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = action != GlobalCommand.LOCK_SCREEN)
            is ActionResult.Failed -> if (action == GlobalCommand.ALL_APPS) {
                CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_APP_DRAWER)
            } else {
                // The reasons of ScreenActions are fixed texts without the screen's words.
                CommandResult(Outcome.FAILED, show = result.reason)
            }
        }
    }

    // Only through Android's system action, where the launcher offers it (research r25); never a gesture.
    private suspend fun circleToSearch(screen: ScreenSession): CommandResult {
        if (PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION !in screen.system.systemActions()) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_CIRCLE_TO_SEARCH)
        }
        return when (val result = screen.system.systemAction(PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    private fun globalAction(action: GlobalCommand): GlobalAction = when (action) {
        GlobalCommand.BACK -> GlobalAction.BACK
        GlobalCommand.HOME -> GlobalAction.HOME
        GlobalCommand.NOTIFICATIONS -> GlobalAction.NOTIFICATIONS
        GlobalCommand.QUICK_SETTINGS -> GlobalAction.QUICK_SETTINGS
        GlobalCommand.RECENT_APPS -> GlobalAction.RECENT_APPS
        GlobalCommand.LOCK_SCREEN -> GlobalAction.LOCK_SCREEN
        GlobalCommand.POWER_MENU -> GlobalAction.POWER_MENU
        GlobalCommand.SCREENSHOT -> GlobalAction.SCREENSHOT
        GlobalCommand.ALL_APPS -> GlobalAction.ALL_APPS
    }
}
