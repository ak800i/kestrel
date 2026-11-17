package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6): global actions, apps, phone settings, media,
 * magnification and calls. Plan 3C replaces this stub, which takes no command.
 */
@Suppress("UNUSED_PARAMETER")
internal fun phoneCommands(environment: CommandEnvironment): CommandPart = object : CommandPart {
    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? = null
}
