package dev.kestrel.app.debug

import android.content.Context
import android.os.Bundle
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError

/**
 * The debug hooks of sub-project 5's agent (design D28 of sub-project 5), in debug builds only. Plan 5A leaves the set
 * empty; plans 5B and 5C add their commands and branches below their anchors.
 */
// The graph, the context and the extras are for the commands of plans 5B and 5C.
@Suppress("unused", "UNUSED_PARAMETER")
internal class AgentHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, extras: Bundle): String = when (command) {
        // sub-project 5B: command branches
        "AGENT_TASK" -> AgentTaskHooks(graph, context).task(extras)
        "AGENT_ANSWER" -> AgentTaskHooks(graph, context).answer(extras)
        "AGENT_SCRIPT" -> AgentTaskHooks(graph, context).script(extras)
        "AGENT_STATE" -> AgentTaskHooks(graph, context).state(extras)
        "AGENT_CACHE" -> AgentTaskHooks(graph, context).cache(extras)
        "AGENT_TOUCH_SOURCE" -> AgentTaskHooks(graph, context).touchSource(extras)
        "AGENT_SETTINGS" -> AgentTaskHooks(graph, context).settings(extras)
        "AGENT_MEMORY" -> AgentTaskHooks(graph, context).memory(extras)
        "AGENT_DATA" -> AgentTaskHooks(graph, context).data(extras)
        "SELFCHAT_CHECK" -> AgentTaskHooks(graph, context).selfChat()
        "AGENT_TREE" -> AgentTaskHooks(graph, context).tree()
        "SCREEN_TAP" -> AgentTaskHooks(graph, context).tap(extras)
        // sub-project 5C: command branches
        // The live session of sitting 2 (design D28): only scripts\a71-agent.ps1 opens it, and closes it at the sitting's end.
        "AGENT_LIVE" -> {
            if (extras.containsKey("on")) {
                graph.settings.set(dev.kestrel.app.SettingKeys.AGENT_LIVE_SESSION, extras.getBoolean("on", false))
            }
            val live = "live=${if (graph.settings.get(dev.kestrel.app.SettingKeys.AGENT_LIVE_SESSION)) "on" else "off"}"
            // With --es model: whether the default profile's own model list, which the provider sent, holds that ID, so
            // that a71-agent.ps1 refuses a comparison model that the owner's account does not list (design D32 item 35).
            val model = extras.getString("model")
            if (model == null) {
                live
            } else {
                val profile = graph.settings.get(dev.kestrel.app.SettingKeys.PROVIDERS).defaultProfile
                val listed = profile != null && graph.providers.models(profile.id).models.any { it.id == model }
                "$live model=${if (listed) "listed" else "unlisted"}"
            }
        }
        // The screenshot approvals (design D20): with --es revoke, a71-agent.ps1 revokes those of one app, which its
        // sitting 2 stored, and keeps the owner's others; the answer holds only counts.
        "AGENT_APPROVALS" -> {
            val revoke = extras.getString("revoke")
            val stored = graph.settings.get(dev.kestrel.app.SettingKeys.SCREENSHOT_APPROVALS).approvals
            val chosen = if (revoke == null) emptyList() else stored.filter { it.packageName == revoke }
            chosen.forEach { graph.screenshotApprovals.revoke(it) }
            "approvals=${graph.settings.get(dev.kestrel.app.SettingKeys.SCREENSHOT_APPROVALS).approvals.size} revoked=${chosen.size}"
        }
        // sub-project 6B: command branches
        // The hosts that Kestrel's network gateway may reach, for :tools:eval's egress check (design D17 of sub-project 6):
        // the provider profiles' hosts and local servers, as the allowlist job applies them, and the model download's hosts.
        // Host names are no screen text; the tool writes them only into the results folder, which git ignores.
        "NETWORK_ALLOWLIST" -> {
            val setting = graph.settings.get(dev.kestrel.app.SettingKeys.PROVIDERS)
            val hosts = dev.kestrel.app.providers.ProviderAllowlist.profileHosts(setting, graph.catalog) +
                dev.kestrel.app.providers.ProviderAllowlist.localServers(setting)
            "hosts=${AgentHookAnswers.hostList(hosts)} modelHosts=${AgentHookAnswers.hostList(graph.models.manifest.downloadHosts)}"
        }
        else -> throw HookError("unknown agent hook")
    }

    companion object {
        val COMMANDS: Set<String> = setOf(
            // sub-project 5B: commands
            "AGENT_TASK", "AGENT_ANSWER", "AGENT_SCRIPT", "AGENT_STATE", "AGENT_CACHE", "AGENT_TOUCH_SOURCE", "AGENT_SETTINGS",
            "AGENT_MEMORY", "AGENT_DATA", "SELFCHAT_CHECK", "AGENT_TREE", "SCREEN_TAP",
            // sub-project 5C: commands
            "AGENT_LIVE", "AGENT_APPROVALS",
            // sub-project 6B: commands
            "NETWORK_ALLOWLIST",
        )
    }
}
