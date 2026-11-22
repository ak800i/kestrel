package dev.kestrel.app.agent.eyes

import dev.kestrel.app.SettingKeys
import dev.kestrel.privacy.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * One "always for this app" answer to the screenshot question: this app's screenshots may go to this provider (design D20
 * of sub-project 5).
 */
@Serializable
data class ScreenshotApproval(val packageName: String, val providerId: String) {
    override fun toString(): String = "ScreenshotApproval(package=${packageName.length} chars, provider=${providerId.length} chars)"
}

/** The stored approvals, in the encrypted setting `SCREENSHOT_APPROVALS`. */
@Serializable
data class ScreenshotApprovalList(val approvals: List<ScreenshotApproval> = emptyList())

/** An approval as the Safety page shows it, with the app's and the provider's names. */
data class ScreenshotApprovalRow(val approval: ScreenshotApproval, val app: String, val provider: String) {
    override fun toString(): String = "ScreenshotApprovalRow(app=${app.length} chars, provider=${provider.length} chars)"
}

/**
 * The screenshot approvals per app and provider (design D20 and D26 of sub-project 5): the agent's memory reads and adds
 * them through `AppGraph`, and the Safety page lists and revokes them. A later profile may use another provider, so an
 * approval names both.
 */
internal class ScreenshotApprovals(private val settings: SettingsStore) {
    private val writes = Mutex()

    val all: Flow<List<ScreenshotApproval>> = settings.observe(SettingKeys.SCREENSHOT_APPROVALS).map { it.approvals }

    suspend fun approved(packageName: String, providerId: String): Boolean =
        ScreenshotApproval(packageName, providerId) in settings.get(SettingKeys.SCREENSHOT_APPROVALS).approvals

    suspend fun approve(packageName: String, providerId: String) = writes.withLock {
        val stored = settings.get(SettingKeys.SCREENSHOT_APPROVALS)
        val approval = ScreenshotApproval(packageName, providerId)
        if (approval !in stored.approvals) settings.set(SettingKeys.SCREENSHOT_APPROVALS, stored.copy(approvals = stored.approvals + approval))
    }

    suspend fun revoke(approval: ScreenshotApproval) = writes.withLock {
        val stored = settings.get(SettingKeys.SCREENSHOT_APPROVALS)
        settings.set(SettingKeys.SCREENSHOT_APPROVALS, stored.copy(approvals = stored.approvals - approval))
    }

    override fun toString(): String = "ScreenshotApprovals"
}
