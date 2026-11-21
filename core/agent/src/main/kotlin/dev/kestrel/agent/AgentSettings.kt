package dev.kestrel.agent

import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.EmergencyNumbers
import dev.kestrel.safety.SafetyEnvironment
import dev.kestrel.safety.SafetySettings

/**
 * The settings that `:app` reads from `SettingsStore` when a task starts (design D1, D13, D16 and D18): the safety
 * settings (mode, test mode and the owner's names), the agent-free apps, Android's emergency-number check, the phone
 * packages with the default phone app, and the debug-only [selfChatAliases], which only the hook `agent-settings` fills
 * and which are empty by default (review B M8). toString prints no name and no address (D25).
 */
data class AgentSettings(
    val safety: SafetySettings = SafetySettings(),
    val agentFree: AgentFreeApps = AgentFreeApps(),
    val emergency: EmergencyNumbers = EmergencyNumbers.UNKNOWN,
    val phonePackages: Set<String> = EmergencyGuard.PHONE_PACKAGES,
    val selfChatAliases: Map<String, String> = emptyMap(),
) {
    /** What `SafetyCheck` needs besides the settings. */
    val environment: SafetyEnvironment
        get() = SafetyEnvironment(
            isAgentFree = agentFree::isAgentFree,
            emergency = emergency,
            phonePackages = phonePackages,
            selfChatAliases = selfChatAliases,
        )

    /** Where the agent never acts and no link may lead: Kestrel, the agent-free apps and Play services (D16, D32 item 12). */
    fun closed(packageName: String?): Boolean = agentFree.isAgentFree(packageName) || packageName == AgentFreeApps.PLAY_SERVICES

    override fun toString(): String =
        "AgentSettings(safety=$safety, agentFree=${agentFree.effective.size}, phonePackages=${phonePackages.size}, " +
            "selfChatAliases=${selfChatAliases.size})"
}
