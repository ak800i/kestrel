package dev.kestrel.agent.port

import dev.kestrel.agent.memory.Fact

/**
 * Kestrel's memory as the agent reads and writes it (design D1, D20, D22 and D23). `:app`'s `SettingsMemoryStore` keeps
 * each part in its own encrypted `SettingKey`: the facts (`MEMORY_FACTS`) and the screenshot approvals per app and
 * provider (`SCREENSHOT_APPROVALS`).
 */
interface MemoryStore {
    suspend fun facts(): List<Fact>

    /** Stores [fact], replacing a fact with the same key. */
    suspend fun saveFact(fact: Fact)

    /** Deletes the fact with [key]; false when there is none. */
    suspend fun deleteFact(key: String): Boolean

    suspend fun screenshotApproved(packageName: String, providerId: String): Boolean

    suspend fun approveScreenshots(packageName: String, providerId: String)
}
