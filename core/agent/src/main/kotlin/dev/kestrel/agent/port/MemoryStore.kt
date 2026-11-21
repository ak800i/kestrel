package dev.kestrel.agent.port

import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.RouteNote

/**
 * Kestrel's memory as the agent reads and writes it (design D1, D20, D22 and D23). `:app`'s `SettingsMemoryStore` keeps
 * each part in its own encrypted `SettingKey`: the facts (`MEMORY_FACTS`), the route notes per app (`ROUTE_NOTES`), the
 * contacts that tasks found (`FOUND_CONTACTS`) and the screenshot approvals per app and provider (`SCREENSHOT_APPROVALS`).
 */
interface MemoryStore {
    suspend fun facts(): List<Fact>

    /** Stores [fact], replacing a fact with the same key. */
    suspend fun saveFact(fact: Fact)

    /** Deletes the fact with [key]; false when there is none. */
    suspend fun deleteFact(key: String): Boolean

    suspend fun notes(packageName: String): List<RouteNote>

    /** Replaces the route notes of [packageName] (at most 20, the oldest gone first). */
    suspend fun saveNotes(packageName: String, notes: List<RouteNote>)

    /** Adds the names of contacts that `find_contact` found, for the boost words (D32 item 44). */
    suspend fun addFoundContacts(names: List<String>)

    suspend fun screenshotApproved(packageName: String, providerId: String): Boolean

    suspend fun approveScreenshots(packageName: String, providerId: String)
}
