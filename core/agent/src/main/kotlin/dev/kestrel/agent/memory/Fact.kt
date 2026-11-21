package dev.kestrel.agent.memory

import kotlinx.serialization.Serializable

/** Whether a fact names something (a contact, a chat, a place, an app) or is any other fact (design D22). */
@Serializable
enum class FactKind { ALIAS, FACT }

/**
 * A fact that the owner saved (design D22; spec 11.9), such as "home = Main Street 1" or "Ana = the Telegram chat 'Saved
 * Messages'". It is stored encrypted in `SettingKey` `MEMORY_FACTS`; [created] is the time in epoch milliseconds.
 * toString prints only lengths (D25).
 */
@Serializable
data class Fact(val key: String, val value: String, val kind: FactKind = FactKind.FACT, val created: Long = 0) {
    override fun toString(): String = "Fact(keyLength=${key.length}, valueLength=${value.length}, kind=$kind)"
}
