package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A): global actions, apps, the phone's
 * settings, media, magnification and calls. The part builds its own Android services from the environment's context,
 * and touches none of them before a command needs it.
 */
internal fun phoneCommands(environment: CommandEnvironment): CommandPart = PhoneCommands(environment)

internal class PhoneCommands(private val environment: CommandEnvironment) : CommandPart {
    private val apps = AppCommands(environment)
    private val device = DeviceCommands(environment)
    // Tasks 2 to 6 of plan 3C add their parts above this line.

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        // Without Kestrel's accessibility service, InstantCommands passes only these, which need no screen.
        device.withoutScreen(command)?.let { return it }
        val screen = environment.screen() ?: return null
        return when (command) {
            is PhoneCommand.Global -> globalCommand(command.action, { screen.hideFeedback() }) { screen.actions.global(it) }
            PhoneCommand.PreviousApp -> previousAppResult(screen.system.previousApp())
            PhoneCommand.CircleToSearch -> circleToSearch(screen.system.systemActions()) { screen.system.systemAction(it) }
            is PhoneCommand.OpenApp -> apps.open(screen, command, conversation)
            is PhoneCommand.Radio -> device.radio(screen, command.radio, command.on)
            is PhoneCommand.Magnify -> device.magnify(screen, command.action)
            is PhoneCommand.Pan -> device.pan(screen, command.direction)
            // While the screen is magnified, "scroll" without a target moves the magnified area (design D10).
            is Command.Scroll -> if (command.target == null && screen.actions.zoomScale > 1f) device.pan(screen, command.direction) else null
            // Tasks 2 to 6 of plan 3C add their commands above this line.
            else -> null
        }
    }
}

/**
 * A global action, [perform]ed after [hideFeedback] for a screenshot, which leaves out Kestrel's feedback bar; the
 * numbers, the labels and the grid stay (design D9). Every global action is repeatable except the lock screen.
 */
internal suspend fun globalCommand(
    action: GlobalCommand,
    hideFeedback: suspend () -> Unit,
    perform: suspend (GlobalAction) -> ActionResult,
): CommandResult {
    if (action == GlobalCommand.SCREENSHOT) hideFeedback()
    return when (val result = perform(globalAction(action))) {
        is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = action != GlobalCommand.LOCK_SCREEN)
        is ActionResult.Failed -> if (action == GlobalCommand.ALL_APPS) {
            CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_APP_DRAWER)
        } else {
            // The reasons of ScreenActions are fixed texts without the screen's words.
            CommandResult(Outcome.FAILED, show = result.reason)
        }
    }
}

/** "Show previous app": a failed switch shows that it failed on the bar. */
internal fun previousAppResult(result: ActionResult): CommandResult = when (result) {
    is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
    is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_SWITCHED)
}

/**
 * Circle to Search, only through Android's system action where the launcher [offered] it (research r25); never a
 * gesture.
 */
internal suspend fun circleToSearch(offered: Set<Int>, perform: suspend (Int) -> ActionResult): CommandResult {
    if (PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION !in offered) {
        return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_CIRCLE_TO_SEARCH)
    }
    return when (val result = perform(PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION)) {
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
