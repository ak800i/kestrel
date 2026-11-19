package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Azure OpenAI and Microsoft Foundry (design D4, research r36): the API of a deployment follows its name as OpenClaw
 * chooses it (`extensions/microsoft-foundry/shared.ts`): deployments whose name starts with `claude` use Anthropic Messages at
 * `<endpoint>/anthropic`; deployments named `gpt-*`, `o1*`, `o3*`, `o4*`, `deepseek-v4*` or `computer-use-preview` use
 * Responses at `<endpoint>/openai/v1`; the rest Chat Completions there too. The v1 API needs no `api-version`.
 */
object AzureRouting {
    private val HOSTS = listOf(".openai.azure.com", ".services.ai.azure.com", ".cognitiveservices.azure.com")

    fun styleFor(deployment: String): RequestStyle {
        val name = deployment.trim().lowercase()
        return when {
            name.startsWith("claude") -> RequestStyle.ANTHROPIC
            name.startsWith("gpt-") || name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4") ||
                name.startsWith("deepseek-v4") || name == "computer-use-preview" -> RequestStyle.RESPONSES
            else -> RequestStyle.CHAT_COMPLETIONS
        }
    }

    /**
     * The endpoint that the owner typed, reduced to `https://<resource>.<Azure host>/`: it must be HTTPS on an Azure OpenAI
     * or Foundry host, without user information or a fragment, and its path may only be an `/openai…` or `/anthropic…`
     * suffix, which is dropped together with any query. Null when the address is none of these.
     */
    fun normalise(endpoint: String): HttpUrl? {
        val url = endpoint.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        if (HOSTS.none { url.host.endsWith(it) }) return null
        val path = url.encodedPath.trimEnd('/')
        if (path.isNotEmpty() && path != "/openai" && !path.startsWith("/openai/") && path != "/anthropic" && !path.startsWith("/anthropic/")) return null
        return url.newBuilder().encodedPath("/").query(null).build()
    }
}
