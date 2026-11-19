package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One of Copilot's quotas: `premium` or `chat`, and the percentage that Copilot reports as left. */
data class CopilotQuota(val label: String, val percentRemaining: Double)

/**
 * "Check allowance" on the profile page (design D12; research r27 §6 and §8): `GET https://api.<domain>/copilot_internal/user`
 * with `Authorization: token <token>`, the IDE headers only and `X-Github-Api-Version: 2025-04-01`, never
 * `Copilot-Integration-Id`. It returns only the quotas whose `percent_remaining` is a number; under AI credits these may
 * be missing (research r28), and Kestrel never computes a remaining figure itself.
 */
class CopilotAllowance(private val http: ProviderHttp, private val ideHeaders: Map<String, String>) {
    private val json = Json { isLenient = true }

    suspend fun check(token: Credential.GitHubToken): List<CopilotQuota> {
        val headers = ideHeaders + mapOf("X-Github-Api-Version" to API_VERSION, "Accept" to "application/json")
        val reply = http.send(HttpRequest("GET", CopilotDomain.userUrl(token.domain), headers), GitHubAuth(token.token, "token"))
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val snapshots = body?.get("quota_snapshots") as? JsonObject ?: return emptyList()
        return listOfNotNull(quota(snapshots, "premium_interactions", "premium"), quota(snapshots, "chat", "chat"))
    }

    private fun quota(snapshots: JsonObject, key: String, label: String): CopilotQuota? {
        val value = ((snapshots[key] as? JsonObject)?.get("percent_remaining") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return value?.let { CopilotQuota(label, it) }
    }

    private companion object {
        const val API_VERSION = "2025-04-01"
    }
}
