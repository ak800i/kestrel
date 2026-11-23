package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost

/**
 * A test alias in Kestrel's memory (hook agent-memory), such as "Ana = the Telegram chat 'Saved Messages'", whose [key]
 * and [text] may hold the run's placeholders. agent-memory cannot read a fact back, so the step first asks whether the
 * key exists (`agent-memory -Has`, design D4 and D17 of sub-project 6) and fails the run when it does: its restore, which
 * removes the key, then never removes a fact of the owner's.
 */
data class MemoryAlias(val key: String, val text: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val name = scope.values.fill(key)
        if (has(scope, name)) throw SetupFailed("Kestrel's memory already has a fact with the run's key, which the run does not touch.")
        scope.restores.push("the test alias in Kestrel's memory", "Kestrel's settings > Memory: delete the alias of the test name.") {
            try {
                scope.hooks.agentMemoryRemove(name)
            } catch (e: HookLost) {
                if (scope.hooks.agentMemoryHas(name)) throw e
            }
        }
        try {
            scope.hooks.agentMemoryAdd(name, scope.values.fill(text))
        } catch (e: HookLost) {
            if (!has(scope, name)) throw SetupFailed("The test alias did not reach Kestrel's memory.")
        }
        return SetupResult.Ready
    }

    /** Whether the key exists; an error of the hook fails the setup, because the key may then be the owner's. */
    private fun has(scope: RunScope, name: String): Boolean = try {
        scope.hooks.agentMemoryHas(name)
    } catch (e: HookError) {
        throw SetupFailed("Kestrel's memory could not say whether the run's key exists.")
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("memory-key" to if (scope.hooks.agentMemoryHas(scope.values.fill(key))) "yes" else "no")
}

/** The debug aliases that let test mode's self-chat rules take a fixture for an app (hook agent-settings -SelfChatAliases), cleared after the run. */
data class SelfChatAliases(val aliases: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("the fixtures' self-chat aliases", "Run: scripts\\hook.ps1 -Serial ${scope.device.serial} agent-settings -SelfChatAliases ''") {
            try {
                scope.hooks.agentSettings(selfChatAliases = "")
            } catch (e: HookLost) {
                if (scope.hooks.agentSettings().int("selfChatAliases") != 0) throw e
            }
        }
        try {
            scope.hooks.agentSettings(selfChatAliases = aliases)
        } catch (e: HookLost) {
            if (scope.hooks.agentSettings().int("selfChatAliases") == 0) throw SetupFailed("The fixtures' self-chat aliases did not reach Kestrel.")
        }
        return SetupResult.Ready
    }

    companion object {
        /** The fixture app counts as Telegram for test mode's self-chat rules. */
        val TELEGRAM = SelfChatAliases("${Fixtures.PACKAGE}=org.telegram.messenger")
    }
}

/**
 * The owner's own addresses from the local configuration in test mode's names (hook agent-settings -OwnerEmails), so
 * that test mode lets an e-mail to himself through (design D4 of sub-project 6). The hook answers only a count, so the
 * addresses cannot be read back: when test mode had none, the restore empties the list again, so that test mode keeps
 * blocking every e-mail of the agent's outside a run, as scripts\a71-agent.ps1 needs; when it had some, they are the
 * owner's own, and they stay.
 */
data object OwnerEmails : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val addresses = listOfNotNull(scope.config.gmailAddress, scope.config.emulatorGmailAddress).distinct()
        if (addresses.isEmpty()) return SetupResult.Untestable("corpus\\owner\\a71-config.json gives no address of the owner's")
        if (scope.hooks.agentSettings().int("ownerEmails") == 0) {
            scope.restores.push("test mode's owner addresses emptied", "Run: scripts\\hook.ps1 -Serial ${scope.device.serial} agent-settings -OwnerEmails ''") {
                scope.hooks.agentSettings(ownerEmails = "")
            }
        }
        try {
            scope.hooks.agentSettings(ownerEmails = addresses.joinToString(","))
        } catch (e: HookLost) {
            if (scope.hooks.agentSettings().int("ownerEmails") == 0) throw SetupFailed("The owner's addresses did not reach test mode.")
        }
        return SetupResult.Ready
    }
}
