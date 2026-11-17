package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult

/** The text commands of plan 3B (design spec, section 9.7). Plan 3B replaces this stub, which takes no command. */
@Suppress("UNUSED_PARAMETER")
internal fun textCommands(environment: CommandEnvironment): CommandPart = object : CommandPart {
    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? = null
}
