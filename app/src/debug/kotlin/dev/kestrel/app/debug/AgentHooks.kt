package dev.kestrel.app.debug

import android.content.Context
import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError

/**
 * The debug hooks of sub-project 5's agent (design D28 of sub-project 5), in debug builds only. Plan 5A leaves the set
 * empty; plans 5B and 5C add their commands and branches below their anchors.
 */
// The graph, the context and the intent are for the commands of plans 5B and 5C.
@Suppress("unused", "UNUSED_PARAMETER")
internal class AgentHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        // sub-project 5B: command branches
        // sub-project 5C: command branches
        else -> throw HookError("unknown agent hook")
    }

    companion object {
        val COMMANDS: Set<String> = setOf(
            // sub-project 5B: commands
            // sub-project 5C: commands
        )
    }
}
