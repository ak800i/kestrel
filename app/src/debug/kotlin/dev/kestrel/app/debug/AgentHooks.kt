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
        "AGENT_TASK" -> AgentTaskHooks(graph, context).task(intent)
        "AGENT_ANSWER" -> AgentTaskHooks(graph, context).answer(intent)
        "AGENT_SCRIPT" -> AgentTaskHooks(graph, context).script(intent)
        "AGENT_STATE" -> AgentTaskHooks(graph, context).state(intent)
        "AGENT_CACHE" -> AgentTaskHooks(graph, context).cache(intent)
        "AGENT_TOUCH_SOURCE" -> AgentTaskHooks(graph, context).touchSource(intent)
        "AGENT_SETTINGS" -> AgentTaskHooks(graph, context).settings(intent)
        "AGENT_MEMORY" -> AgentTaskHooks(graph, context).memory(intent)
        "AGENT_DATA" -> AgentTaskHooks(graph, context).data(intent)
        "SELFCHAT_CHECK" -> AgentTaskHooks(graph, context).selfChat()
        "AGENT_TREE" -> AgentTaskHooks(graph, context).tree()
        "SCREEN_TAP" -> AgentTaskHooks(graph, context).tap(intent)
        // sub-project 5C: command branches
        else -> throw HookError("unknown agent hook")
    }

    companion object {
        val COMMANDS: Set<String> = setOf(
            // sub-project 5B: commands
            "AGENT_TASK", "AGENT_ANSWER", "AGENT_SCRIPT", "AGENT_STATE", "AGENT_CACHE", "AGENT_TOUCH_SOURCE", "AGENT_SETTINGS",
            "AGENT_MEMORY", "AGENT_DATA", "SELFCHAT_CHECK", "AGENT_TREE", "SCREEN_TAP",
            // sub-project 5C: commands
        )
    }
}
