package dev.kestrel.app.settings

import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the settings pages read and change. [StoredSettingsData] connects them to Kestrel's stores; tests use a fake. */
interface SettingsData {
    val testMode: Flow<Boolean>

    suspend fun setTestMode(on: Boolean)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>

    suspend fun networkLog(): List<LogEntry<NetworkEvent>>

    /** Wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory()
}

class StoredSettingsData(private val graph: AppGraph) : SettingsData {
    override val testMode: Flow<Boolean> = graph.settings.observe(SettingKeys.SAFETY).map { it.testMode }

    override suspend fun setTestMode(on: Boolean) {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }

    override suspend fun activityLog() = graph.logs.activity()

    override suspend fun networkLog() = graph.logs.network()

    override suspend fun deleteHistory() {
        graph.logs.deleteHistory()
    }
}
