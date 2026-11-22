package dev.kestrel.app.settings

import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.AgentFreeChanges

/**
 * The owner's edits of the agent-free apps (design D16 of sub-project 5): kept apart from the shipped list, as the names
 * he added and the shipped names he removed, so that a later version's new shipped names count. Kestrel's own app is
 * always agent-free and is never on the editable list.
 */
internal object AgentFreeEdits {
    fun add(changes: AgentFreeChanges, packageName: String): AgentFreeChanges = when {
        packageName == AgentFreeApps.KESTREL -> changes
        packageName in changes.removed -> changes.copy(removed = changes.removed - packageName)
        packageName in AgentFreeApps.SHIPPED || packageName in changes.added -> changes
        else -> changes.copy(added = changes.added + packageName)
    }

    fun remove(changes: AgentFreeChanges, packageName: String): AgentFreeChanges = when {
        packageName == AgentFreeApps.KESTREL -> changes
        packageName in changes.added -> changes.copy(added = changes.added - packageName)
        packageName in AgentFreeApps.SHIPPED && packageName !in changes.removed -> changes.copy(removed = changes.removed + packageName)
        else -> changes
    }

    /** The apps that the Safety page lists, without Kestrel itself. */
    fun listed(changes: AgentFreeChanges): Set<String> = AgentFreeApps.from(changes).effective - AgentFreeApps.KESTREL

    /** The installed launcher apps that are not agent-free and whose label sounds like money or passwords (design D16). */
    fun suggestions(changes: AgentFreeChanges, apps: List<LauncherApp>): List<LauncherApp> {
        val list = AgentFreeApps.from(changes)
        return apps.filter { !list.isAgentFree(it.packageName) && AgentFreeApps.suggests(it.label) }.distinctBy { it.packageName }
    }
}

/** The owner's e-mail addresses as he types them on the Safety page: separated by commas, semicolons or spaces (design D18). */
internal fun ownerEmails(text: String): List<String> = text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
